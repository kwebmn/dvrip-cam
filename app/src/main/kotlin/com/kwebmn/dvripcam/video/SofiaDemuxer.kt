package com.kwebmn.dvripcam.video

import java.io.ByteArrayOutputStream

/**
 * Демультиплексор медиа-потока Xiongmai/Sofia (DHAV).
 *
 * Поток состоит из кадров, каждый начинается с маркера `00 00 01 XX`:
 *  - `FC` / `FD` — видеокадр (I / P). Заголовок 16 байт:
 *      magic(4) frameType(1) fps(1) width(1) height(1) timestamp(4 LE) length(4 LE),
 *      далее length байт полезной нагрузки = Annex-B H.264 (со старт-кодами 00 00 00 01).
 *  - `FA` — аудиокадр. Заголовок 8 байт: magic(4) format(1) sampleRate(1) length(2 LE),
 *      далее length байт (G.711).
 *  - `F9` и прочее — служебные, пропускаем (заголовок 8 байт).
 *
 * Устойчив к тому, что кадры приходят разрезанными по DVRIP-пакетам: копит буфер и
 * отдаёт только целиком собранные кадры. Если в начале буфера не распознан валидный
 * маркер, но лежит сырой H.264 (00 00 00 01) — отдаёт как видео (совместимость).
 */
class SofiaDemuxer(
    private val onVideo: (ByteArray) -> Unit,
    private val onAudio: (payload: ByteArray, format: Int) -> Unit = { _, _ -> },
) {
    private val buf = ByteArrayOutputStream()

    fun feed(data: ByteArray) {
        buf.write(data)
        val arr = buf.toByteArray()
        val consumed = parse(arr)
        if (consumed > 0) {
            buf.reset()
            if (consumed < arr.size) buf.write(arr, consumed, arr.size - consumed)
        } else if (arr.size > 4_000_000) {
            buf.reset() // защита от разрастания при рассинхроне
        }
    }

    /** Возвращает число полностью обработанных байт от начала [arr]. */
    private fun parse(arr: ByteArray): Int {
        var p = 0
        while (true) {
            // выровняться на следующий маркер 00 00 01
            val m = indexOfMarker(arr, p)
            if (m < 0) return p
            if (m + 4 > arr.size) return p // маркер есть, а типа кадра ещё нет
            val type = arr[m + 3].toInt() and 0xFF
            when (type) {
                0xFC, 0xFD -> {
                    if (m + 16 > arr.size) return p
                    val len = u32(arr, m + 12)
                    if (len < 0 || len > 4_000_000) { p = m + 3; continue } // мусор — ресинк
                    val end = m + 16 + len
                    if (end > arr.size) return p // кадр ещё не дособрался
                    onVideo(arr.copyOfRange(m + 16, end))
                    p = end
                }
                0xFA -> {
                    if (m + 8 > arr.size) return p
                    val len = u16(arr, m + 6)
                    val fmt = arr[m + 4].toInt() and 0xFF
                    val end = m + 8 + len
                    if (end > arr.size) return p
                    onAudio(arr.copyOfRange(m + 8, end), fmt)
                    p = end
                }
                0xF9 -> {
                    if (m + 8 > arr.size) return p
                    val len = u16(arr, m + 6)
                    val end = m + 8 + len
                    if (end > arr.size) return p
                    p = end
                }
                else -> { p = m + 3 } // неизвестный тип — сдвинуться и искать дальше (ресинк)
            }
        }
    }

    /** Индекс следующего 00 00 01 начиная с [from] (>=0), иначе -1. */
    private fun indexOfMarker(a: ByteArray, from: Int): Int {
        var i = maxOf(from, 0)
        while (i <= a.size - 3) {
            if (a[i].toInt() == 0 && a[i + 1].toInt() == 0 && a[i + 2].toInt() == 1) return i
            i++
        }
        return -1
    }

    private fun u16(a: ByteArray, o: Int): Int =
        (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(a: ByteArray, o: Int): Int =
        (a[o].toInt() and 0xFF) or ((a[o + 1].toInt() and 0xFF) shl 8) or
            ((a[o + 2].toInt() and 0xFF) shl 16) or ((a[o + 3].toInt() and 0xFF) shl 24)
}
