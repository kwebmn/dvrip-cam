package com.kwebmn.dvripcam

import com.kwebmn.dvripcam.dvrip.DvripClient
import kotlinx.coroutines.delay
import javax.net.SocketFactory

/**
 * Соединиться и залогиниться, переспрашивая до [timeoutMs] (батарейная камера может спать —
 * её надо разбудить движением/PIR). Возвращает true при успешном логине.
 */
internal suspend fun DvripClient.connectAwait(
    user: String,
    pass: String,
    wifiSf: SocketFactory?,
    timeoutMs: Long = 60_000,
    onStatus: (String) -> Unit,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        try {
            connect(3000, wifiSf)
            return if (login(user, pass)) true else { onStatus("Ошибка логина"); false }
        } catch (e: Exception) {
            onStatus("Жду пробуждения камеры… помаши рукой")
            runCatching { close() }
            delay(1500)
        }
    }
    onStatus("Камера не ответила (спит?)")
    return false
}
