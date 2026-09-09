package com.kwebmn.dvripcam.dvrip

import java.security.MessageDigest

/**
 * Хэш пароля Xiongmai/Sofia ("sofia_hash").
 * md5(password) -> берём пары байт (0+1, 2+3, ...) -> сумма % 62 -> индекс в 62-символьном алфавите -> 8 символов.
 */
object SofiaHash {
    private const val ALPHABET =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    fun hash(password: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(8)
        for (i in 0 until 8) {
            val a = md5[i * 2].toInt() and 0xFF
            val b = md5[i * 2 + 1].toInt() and 0xFF
            sb.append(ALPHABET[(a + b) % 62])
        }
        return sb.toString()
    }
}
