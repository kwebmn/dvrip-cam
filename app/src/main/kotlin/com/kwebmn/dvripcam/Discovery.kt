package com.kwebmn.dvripcam

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.coroutines.coroutineContext

/** Найденная в сети камера. */
data class FoundCamera(val host: String, val name: String, val mac: String)

/**
 * Поиск XM/Sofia-камер в локальной сети по UDP 34569.
 * Камера отвечает (и сама периодически анонсирует) JSON с секцией NetWork.NetCommon
 * (HostName / HostIP / MAC / порты). Слушаем ответы и параллельно шлём search-broadcast.
 */
object Discovery {
    private const val PORT = 34569

    // XM device-search broadcast: DVRIP-подобный заголовок + пустой запрос OPMachine.
    // MsgId 1530 (OP search). Тело — JSON с Name, как в обычном DVRIP.
    private fun searchPacket(): ByteArray {
        val body = "{ \"Name\" : \"\" }\n".toByteArray(Charsets.UTF_8)
        val head = ByteArray(20)
        head[0] = 0xFF.toByte()            // маркер
        // msgId 1530 (LE) в offset 14..15
        head[14] = (1530 and 0xFF).toByte()
        head[15] = ((1530 shr 8) and 0xFF).toByte()
        // bodyLen (LE) в offset 16..19
        head[16] = (body.size and 0xFF).toByte()
        head[17] = ((body.size shr 8) and 0xFF).toByte()
        return head + body
    }

    /**
     * Слушать [durationMs] и отдавать уникальные найденные камеры в [onFound].
     * Требует MulticastLock (берём сами). Работает лучше всего, когда камера бодрствует.
     */
    suspend fun scan(
        context: Context,
        durationMs: Long = 6000,
        onFound: (FoundCamera) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("dvripcam-discovery")?.apply { setReferenceCounted(false); acquire() }
        val seen = HashSet<String>()
        var sock: DatagramSocket? = null
        try {
            sock = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 800
                bind(java.net.InetSocketAddress(PORT))
            }
            // разослать search-broadcast
            runCatching {
                val pkt = searchPacket()
                sock.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName("255.255.255.255"), PORT))
            }
            val deadline = System.currentTimeMillis() + durationMs
            val buf = ByteArray(64 * 1024)
            while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
                val dp = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(dp)
                } catch (e: java.net.SocketTimeoutException) {
                    continue
                }
                val cam = parse(dp) ?: continue
                if (seen.add(cam.host)) onFound(cam)
            }
        } catch (e: Exception) {
            // тихо: сеть/права могут не дать биндиться — не критично
        } finally {
            runCatching { sock?.close() }
            runCatching { lock?.release() }
        }
    }

    private fun parse(dp: DatagramPacket): FoundCamera? {
        val bytes = dp.data.copyOfRange(dp.offset, dp.offset + dp.length)
        // найти начало JSON
        val start = bytes.indexOfFirst { it == '{'.code.toByte() }
        val srcIp = dp.address?.hostAddress ?: return null
        if (start < 0) {
            // не JSON — но пакет с 34569 от камеры: вернуть хотя бы IP источника
            return FoundCamera(srcIp, srcIp, "")
        }
        var end = bytes.size
        for (i in start until bytes.size) if (bytes[i].toInt() == 0) { end = i; break }
        val text = String(bytes, start, end - start, Charsets.UTF_8).trim()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return FoundCamera(srcIp, srcIp, "")
        val nc = json.optJSONObject("NetWork.NetCommon")
            ?: json.optJSONObject("NetCommon")
            ?: json
        val ipRaw = nc.optString("HostIP").ifBlank { nc.optString("IP") }
        val host = decodeHexIp(ipRaw) ?: srcIp
        val name = nc.optString("HostName").ifBlank { host }
        val mac = nc.optString("MAC").ifBlank { nc.optString("HWAddress") }
        return FoundCamera(host, name, mac)
    }

    /** XM отдаёт IP как hex-строку "0x1B01A8C0" (LE) — конвертируем в "192.168.1.27". */
    private fun decodeHexIp(v: String): String? {
        if (v.isBlank()) return null
        if (v.contains('.')) return v
        val n = runCatching { java.lang.Long.decode(v).toLong() }.getOrNull() ?: return null
        val a = (n and 0xFF); val b = (n shr 8) and 0xFF; val c = (n shr 16) and 0xFF; val d = (n shr 24) and 0xFF
        return "$a.$b.$c.$d"
    }
}
