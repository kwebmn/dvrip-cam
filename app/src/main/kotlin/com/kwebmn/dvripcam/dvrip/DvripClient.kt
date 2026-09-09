package com.kwebmn.dvripcam.dvrip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Минимальный DVRIP-клиент: соединение, логин (sofia_hash), запрос/ответ config get/set, SystemInfo.
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

    fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }
}
