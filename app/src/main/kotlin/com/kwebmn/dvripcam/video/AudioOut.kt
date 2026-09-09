package com.kwebmn.dvripcam.video

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/** Декодирование G.711 (A-law / µ-law) в PCM16 и воспроизведение через AudioTrack (8 кГц, моно). */
object G711 {
    private val alaw = ShortArray(256)
    private val ulaw = ShortArray(256)

    init {
        for (i in 0..255) {
            alaw[i] = decodeAlaw(i)
            ulaw[i] = decodeUlaw(i)
        }
    }

    private fun decodeAlaw(aval0: Int): Short {
        var a = aval0 xor 0x55
        var t = (a and 0x0F) shl 4
        val seg = (a and 0x70) shr 4
        when (seg) {
            0 -> t += 8
            1 -> t += 0x108
            else -> { t += 0x108; t = t shl (seg - 1) }
        }
        return (if (a and 0x80 != 0) t else -t).toShort()
    }

    private fun decodeUlaw(uval: Int): Short {
        val u = uval.inv() and 0xFF
        val t = ((u and 0x0F) shl 3) + 0x84
        val shifted = t shl ((u and 0x70) shr 4)
        val v = shifted - 0x84
        return (if (u and 0x80 != 0) -v else v).toShort()
    }

    /** format: байт формата из Sofia-аудиокадра. 0x0E → A-law, иначе (0x0A) → µ-law по умолчанию A-law. */
    fun toPcm16(payload: ByteArray, format: Int): ShortArray {
        val table = if (format == 0x0A || format == 0x07) ulaw else alaw
        return ShortArray(payload.size) { table[payload[it].toInt() and 0xFF] }
    }
}

/** Простой проигрыватель PCM16 8кГц моно. Потокобезопасен для write из потока чтения сети. */
class AudioOut(private val sampleRate: Int = 8000) {
    private var track: AudioTrack? = null
    @Volatile private var playing = false

    fun start() {
        if (playing) return
        val min = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        playing = true
    }

    fun write(pcm: ShortArray) {
        if (!playing) return
        runCatching { track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING) }
    }

    fun stop() {
        playing = false
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }
}
