package com.kwebmn.dvripcam.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.kwebmn.dvripcam.CameraEntry
import com.kwebmn.dvripcam.CameraStore
import com.kwebmn.dvripcam.MainActivity
import com.kwebmn.dvripcam.dvrip.DvripClient
import com.kwebmn.dvripcam.dvrip.MessageIds
import com.kwebmn.dvripcam.wifiSocketFactory
import kotlinx.coroutines.*
import org.json.JSONObject

/** Настройки сторожа тревог. */
object AlarmPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("alarm", Context.MODE_PRIVATE)
    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", false)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("enabled", v).apply()
    /** host:port камеры, за которой следим (пусто = последняя сохранённая). */
    fun target(ctx: Context): String = p(ctx).getString("target", "") ?: ""
    fun setTarget(ctx: Context, v: String) = p(ctx).edit().putString("target", v).apply()
}

/**
 * Фоновый сторож тревог: держит соединение с камерой, подписывается через guard (1500)
 * и показывает уведомление на каждое событие AlarmInfo (1504).
 *
 * Ограничение железа: батарейную камеру нельзя разбудить по сети — события приходят,
 * только пока камера бодрствует (её будит PIR/движение или удержание потока).
 */
class AlarmService : Service() {

    private var scope: CoroutineScope? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        val cam = pickCamera() ?: run { stopSelf(); return START_NOT_STICKY }
        running = true
        val notif = serviceNotification("Слежу за ${cam.name.ifBlank { cam.host }}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_SERVICE, notif, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_SERVICE, notif)
        }
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob()); scope = s
        s.launch { watchLoop(cam) }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        scope?.cancel()
        super.onDestroy()
    }

    private fun pickCamera(): CameraEntry? {
        val list = CameraStore(this).list()
        if (list.isEmpty()) return null
        val target = AlarmPrefs.target(this)
        return list.firstOrNull { "${it.host}:${it.port}" == target } ?: list.last()
    }

    private suspend fun watchLoop(cam: CameraEntry) {
        var backoff = 2000L
        while (running) {
            val c = DvripClient(cam.host, cam.port)
            try {
                c.connect(4000, wifiSocketFactory(this@AlarmService))
                if (!c.login(cam.user, cam.pass)) throw IllegalStateException("login")
                if (!c.guard()) throw IllegalStateException("guard")
                backoff = 2000L
                updateService("Слежу за ${cam.name.ifBlank { cam.host }}")
                while (running) {
                    // Тишина в сокете — это норма: шлём KeepAlive, а не рвём соединение.
                    val pkt = try {
                        c.readRawPacket()
                    } catch (t: java.net.SocketTimeoutException) {
                        c.keepAlive(); continue
                    }
                    val (mid, body) = pkt
                    if (body.isEmpty()) continue
                    if (body[0] != '{'.code.toByte()) continue
                    if (mid != MessageIds.ALARM_INFO) continue
                    var end = body.size
                    for (i in body.indices) if (body[i].toInt() == 0) { end = i; break }
                    val text = String(body, 0, end, Charsets.UTF_8).trim()
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                    handleAlarm(json, cam)
                }
            } catch (e: Exception) {
                if (running) {
                    updateService("Нет связи, переподключаюсь…")
                    delay(backoff); backoff = (backoff * 2).coerceAtMost(30_000)
                }
            } finally {
                runCatching { c.close() }
            }
        }
    }

    /** AlarmInfo: {"AlarmInfo":{"Channel":0,"Event":"MotionDetect","StartTime":"..","Status":"Start"}} */
    private fun handleAlarm(json: JSONObject, cam: CameraEntry) {
        val info = json.optJSONObject("AlarmInfo") ?: json
        val status = info.optString("Status", "Start")
        if (status.equals("Stop", ignoreCase = true)) return   // уведомляем только о начале
        val event = info.optString("Event").ifBlank { "Alarm" }
        val time = info.optString("StartTime").ifBlank {
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        }
        notifyAlarm(eventTitle(event), "${cam.name.ifBlank { cam.host }} · ${time.takeLast(8)}")
    }

    private fun eventTitle(event: String): String = when {
        event.contains("Motion", true) -> "Движение"
        event.contains("Human", true) -> "Человек в кадре"
        event.contains("PIR", true) -> "PIR-датчик"
        event.contains("Blind", true) -> "Камера перекрыта"
        event.contains("Video", true) -> "Проблема с видео"
        else -> event
    }

    // ---- уведомления ----

    private fun nm() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val svc = NotificationChannel(CH_SERVICE, "Сторож камеры", NotificationManager.IMPORTANCE_MIN)
            .apply { description = "Постоянное уведомление, пока работает слежение" }
        val alarm = NotificationChannel(CH_ALARM, "Тревоги", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Движение и другие события камеры"; enableVibration(true) }
        nm().createNotificationChannel(svc)
        nm().createNotificationChannel(alarm)
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, i, flags)
    }

    private fun serviceNotification(text: String): Notification =
        Notification.Builder(this, CH_SERVICE)
            .setContentTitle("Сторож камеры")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .build()

    private fun updateService(text: String) {
        runCatching { nm().notify(NOTIF_SERVICE, serviceNotification(text)) }
    }

    private fun notifyAlarm(title: String, text: String) {
        val n = Notification.Builder(this, CH_ALARM)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_video_busy)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        runCatching { nm().notify(NOTIF_ALARM_BASE + (System.currentTimeMillis() % 1000).toInt(), n) }
    }

    companion object {
        private const val CH_SERVICE = "watch"
        private const val CH_ALARM = "motion"
        private const val NOTIF_SERVICE = 1
        private const val NOTIF_ALARM_BASE = 1000

        fun start(ctx: Context) {
            val i = Intent(ctx, AlarmService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AlarmService::class.java))
        }
    }
}
