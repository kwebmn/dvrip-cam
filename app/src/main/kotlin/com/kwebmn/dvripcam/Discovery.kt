package com.kwebmn.dvripcam

import android.content.Context
import android.net.wifi.WifiManager
import com.kwebmn.dvripcam.dvrip.DvripHeader
import com.kwebmn.dvripcam.dvrip.MessageIds
import com.kwebmn.dvripcam.dvrip.SofiaHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
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
        val srcIp = dp.address?.hostAddress ?: return null
        // Только настоящий анонс камеры (JSON с NetCommon). Любой прочий UDP-пакет
        // на 34569 (напр. от телефона) — НЕ камера, игнорируем.
        val start = bytes.indexOfFirst { it == '{'.code.toByte() }
        if (start < 0) return null
        var end = bytes.size
        for (i in start until bytes.size) if (bytes[i].toInt() == 0) { end = i; break }
        val text = String(bytes, start, end - start, Charsets.UTF_8).trim()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val nc = json.optJSONObject("NetWork.NetCommon")
            ?: json.optJSONObject("NetCommon")
            ?: json
        val hostName = nc.optString("HostName")
        val mac = nc.optString("MAC").ifBlank { nc.optString("HWAddress") }
        val ipRaw = nc.optString("HostIP").ifBlank { nc.optString("IP") }
        // Признаки камеры Xiongmai/DVRIP — иначе это не камера.
        val looksLikeCamera = hostName.isNotBlank() || mac.isNotBlank() ||
            nc.has("TCPPort") || nc.has("HttpPort") || nc.has("GateWay") || nc.has("TCPMaxConn")
        if (!looksLikeCamera) return null
        val host = decodeHexIp(ipRaw) ?: srcIp
        val name = hostName.ifBlank { host }
        return FoundCamera(host, name, mac)
    }

    /**
     * Надёжный поиск: активно сканирует локальную /24 подсеть на TCP-порт 34567 и
     * проверяет, что устройство отвечает как DVRIP-камера (шлём login, ждём JSON c "Ret").
     * Так находим именно камеру, а не любой девайс. Работает даже если UDP-анонса нет.
     */
    suspend fun scanSubnet(
        socketFactory: javax.net.SocketFactory?,
        onFound: (FoundCamera) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val base = localSubnetBase() ?: return@withContext
        val factory = socketFactory ?: javax.net.SocketFactory.getDefault()
        coroutineScope {
            (1..254).map { i ->
                async {
                    val ip = "$base.$i"
                    if (probeDvrip(factory, ip)) FoundCamera(ip, ip, "") else null
                }
            }.awaitAll().filterNotNull().forEach { onFound(it) }
        }
    }

    /** Проверка одного адреса: открыт ли 34567 и отвечает ли как DVRIP. */
    private fun probeDvrip(factory: javax.net.SocketFactory, ip: String): Boolean {
        var s: Socket? = null
        return try {
            s = factory.createSocket() as Socket
            s.connect(InetSocketAddress(ip, 34567), 600)
            s.soTimeout = 1200
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream())
            val body = JSONObject()
                .put("EncryptType", "MD5").put("LoginType", "DVRIP-Web")
                .put("UserName", "admin").put("PassWord", SofiaHash.hash("x"))
                .toString().toByteArray(Charsets.UTF_8) + byteArrayOf(0x0A, 0x00)
            out.write(DvripHeader.build(0, 0, MessageIds.LOGIN, body.size)); out.write(body); out.flush()
            val hdr = ByteArray(DvripHeader.SIZE); inp.readFully(hdr)
            val p = DvripHeader.parse(hdr)
            if (p.bodyLen in 1..65535) {
                val b = ByteArray(p.bodyLen); inp.readFully(b)
                String(b, Charsets.UTF_8).contains("\"Ret\"")   // валидный DVRIP-ответ
            } else false
        } catch (e: Exception) {
            false
        } finally {
            runCatching { s?.close() }
        }
    }

    /** База локальной /24 (напр. "192.168.88") из адреса Wi-Fi/site-local интерфейса. */
    private fun localSubnetBase(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filter { !it.isLoopbackAddress && it.isSiteLocalAddress && it.address.size == 4 }
                .mapNotNull { it.hostAddress }
                .firstOrNull()
                ?.substringBeforeLast('.')
        } catch (e: Exception) {
            null
        }
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
