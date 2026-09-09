package com.kwebmn.dvripcam.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Складывает поток H.264 (Annex-B NAL) в MP4 через MediaMuxer.
 * SPS/PPS уходят в csd, кадры (VCL NAL) пишутся сэмплами с равномерными PTS (~[fps] кадр/с,
 * т.к. точный таймстамп из DHAV мы не извлекаем — для просмотра клипа этого достаточно).
 */
class Mp4Saver(private val outFile: File, private val fps: Int = 15) {
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var started = false
    private var frameIndex = 0L
    private var lastError: String? = null

    val error: String? get() = lastError
    var frames: Long = 0; private set

    fun onNal(nal: ByteArray) {
        when (nalTypeOf(nal)) {
            7 -> { sps = nal; tryStart() }
            8 -> { pps = nal; tryStart() }
            5 -> writeSample(nal, key = true)
            1 -> writeSample(nal, key = false)
            else -> {} // SEI/AUD и пр. — пропускаем
        }
    }

    private fun tryStart() {
        if (started || sps == null || pps == null) return
        try {
            val (w, h) = SpsParser.dimensions(sps!!) ?: (1280 to 720)
            val fmt = MediaFormat.createVideoFormat("video/avc", w, h)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            val m = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            track = m.addTrack(fmt)
            m.start()
            muxer = m
            started = true
        } catch (e: Exception) {
            lastError = "MP4 init: ${e.message}"
        }
    }

    private fun writeSample(nal: ByteArray, key: Boolean) {
        val m = muxer ?: return
        if (!started) return
        try {
            val info = MediaCodec.BufferInfo()
            info.offset = 0
            info.size = nal.size
            info.presentationTimeUs = frameIndex * 1_000_000L / fps
            info.flags = if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            m.writeSampleData(track, ByteBuffer.wrap(nal), info)
            frameIndex++; frames = frameIndex
        } catch (e: Exception) {
            lastError = "MP4 write: ${e.message}"
        }
    }

    /** Завершить файл. Возвращает true, если хоть что-то записано без ошибок. */
    fun finish(): Boolean {
        return try {
            muxer?.let { it.stop(); it.release() }
            muxer = null
            started && lastError == null && frames > 0
        } catch (e: Exception) {
            lastError = "MP4 finish: ${e.message}"; false
        }
    }
}

private fun nalTypeOf(nal: ByteArray): Int {
    var p = 0
    if (nal.size > 4 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 0 && nal[3].toInt() == 1) p = 4
    else if (nal.size > 3 && nal[0].toInt() == 0 && nal[1].toInt() == 0 && nal[2].toInt() == 1) p = 3
    return if (p < nal.size) nal[p].toInt() and 0x1F else -1
}
