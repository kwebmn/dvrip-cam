package com.kwebmn.dvripcam.video

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Декодирует первый ключевой кадр H.264 в JPEG (без Surface — в память).
 * Нужен для миниатюр архива: камера команду OPCompressPic не поддерживает (Ret 109),
 * поэтому превью делаем сами из короткого куска плейбэка.
 */
class KeyFrameGrabber {
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var codec: MediaCodec? = null
    private var done = false

    /** JPEG первого декодированного кадра, либо null пока не готово. */
    var jpeg: ByteArray? = null
        private set

    val isDone: Boolean get() = done

    fun submit(nal: ByteArray) {
        if (done) return
        when (nalType(nal)) {
            7 -> sps = nal
            8 -> pps = nal
            5 -> { if (start()) feed(nal) }      // только I-кадр: он самодостаточен
            else -> {}
        }
    }

    private fun start(): Boolean {
        if (codec != null) return true
        val s = sps ?: return false
        val p = pps ?: return false
        val (w, h) = SpsParser.dimensions(s) ?: (1280 to 720)
        return try {
            val fmt = MediaFormat.createVideoFormat("video/avc", w, h)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(s))
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(p))
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodec.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val c = MediaCodec.createDecoderByType("video/avc")
            c.configure(fmt, null, null, 0)   // без Surface — читаем пиксели
            c.start()
            codec = c
            true
        } catch (e: Exception) {
            done = true
            false
        }
    }

    private fun feed(nal: ByteArray) {
        val c = codec ?: return
        try {
            val inIdx = c.dequeueInputBuffer(200_000)
            if (inIdx >= 0) {
                c.getInputBuffer(inIdx)!!.apply { clear(); put(nal) }
                c.queueInputBuffer(inIdx, 0, nal.size, 0, 0)
            }
            val info = MediaCodec.BufferInfo()
            var tries = 0
            while (tries++ < 40 && !done) {
                val outIdx = c.dequeueOutputBuffer(info, 50_000)
                when {
                    outIdx >= 0 -> {
                        jpeg = runCatching { imageToJpeg(c, outIdx) }.getOrNull()
                        c.releaseOutputBuffer(outIdx, false)
                        done = true
                    }
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (tries > 12) break
                    else -> {} // формат сменился и т.п. — продолжаем
                }
            }
        } catch (e: Exception) {
            done = true
        }
    }

    private fun imageToJpeg(c: MediaCodec, outIdx: Int): ByteArray? {
        val img = c.getOutputImage(outIdx) ?: return null
        val w = img.width; val h = img.height
        val nv21 = yuv420ToNv21(img, w, h) ?: return null
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null)
            .compressToJpeg(Rect(0, 0, w, h), 70, out)
        return out.toByteArray()
    }

    /** YUV_420_888 -> NV21 (Y, затем чередование V/U). */
    private fun yuv420ToNv21(img: android.media.Image, w: Int, h: Int): ByteArray? {
        return try {
            val out = ByteArray(w * h * 3 / 2)
            val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
            // Y
            var pos = 0
            val yBuf = yP.buffer; val yRow = yP.rowStride
            val row = ByteArray(yRow)
            for (r in 0 until h) {
                yBuf.position(r * yRow)
                val len = minOf(yRow, yBuf.remaining())
                yBuf.get(row, 0, len)
                System.arraycopy(row, 0, out, pos, w); pos += w
            }
            // VU
            val uBuf = uP.buffer; val vBuf = vP.buffer
            val uRow = uP.rowStride; val vRow = vP.rowStride
            val uPix = uP.pixelStride; val vPix = vP.pixelStride
            val cw = w / 2; val ch = h / 2
            for (r in 0 until ch) {
                for (col in 0 until cw) {
                    val vi = r * vRow + col * vPix
                    val ui = r * uRow + col * uPix
                    out[pos++] = if (vi < vBuf.limit()) vBuf.get(vi) else 0
                    out[pos++] = if (ui < uBuf.limit()) uBuf.get(ui) else 0
                }
            }
            out
        } catch (e: Exception) {
            null
        } finally {
            runCatching { img.close() }
        }
    }

    fun saveTo(file: File): Boolean {
        val data = jpeg ?: return false
        return try {
            FileOutputStream(file).use { it.write(data) }
            true
        } catch (e: Exception) { false }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }
}
