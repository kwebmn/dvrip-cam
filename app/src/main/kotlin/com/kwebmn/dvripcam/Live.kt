package com.kwebmn.dvripcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import com.kwebmn.dvripcam.dvrip.DvripClient
import com.kwebmn.dvripcam.video.AudioOut
import com.kwebmn.dvripcam.video.G711
import com.kwebmn.dvripcam.video.H264Decoder
import com.kwebmn.dvripcam.video.NalExtractor
import com.kwebmn.dvripcam.video.SofiaDemuxer
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
    private val audio = AudioOut()
    @Volatile private var soundOn = false

    /** Включить/выключить звук (звук из потока камеры). */
    fun setSound(on: Boolean) {
        soundOn = on
        if (on) audio.start() else audio.stop()
    }

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
                val demux = SofiaDemuxer(
                    onVideo = { extractor.feed(it) },
                    onAudio = { payload, fmt -> if (soundOn) audio.write(G711.toPcm16(payload, fmt)) },
                )
                c.runMonitor(streamType, onPayload = { demux.feed(it) }, isRunning = { running })
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
        runCatching { audio.stop() }
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

    // "Extra" = D1 (лёгкий поток), "Main" = 1080p. Смена качества пересоздаёт плеер.
    var stream by rememberSaveable { mutableStateOf("Extra") }
    var sound by rememberSaveable { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(4f / 3f) }
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }
    val player = remember(stream) {
        LivePlayer(
            host, port, user, pass, wifiSf,
            onStatus = { status = it },
            onVideoSize = { w, h -> if (h > 0) aspect = w.toFloat() / h },
        ).also { it.streamType = stream }
    }
    // применяем текущее состояние звука к (пере)созданному плееру
    LaunchedEffect(player, sound) { player.setSound(sound) }
    DisposableEffect(player) { onDispose { player.stop() } }

    // --- talk-back (рация): отдельное соединение, push-to-talk ---
    val talk = remember { TalkSession(host, port, user, pass, wifiSf, onStatus = { status = it }) }
    DisposableEffect(talk) { onDispose { talk.dispose() } }
    var hasMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMic = granted
        if (!granted) status = "Нужно разрешение на микрофон для рации"
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text(if (stream == "Main") "Live (1080p)" else "Live (D1)",
                style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { takeSnapshot(ctx, surfaceView) { status = it } }) { Text("📷") }
            FilterChip(
                selected = sound,
                onClick = { sound = !sound },
                label = { Text(if (sound) "🔊" else "🔈") },
            )
            FilterChip(
                selected = stream == "Main",
                onClick = { stream = if (stream == "Main") "Extra" else "Main" },
                label = { Text(if (stream == "Main") "HD" else "SD") },
            )
        }

        Box(
            modifier = Modifier.fillMaxWidth().weight(1f)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offX += pan.x; offY += pan.y
                        } else { offX = 0f; offY = 0f }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { scale = 1f; offX = 0f; offY = 0f })
                },
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect).graphicsLayer(
                    scaleX = scale, scaleY = scale, translationX = offX, translationY = offY,
                ),
                factory = { c ->
                    SurfaceView(c).also { surfaceView = it }.apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) { player.start(h.surface) }
                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                            override fun surfaceDestroyed(h: SurfaceHolder) { player.stop() }
                        })
                    }
                },
            )
            if (scale > 1f) {
                Text("×%.1f".format(scale), modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(8.dp),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            }
        }
        // Рация (push-to-talk). Не Button: у него собственный clickable, который
        // перехватывает жест и не даёт сработать detectTapGestures/запросу разрешения.
        var talking by remember { mutableStateOf(false) }
        Surface(
            modifier = Modifier.fillMaxWidth().pointerInput(hasMic) {
                detectTapGestures(
                    onPress = {
                        if (!hasMic) {
                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@detectTapGestures
                        }
                        talking = true
                        talk.start()
                        tryAwaitRelease()
                        talk.stop()
                        talking = false
                    },
                )
            },
            color = if (talking) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
        ) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(
                    if (talking) "🔴 Говорите… (отпустите, чтобы закончить)"
                    else if (hasMic) "🎙 Удерживать — говорить в камеру (рация)"
                    else "🎙 Нажмите — разрешить микрофон для рации",
                    color = if (talking) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Text(status, style = MaterialTheme.typography.bodySmall)
        Text("Если чёрный экран — помаши рукой перед камерой (она спит). Рация экспериментальная.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun LocalContextX(): Context = androidx.compose.ui.platform.LocalContext.current

/** Снимок текущего кадра из SurfaceView через PixelCopy → JPEG в папку Pictures приложения. */
private fun takeSnapshot(ctx: Context, sv: SurfaceView?, onResult: (String) -> Unit) {
    if (sv == null || sv.width == 0 || sv.height == 0) { onResult("Нет кадра для снимка"); return }
    val bmp = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(sv, bmp, { result ->
            if (result == PixelCopy.SUCCESS) {
                try {
                    val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: ctx.cacheDir
                    val out = File(dir, "snap_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    onResult("📷 Снимок: ${out.name}")
                    Toast.makeText(ctx, "Снимок сохранён: ${out.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    onResult("Ошибка снимка: ${e.message}")
                }
            } else {
                onResult("PixelCopy: код $result")
            }
        }, Handler(Looper.getMainLooper()))
    } catch (e: Exception) {
        onResult("Снимок недоступен: ${e.message}")
    }
}
