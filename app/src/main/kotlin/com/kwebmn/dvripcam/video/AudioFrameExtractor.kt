package com.kwebmn.dvripcam.video

import java.io.ByteArrayOutputStream

/**
 * Лёгкий извлекатель АУДИО из потока DVRIP/Sofia — работает НЕЗАВИСИМО от видео.
 * Сканирует поток на аудиокадры `00 00 01 FA` (заголовок 8 байт: magic(4) format(1)
 * sampleRate(1) length(2 LE)) и отдаёт G.711-payload в [onAudio]. Видео при этом
 * параллельно режется отдельным [NalExtractor] по старт-кодам — так видео не зависит
 * от разбора аудио и не «замирает» при рассинхроне.
 */
class AudioFrameExtractor(private val onAudio: (payload: ByteArray, format: Int) -> Unit) {
    private val buf = ByteArrayOutputStream()

    fun feed(data: ByteArray) {
        buf.write(data)
        val arr = buf.toByteArray()
        var consumed = 0
        var p = 0
        while (true) {
            val m = indexOfFA(arr, p)
            if (m < 0) { consumed = maxOf(0, arr.size - 3); break }   // хвост под возможный маркер
            if (m + 8 > arr.size) { consumed = m; break }             // заголовок ещё не собрался
            val len = u16(arr, m + 6)
            if (len < 0 || len > 8192) { p = m + 3; continue }        // мусор — ресинк
            val end = m + 8 + len
            if (end > arr.size) { consumed = m; break }               // payload ещё не собрался
            val fmt = arr[m + 4].toInt() and 0xFF
            onAudio(arr.copyOfRange(m + 8, end), fmt)
            p = end; consumed = end
        }
        if (consumed > 0) {
            val rem = arr.copyOfRange(consumed, arr.size)
            buf.reset(); buf.write(rem)
        } else if (arr.size > 2_000_000) {
            buf.reset()
        }
    }

    /** Индекс `00 00 01 FA` начиная с [from]. */
    private fun indexOfFA(a: ByteArray, from: Int): Int {
        var i = maxOf(from, 0)
        while (i <= a.size - 4) {
            if (a[i].toInt() == 0 && a[i + 1].toInt() == 0 && a[i + 2].toInt() == 1 &&
                (a[i + 3].toInt() and 0xFF) == 0xFA
            ) return i
            i++
        }
        return -1
    }

    private fun u16(a: ByteArray, o: Int) =
        (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8)
}
