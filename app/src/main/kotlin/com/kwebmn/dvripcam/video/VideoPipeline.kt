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

/** Предел адаптивного буфера (кадров). */
private const val MAX_CAP = 24

/**
 * Декодер H.264 через MediaCodec на Surface.
 * Размер очереди задаёт компромисс задержка/плавность: LOW=4, SMOOTH=24,
 * ADAPTIVE стартует с 8 и растёт при переполнении.
 * При переполнении очередь чистится и ждём следующий I-кадр (выкидывать кадры
 * из середины GOP нельзя — будет «каша»).
 * Кодек пересоздаётся только при реальной смене разрешения (HD<->SD).
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
    private var configuredDims: Pair<Int, Int>? = null
    private val queue = LinkedBlockingDeque<ByteArray>()
    @Volatile private var running = false
    @Volatile private var needKeyframe = true
    private var thread: Thread? = null

    // адаптивный размер буфера (в кадрах): растёт при переполнении
    @Volatile private var adaptiveCap = 8

    // статистика
    @Volatile private var inBytes = 0L
    @Volatile private var rendered = 0
    private var lastStatsAt = System.nanoTime()
    private var lastFps = 0

    private fun cap(): Int = when (mode) {
        LatencyMode.LOW -> 4
        LatencyMode.SMOOTH -> 24
        LatencyMode.ADAPTIVE -> adaptiveCap
    }

    fun submitNal(nal: ByteArray) {
        when (nalType(nal)) {
            7 -> { // SPS: переконфиг ТОЛЬКО при реальной смене разрешения (HD<->SD),
                   // а не при любом отличии байтов — иначе кодек пересоздавался бы каждый GOP.
                val dims = SpsParser.dimensions(nal)
                val cur = configuredDims
                if (codec != null && dims != null && cur != null && dims != cur) reset()
                sps = nal; tryConfigure()
            }
            8 -> { pps = nal; tryConfigure() }
            else -> enqueue(nal)
        }
    }

    private fun enqueue(nal: ByteArray) {
        codec ?: return
        val t = nalType(nal)
        if (needKeyframe && t != 5) return       // ждём I-кадр после дропа/старта
        if (t == 5) needKeyframe = false
        inBytes += nal.size
        if (queue.size >= cap()) {
            // Переполнение = декодер не успевает. Выбрасывать кадры из середины GOP нельзя
            // (получим «кашу»), поэтому чистим очередь и ждём следующий I-кадр.
            queue.clear()
            if (mode == LatencyMode.ADAPTIVE && adaptiveCap < MAX_CAP) adaptiveCap += 2
            if (t != 5) { needKeyframe = true; return }
        }
        queue.offerLast(nal)
    }

    /**
     * Создать и запустить кодек. [lowLatency] добавляет KEY_LOW_LATENCY — его понимают
     * не все декодеры, поэтому вызывающий делает фолбэк без него.
     */
    private fun createCodec(w: Int, h: Int, lowLatency: Boolean): MediaCodec? {
        var c: MediaCodec? = null
        return try {
            val fmt = MediaFormat.createVideoFormat("video/avc", w, h)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fmt.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            c = MediaCodec.createDecoderByType("video/avc")
            c.configure(fmt, surface, null, 0)
            c.start()
            c
        } catch (e: Exception) {
            runCatching { c?.release() }
            null
        }
    }

    @Synchronized
    private fun tryConfigure() {
        if (codec != null || sps == null || pps == null) return
        val (w, h) = SpsParser.dimensions(sps!!) ?: fallbackSize
        // Сначала пробуем low-latency, при отказе — обычную конфигурацию.
        val c = createCodec(w, h, lowLatency = true) ?: createCodec(w, h, lowLatency = false)
        if (c == null) { onError("Декодер не запустился (${w}x$h)"); return }
        // ВАЖНО: сообщаем размер только после успешного старта — иначе UI спрячет
        // спиннер и текст ошибки, и пользователь увидит чёрный экран без причины.
        onVideoSize(w, h)
        codec = c
        configuredDims = w to h
        running = true
        needKeyframe = true
        thread = Thread { drainLoop(c) }.apply { isDaemon = true; start() }
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
        codec = null; configuredDims = null; sps = null; pps = null
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
