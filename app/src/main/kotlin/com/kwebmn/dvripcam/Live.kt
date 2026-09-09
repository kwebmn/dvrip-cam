package com.kwebmn.dvripcam

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Surface
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kwebmn.dvripcam.dvrip.DvripClient
import com.kwebmn.dvripcam.video.H264Decoder
import com.kwebmn.dvripcam.video.NalExtractor
import kotlinx.coroutines.*
import javax.net.SocketFactory

/** Управляет живым потоком: соединение (с ожиданием пробуждения) -> OPMonitor -> демукс -> декод на Surface. */
class LivePlayer(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val pass: String,
    private val wifiSf: SocketFactory?,
    private val onStatus: (String) -> Unit,
    private val onVideoSize: (Int, Int) -> Unit = { _, _ -> },
) {
    @Volatile var streamType: String = "Extra"
    @Volatile private var running = false
    private var scope: CoroutineScope? = null
    private var client: DvripClient? = null
    private var decoder: H264Decoder? = null

    fun start(surface: Surface) {
        if (running) return
        running = true
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob()); scope = s
        s.launch {
            try {
                onStatus("Подключаюсь…")
                val c = DvripClient(host, port); client = c
                val deadline = System.currentTimeMillis() + 60_000
                var ok = false
                while (running && !ok && System.currentTimeMillis() < deadline) {
                    try {
                        c.connect(3000, wifiSf); ok = c.login(user, pass)
                        if (!ok) { onStatus("Ошибка логина"); return@launch }
                    } catch (e: Exception) {
                        onStatus("Жду пробуждения камеры… помаши рукой")
                        runCatching { c.close() }
                        delay(1500)
                    }
                }
                if (!ok) { onStatus("Камера не ответила (спит?)"); return@launch }

                onStatus("Live • $streamType")
                val dec = H264Decoder(surface, onError = { onStatus(it) }, onVideoSize = onVideoSize); decoder = dec
                val extractor = NalExtractor { nal -> dec.submitNal(nal) }
                c.runMonitor(streamType, onPayload = { extractor.feed(it) }, isRunning = { running })
            } catch (e: Exception) {
                if (running) onStatus("Поток прерван: ${e.message}")
            } finally {
                runCatching { decoder?.stop() }
                runCatching { client?.close() }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { decoder?.stop() }
        runCatching { client?.close() }
        scope?.cancel()
    }
}

@Composable
fun LiveScreen(host: String, port: Int, user: String, pass: String, onBack: () -> Unit) {
    val ctx = LocalContextX()
    var status by remember { mutableStateOf("Готовлюсь…") }
    val wifiSf = remember { wifiSocketFactory(ctx) }

    var aspect by remember { mutableStateOf(4f / 3f) }
    val player = remember {
        LivePlayer(
            host, port, user, pass, wifiSf,
            onStatus = { status = it },
            onVideoSize = { w, h -> if (h > 0) aspect = w.toFloat() / h },
        ).also { it.streamType = "Extra" }
    }
    DisposableEffect(player) { onDispose { player.stop() } }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Live (D1)", style = MaterialTheme.typography.titleLarge)
        }

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
                factory = { c ->
                    SurfaceView(c).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) { player.start(h.surface) }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) { player.stop() }
                        })
                    }
                },
            )
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        Text("Если чёрный экран — помаши рукой перед камерой (она спит).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun LocalContextX(): Context = androidx.compose.ui.platform.LocalContext.current
