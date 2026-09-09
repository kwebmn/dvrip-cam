package com.kwebmn.dvripcam.dvrip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/** Запись на SD-карте (из OPFileQuery). */
data class RecordingFile(val name: String, val begin: String, val end: String) {
    val isAlarm: Boolean get() = name.contains("[A]")
    val shortName: String get() = name.substringAfterLast('/')
}

/**
 * Минимальный DVRIP-клиент: соединение, логин (sofia_hash), запрос/ответ config get/set, SystemInfo,
 * живой поток (OPMonitor) и плейбэк архива (OPPlayBack).
 * Все сетевые операции — на Dispatchers.IO. Синхронный request/response по одному сокету.
 */
class DvripClient(
    private val host: String,
    private val port: Int = 34567,
) {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    private var sessionId: Int = 0
    private var sessionHex: String = "0x00000000"
    private var seq: Int = 0

    val isConnected: Boolean get() = socket?.isConnected == true && socket?.isClosed == false

    /**
     * socketFactory: если задан (например, socketFactory из Wi-Fi [android.net.Network]),
     * сокет пойдёт именно через эту сеть — иначе Android при активных мобильных данных
     * может отправить локальное соединение через соту ("No route to host").
     */
    suspend fun connect(
        timeoutMs: Int = 6000,
        socketFactory: javax.net.SocketFactory? = null,
    ) = withContext(Dispatchers.IO) {
        val factory = socketFactory ?: javax.net.SocketFactory.getDefault()
        val s = factory.createSocket() as Socket
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = 12000
        socket = s
        input = DataInputStream(s.getInputStream())
        output = s.getOutputStream()
    }

    private fun frame(msgId: Int, obj: JSONObject) {
        val json = obj.toString().toByteArray(Charsets.UTF_8)
        val body = ByteArray(json.size + 2)
        System.arraycopy(json, 0, body, 0, json.size)
        body[json.size] = 0x0A
        body[json.size + 1] = 0x00
        val header = DvripHeader.build(sessionId, seq++, msgId, body.size)
        val out = output ?: error("not connected")
        out.write(header)
        out.write(body)
        out.flush()
    }

    private fun recv(): JSONObject {
        val inp = input ?: error("not connected")
        val header = ByteArray(DvripHeader.SIZE)
        inp.readFully(header)
        val parsed = DvripHeader.parse(header)
        if (parsed.bodyLen <= 0) return JSONObject()
        val body = ByteArray(parsed.bodyLen)
        inp.readFully(body)
        var end = body.size
        for (i in body.indices) {
            if (body[i].toInt() == 0) { end = i; break }
        }
        val text = String(body, 0, end, Charsets.UTF_8).trim()
        return if (text.isEmpty()) JSONObject() else JSONObject(text)
    }

    /** Универсальный запрос config-уровня: подставляет SessionID и возвращает JSON-ответ. */
    suspend fun request(msgId: Int, obj: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        obj.put("SessionID", sessionHex)
        frame(msgId, obj)
        recv()
    }

    suspend fun login(user: String, password: String): Boolean = withContext(Dispatchers.IO) {
        val obj = JSONObject()
            .put("EncryptType", "MD5")
            .put("LoginType", "DVRIP-Web")
            .put("UserName", user)
            .put("PassWord", SofiaHash.hash(password))
        frame(MessageIds.LOGIN, obj)
        val resp = recv()
        if (resp.optInt("Ret", -1) == 100) {
            sessionHex = resp.optString("SessionID", "0x00000000")
            sessionId = runCatching { java.lang.Long.decode(sessionHex).toInt() }.getOrDefault(0)
            true
        } else {
            false
        }
    }

    suspend fun getConfig(name: String): JSONObject =
        request(MessageIds.CONFIG_GET, JSONObject().put("Name", name))

    suspend fun setConfig(name: String, value: Any): JSONObject =
        request(MessageIds.CONFIG_SET, JSONObject().put("Name", name).put(name, value))

    suspend fun systemInfo(): JSONObject {
        val resp = request(MessageIds.SYSTEM_INFO, JSONObject().put("Name", "SystemInfo"))
        return resp.optJSONObject("SystemInfo") ?: JSONObject()
    }

    /** Прочитать один сырой пакет (без разбора JSON): (msgId, тело). */
    private fun recvRaw(): Pair<Int, ByteArray> {
        val inp = input ?: error("not connected")
        val header = ByteArray(DvripHeader.SIZE)
        inp.readFully(header)
        val p = DvripHeader.parse(header)
        val body = if (p.bodyLen > 0) ByteArray(p.bodyLen).also { inp.readFully(it) } else ByteArray(0)
        return Pair(p.msgId, body)
    }

    /**
     * Запустить живой поток OPMonitor (Claim 1413 -> Start 1410) и читать медиа-пакеты,
     * отдавая payload каждого в [onPayload], пока [isRunning] == true.
     * streamType: "Main" (1080p) или "Extra" (D1).
     */
    suspend fun runMonitor(
        streamType: String,
        onPayload: (ByteArray) -> Unit,
        isRunning: () -> Boolean,
    ) = withContext(Dispatchers.IO) {
        fun param() = JSONObject()
            .put("Channel", 0).put("CombinMode", "NONE")
            .put("StreamType", streamType).put("TransMode", "TCP")
        // Claim (ждём ответ)
        request(
            MessageIds.MONITOR_CLAIM,
            JSONObject().put("Name", "OPMonitor")
                .put("OPMonitor", JSONObject().put("Action", "Claim").put("Parameter", param())),
        )
        // Start (медиа идёт следом, ответ не ждём)
        val startObj = JSONObject().put("Name", "OPMonitor").put("SessionID", sessionHex)
            .put("OPMonitor", JSONObject().put("Action", "Start").put("Parameter", param()))
        frame(MessageIds.MONITOR_START, startObj)
        socket?.soTimeout = 8000
        while (isRunning()) {
            val (_, body) = recvRaw()
            if (body.isEmpty()) continue
            if (body[0] == '{'.code.toByte()) continue // JSON-контрол, не медиа
            onPayload(body)
        }
    }

    /** Список записей на SD за интервал [begin]..[end] (формат "YYYY-MM-DD HH:MM:SS"). Лимит ~64/запрос. */
    suspend fun queryFiles(begin: String, end: String): List<RecordingFile> = withContext(Dispatchers.IO) {
        val q = JSONObject()
            .put("BeginTime", begin).put("EndTime", end).put("Channel", 0)
            .put("DriverTypeMask", "0x0000FFFF").put("Event", "*")
            .put("StreamType", "0x00000000").put("Type", "h264")
        val resp = request(MessageIds.FILE_QUERY, JSONObject().put("Name", "OPFileQuery").put("OPFileQuery", q))
        val arr = resp.optJSONArray("OPFileQuery") ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            val name = o.optString("FileName")
            if (name.isBlank()) null
            else RecordingFile(name, o.optString("BeginTime"), o.optString("EndTime"))
        }
    }

    /**
     * Плейбэк записи: Claim (1424) -> DownloadStart (1420), медиа приходит (1426) до пакета
     * нулевой длины (EOF). Payload каждого пакета -> [onPayload].
     */
    suspend fun playback(
        file: String, begin: String, end: String,
        onPayload: (ByteArray) -> Unit, isRunning: () -> Boolean,
    ) = withContext(Dispatchers.IO) {
        fun par() = JSONObject().put("FileName", file).put("PlayMode", "ByName")
            .put("StreamType", 0).put("Value", 0).put("TransMode", "TCP")
        request(
            MessageIds.PLAYBACK_CLAIM,
            JSONObject().put("Name", "OPPlayBack").put("OPPlayBack",
                JSONObject().put("Action", "Claim").put("StartTime", begin).put("EndTime", end).put("Parameter", par())),
        )
        frame(
            MessageIds.PLAYBACK_DOWNLOAD_START,
            JSONObject().put("Name", "OPPlayBack").put("SessionID", sessionHex).put("OPPlayBack",
                JSONObject().put("Action", "DownloadStart").put("StartTime", begin).put("EndTime", end).put("Parameter", par())),
        )
        socket?.soTimeout = 12000
        while (isRunning()) {
            val (_, body) = recvRaw()
            if (body.isEmpty()) break // EOF
            if (body[0] == '{'.code.toByte()) continue
            onPayload(body)
        }
    }

    /** Отправить сырой (не-JSON) кадр: заголовок + тело как есть. Для talk-аудио. */
    private fun frameRaw(msgId: Int, body: ByteArray) {
        val header = DvripHeader.build(sessionId, seq++, msgId, body.size)
        val out = output ?: error("not connected")
        out.write(header); out.write(body); out.flush()
    }

    /**
     * Начать talk-back (микрофон → камера): Claim (1434, ждём ответ) + Start (1430).
     * Использовать НА ОТДЕЛЬНОМ соединении (не на сокете живого потока!).
     * Формат: G711 A-law, 8кГц, 8 бит.
     */
    /** Последний сырой ответ на talk Claim — для диагностики. */
    @Volatile var lastTalkResp: String = ""

    /**
     * Начать talk-back. Возвращает Ret ответа Claim (100 = успех).
     * Start (1430) отправляется только при успешном Claim.
     */
    suspend fun talkStart(): Int = withContext(Dispatchers.IO) {
        fun af() = JSONObject().put("BitRate", 128).put("EncodeType", "G711_ALAW")
            .put("SampleBit", 8).put("SampleRate", 8)
        val resp = request(
            MessageIds.TALK_CLAIM,
            JSONObject().put("Name", "OPTalk")
                .put("OPTalk", JSONObject().put("Action", "Claim").put("AudioFormat", af())),
        )
        lastTalkResp = resp.toString()
        val ret = resp.optInt("Ret", -1)
        if (ret == 100 || ret == 0) {
            frame(
                MessageIds.TALK_START,
                JSONObject().put("Name", "OPTalk").put("SessionID", sessionHex)
                    .put("OPTalk", JSONObject().put("Action", "Start").put("AudioFormat", af())),
            )
        }
        ret
    }

    /** Отправить порцию G711 A-law в камеру, обёрнутую в Sofia-аудиокадр (00 00 01 FA). */
    suspend fun talkSend(g711: ByteArray) = withContext(Dispatchers.IO) {
        val len = g711.size
        val body = ByteArray(8 + len)
        body[0] = 0; body[1] = 0; body[2] = 1; body[3] = 0xFA.toByte()
        body[4] = 0x0E            // тип: G711 A-law
        body[5] = 0x02            // 8 кГц
        body[6] = (len and 0xFF).toByte()
        body[7] = ((len shr 8) and 0xFF).toByte()
        System.arraycopy(g711, 0, body, 8, len)
        frameRaw(MessageIds.TALK_DATA, body)
    }

    /** Завершить talk-back. */
    suspend fun talkStop() = withContext(Dispatchers.IO) {
        runCatching {
            frame(
                MessageIds.TALK_START,
                JSONObject().put("Name", "OPTalk").put("SessionID", sessionHex)
                    .put("OPTalk", JSONObject().put("Action", "Stop")),
            )
        }
        Unit
    }

    fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }
}
