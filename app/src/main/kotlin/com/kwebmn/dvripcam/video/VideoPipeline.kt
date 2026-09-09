package com.kwebmn.dvripcam.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Инкрементальный извлекатель Annex-B NAL из потока DVRIP-payload.
 * Кадры камеры завёрнуты в DHAV (00 00 01 FC/FD), реальные H.264 NAL используют
 * 4-байтный старт-код 00 00 00 01 — по нему и режем.
 */
class NalExtractor(private val onNal: (ByteArray) -> Unit) {
    private val buf = ByteArrayOutputStream()

    fun feed(data: ByteArray) {
        buf.write(data)
        val arr = buf.toByteArray()
        val pos = ArrayList<Int>()
        var i = 0
        while (i <= arr.size - 4) {
            if (arr[i].toInt() == 0 && arr[i + 1].toInt() == 0 && arr[i + 2].toInt() == 0 && arr[i + 3].toInt() == 1) {
                pos.add(i); i += 4
            } else i++
        }
        if (pos.size < 2) {
            // не даём буферу расти бесконечно, если старт-кодов нет
            if (arr.size > 4_000_000) buf.reset()
            return
        }
        for (k in 0 until pos.size - 1) {
            onNal(arr.copyOfRange(pos[k], pos[k + 1]))
        }
        // оставить хвост от последнего старт-кода
        buf.reset()
        buf.write(arr, pos.last(), arr.size - pos.last())
    }
}

/** Тип NAL по первому байту после старт-кода. */
private fun nalType(nal: ByteArray): Int {
    var p = 0
    if (nal.size > 4 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 0 && nal[3].toInt() == 1) p = 4
    else if (nal.size > 3 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 1) p = 3
    return if (p < nal.size) nal[p].toInt() and 0x1F else -1
}

/**
 * Декодер H.264 через MediaCodec с выводом на Surface.
 * Собирает SPS/PPS, конфигурируется по ним и гонит кадры в отдельном потоке.
 */
class H264Decoder(
    private val surface: Surface,
    private val fallbackSize: Pair<Int, Int> = 1280 to 720,
    private val onError: (String) -> Unit = {},
    private val onVideoSize: (Int, Int) -> Unit = { _, _ -> },
) {
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val queue = LinkedBlockingQueue<ByteArray>(120)
    @Volatile private var running = false
    private var thread: Thread? = null

    fun submitNal(nal: ByteArray) {
        when (nalType(nal)) {
            7 -> { sps = nal; tryConfigure() }
            8 -> { pps = nal; tryConfigure() }
            else -> if (codec != null) { if (!queue.offer(nal)) { queue.poll(); queue.offer(nal) } }
        }
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
            val c = MediaCodec.createDecoderByType("video/avc")
            c.configure(fmt, surface, null, 0)
            c.start()
            codec = c
            running = true
            thread = Thread { drainLoop(c) }.apply { isDaemon = true; start() }
        } catch (e: Exception) {
            onError("Декодер: ${e.message}")
        }
    }

    private fun drainLoop(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            try {
                val nal = queue.poll(20, TimeUnit.MILLISECONDS)
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
                    c.releaseOutputBuffer(outIdx, true) // рендер на Surface
                    outIdx = c.dequeueOutputBuffer(info, 0)
                }
            } catch (e: Exception) {
                if (running) onError("Поток декода: ${e.message}")
                break
            }
        }
    }

    fun stop() {
        running = false
        runCatching { thread?.join(300) }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }
}
