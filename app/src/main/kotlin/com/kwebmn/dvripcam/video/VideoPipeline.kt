package com.kwebmn.dvripcam.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Инкрементальный извлекатель Annex-B NAL из потока DVRIP-payload.
 * Кадры камеры завёрнуты в DHAV (00 00 01 FC/FD), реальные H.264 NAL используют
 * 4-байтный старт-код 00 00 00 01 — по нему и режем. Буфер компактится к хвосту,
 * поэтому не растёт и не пересканируется целиком.
 */
class NalExtractor(private val onNal: (ByteArray) -> Unit) {
    private val buf = ByteArrayOutputStream()

    fun feed(data: ByteArray) {
        buf.write(data)
        val arr = buf.toByteArray()
        val pos = ArrayList<Int>(8)
        var i = 0
        while (i <= arr.size - 4) {
            if (arr[i].toInt() == 0 && arr[i + 1].toInt() == 0 && arr[i + 2].toInt() == 0 && arr[i + 3].toInt() == 1) {
                pos.add(i); i += 4
            } else i++
        }
        if (pos.size < 2) {
            if (arr.size > 4_000_000) buf.reset()
            return
        }
        for (k in 0 until pos.size - 1) onNal(arr.copyOfRange(pos[k], pos[k + 1]))
        buf.reset()
        buf.write(arr, pos.last(), arr.size - pos.last())
    }
}

/** Тип NAL по первому байту после старт-кода. */
fun nalType(nal: ByteArray): Int {
    var p = 0
    if (nal.size > 4 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 0 && nal[3].toInt() == 1) p = 4
    else if (nal.size > 3 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 1) p = 3
    return if (p < nal.size) nal[p].toInt() and 0x1F else -1
}

/** Режим буферизации: компромисс задержка/плавность. */
enum class LatencyMode { LOW, SMOOTH, ADAPTIVE }

/** Снимок статистики для HUD. */
data class VideoStats(val fps: Int, val kbps: Int, val bufferedFrames: Int, val latencyMs: Int)

/**
 * Декодер H.264 через MediaCodec на Surface с низкой задержкой.
 * - LOW: мелкая очередь, при переполнении дроп до следующего I-кадра (реалтайм).
 * - SMOOTH: буфер побольше, при переполнении дроп самого старого (плавнее).
 * - ADAPTIVE: старт как LOW, при недогрузах наращивает буфер, при простое ужимает.
 * Переконфигурируется при смене SPS (переключение HD/SD на лету).
 */
class H264Decoder(
    private val surface: Surface,
    private val fallbackSize: Pair<Int, Int> = 1280 to 720,
    private val onError: (String) -> Unit = {},
    private val onVideoSize: (Int, Int) -> Unit = { _, _ -> },
) {
    @Volatile var mode: LatencyMode = LatencyMode.ADAPTIVE

    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var configuredSps: ByteArray? = null
    private val queue = LinkedBlockingDeque<ByteArray>()
    @Volatile private var running = false
    @Volatile private var needKeyframe = true
    private var thread: Thread? = null

    // адаптивный размер буфера (в кадрах)
    @Volatile private var adaptiveCap = 2
    @Volatile private var underruns = 0

    // статистика
    @Volatile private var inBytes = 0L
    @Volatile private var rendered = 0
    private var lastStatsAt = System.nanoTime()
    private var lastFps = 0

    private fun cap(): Int = when (mode) {
        LatencyMode.LOW -> 2
        LatencyMode.SMOOTH -> 12
        LatencyMode.ADAPTIVE -> adaptiveCap
    }

    fun submitNal(nal: ByteArray) {
        when (nalType(nal)) {
            7 -> { // SPS: смена разрешения -> переконфиг
                if (configuredSps != null && !nal.contentEquals(configuredSps)) reset()
                sps = nal; tryConfigure()
            }
            8 -> { pps = nal; tryConfigure() }
            else -> enqueue(nal)
        }
    }

    private fun enqueue(nal: ByteArray) {
        val c = codec ?: return
        val t = nalType(nal)
        if (needKeyframe && t != 5) return       // ждём I-кадр после дропа/старта
        if (t == 5) needKeyframe = false
        inBytes += nal.size
        val limit = cap()
        if (queue.size >= limit) {
            if (mode == LatencyMode.LOW) {
                queue.clear()
                if (t != 5) { needKeyframe = true; return } // ждём следующий I-кадр
            } else {
                queue.pollFirst() // дроп самого старого
            }
        }
        queue.offerLast(nal)
    }

    @Synchronized
    private fun tryConfigure() {
        if (codec != null || sps == null || pps == null) return
        try {
            val (w, h) = SpsParser.dimensions(sps!!) ?: fallbackSize
            onVideoSize(w, h)
            val fmt = MediaFormat.createVideoFormat("video/avc", w, h)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val c = MediaCodec.createDecoderByType("video/avc")
            c.configure(fmt, surface, null, 0)
            c.start()
            codec = c
            configuredSps = sps
            running = true
            needKeyframe = true
            thread = Thread { drainLoop(c) }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            onError("Декодер: ${e.message}")
        }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val nal = queue.pollFirst(20, TimeUnit.MILLISECONDS)
                if (nal != null) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = c.getInputBuffer(inIdx)!!
                        ib.clear(); ib.put(nal)
                        c.queueInputBuffer(inIdx, 0, nal.size, System.nanoTime() / 1000, 0)
                    }
                } else if (mode == LatencyMode.ADAPTIVE) {
                    // очередь пуста при рендере — сеть не поспевает, наращиваем буфер
                    underruns++
                    if (underruns >= 3 && adaptiveCap < 12) { adaptiveCap++; underruns = 0 }
                }
                var outIdx = c.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0) {
                    c.releaseOutputBuffer(outIdx, true)
                    rendered++
                    outIdx = c.dequeueOutputBuffer(info, 0)
                }
            } catch (e: Exception) {
                if (running) onError("Поток декода: ${e.message}")
                break
            }
        }
    }

    /** Сброс к следующему I-кадру (при реконнекте/после разрыва) — без пересоздания кодека. */
    fun requestKeyframe() { queue.clear(); needKeyframe = true }

    /** Снимок статистики с момента прошлого вызова (fps/битрейт/буфер/задержка). */
    fun snapshotStats(): VideoStats {
        val now = System.nanoTime()
        val dtSec = (now - lastStatsAt) / 1e9
        val fps = if (dtSec > 0.2) (rendered / dtSec).toInt() else lastFps
        val kbps = if (dtSec > 0.2) (inBytes * 8 / 1000 / dtSec).toInt() else 0
        val buffered = queue.size
        val latency = (buffered * 1000) / maxOf(fps, 1)
        if (dtSec > 0.2) { lastStatsAt = now; inBytes = 0; rendered = 0; lastFps = fps }
        return VideoStats(fps, kbps, buffered, latency)
    }

    /** Полный сброс кодека (при смене разрешения). */
    @Synchronized
    fun reset() {
        running = false
        runCatching { thread?.join(200) }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null; configuredSps = null; sps = null; pps = null
        queue.clear(); needKeyframe = true
    }

    fun stop() {
        running = false
        runCatching { thread?.join(300) }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }
}
