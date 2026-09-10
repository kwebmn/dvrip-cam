package com.kwebmn.dvripcam

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.kwebmn.dvripcam.dvrip.DvripClient
import com.kwebmn.dvripcam.video.AudioOut
import com.kwebmn.dvripcam.video.G711
import com.kwebmn.dvripcam.video.SofiaDemuxer
import kotlinx.coroutines.*
import javax.net.SocketFactory

/**
 * Talk-back (микрофон телефона → динамик камеры). Работает на ОТДЕЛЬНОМ DVRIP-соединении,
 * чтобы не конфликтовать с читающим циклом живого потока. Push-to-talk: start() на нажатие,
 * stop() на отпускание.
 *
 * ВНИМАНИЕ: точный формат talk-кадра у XM-прошивок различается — фича экспериментальная.
 */
class TalkSession(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val pass: String,
    private val wifiSf: SocketFactory?,
    private val onStatus: (String) -> Unit,
) {
    @Volatile private var running = false
    private var scope: CoroutineScope? = null
    private var client: DvripClient? = null

    @SuppressLint("MissingPermission") // разрешение проверяется в UI перед вызовом
    fun start() {
        if (running) return
        running = true
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob()); scope = s
        s.launch {
            var record: AudioRecord? = null
            try {
                onStatus("Рация: соединяюсь…")
                val c = DvripClient(host, port); client = c
                c.connect(4000, wifiSf)
                if (!c.login(user, pass)) { onStatus("Рация: ошибка логина"); return@launch }
                val ret = c.talkStart()
                if (ret != 100 && ret != 0) {
                    onStatus("Рация: камера отклонила talk (Ret=$ret) ${c.lastTalkResp.take(120)}")
                    return@launch
                }

                val minBuf = AudioRecord.getMinBufferSize(
                    8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(3200)
                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    onStatus("Рация: микрофон недоступен"); return@launch
                }
                record.startRecording()
                onStatus("🎤 Говорите… (слышно камеру)")

                // Двусторонний звук: параллельно читаем возврат камеры (mid 1433, DHAV FA) и играем.
                val audioOut = AudioOut().also { it.start() }
                val rxDemux = SofiaDemuxer(onVideo = {}, onAudio = { payload, fmt ->
                    audioOut.write(G711.toPcm16(payload, fmt))
                })
                val rxJob = launch {
                    try {
                        while (running && isActive) {
                            val (_, body) = c.readRawPacket()
                            if (body.isEmpty() || body[0] == '{'.code.toByte()) continue
                            rxDemux.feed(body)
                        }
                    } catch (_: Exception) { /* сокет закрыт при stop */ }
                }

                val chunk = ShortArray(320) // 40 мс @ 8кГц
                try {
                    while (running && isActive) {
                        val n = record.read(chunk, 0, chunk.size)
                        if (n > 0) {
                            val alaw = G711.pcm16ToAlaw(chunk, n)
                            runCatching { c.talkSend(alaw) }
                        }
                    }
                } finally {
                    rxJob.cancel()
                    runCatching { audioOut.stop() }
                }
            } catch (e: Exception) {
                if (running) onStatus("Рация: ${e.message}")
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
                runCatching { client?.talkStop() }
                runCatching { client?.close() }
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false // цикл сам выйдет и корректно закроет talk/сокет (cleanup в finally)
        onStatus("Рация выключена")
    }

    /** Жёстко оборвать (при уходе с экрана). */
    fun dispose() {
        running = false
        runCatching { client?.close() }
        scope?.cancel()
    }
}
