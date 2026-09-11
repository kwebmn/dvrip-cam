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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
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
import com.kwebmn.dvripcam.video.AudioFrameExtractor
import com.kwebmn.dvripcam.video.AudioOut
import com.kwebmn.dvripcam.video.G711
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
                // Видео — прямая нарезка по старт-кодам (надёжно, не замирает).
                val video = NalExtractor { nal -> dec.submitNal(nal) }
                // Звук — независимый сканер аудиокадров (не влияет на видео).
                val audioEx = AudioFrameExtractor { payload, fmt ->
                    if (soundOn) audio.write(G711.toPcm16(payload, fmt))
                }
                c.runMonitor(
                    streamType,
                    onPayload = { p -> video.feed(p); audioEx.feed(p) },
                    isRunning = { running },
                )
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
    val wifiSf = remember { wifiSocketFactory(ctx) }

    var stream by rememberSaveable { mutableStateOf("Main") } // "Main"=1080p HD, "Extra"=D1 SD
    var sound by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("Подключаюсь…") }
    var videoStarted by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(16f / 9f) }
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }
    var controls by remember { mutableStateOf(true) }
    var talking by remember { mutableStateOf(false) }

    val player = remember(stream) {
        LivePlayer(
            host, port, user, pass, wifiSf,
            onStatus = { status = it },
            onVideoSize = { w, h -> if (h > 0) aspect = w.toFloat() / h; videoStarted = true },
        ).also { it.streamType = stream }
    }
    LaunchedEffect(player) { videoStarted = false }
    LaunchedEffect(player, sound) { player.setSound(sound) }
    DisposableEffect(player) { onDispose { player.stop() } }

    // рация (push-to-talk) на отдельном соединении
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
    ) { granted -> hasMic = granted }

    // автоскрытие кнопок через ~3.5с (пока видео идёт и не говорим)
    LaunchedEffect(controls, videoStarted, talking) {
        if (controls && videoStarted && !talking) { delay(3500); controls = false }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // --- видео на весь экран ---
        Box(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) { offX += pan.x; offY += pan.y } else { offX = 0f; offY = 0f }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controls = !controls },
                        onDoubleTap = { scale = 1f; offX = 0f; offY = 0f },
                    )
                },
            contentAlignment = Alignment.Center,
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
        }

        // --- индикатор подключения (пока не пошло видео) ---
        if (!videoStarted) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(color = Color.White)
                Text(status, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // --- верхняя панель кнопок (автоскрытие) ---
        AnimatedVisibility(visible = controls, modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(onClick = onBack) { Text("←", color = Color.White) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { takeSnapshot(ctx, surfaceView) { status = it } }) { Text("📷") }
                TextButton(onClick = { sound = !sound }) { Text(if (sound) "🔊" else "🔈") }
                TextButton(onClick = { stream = if (stream == "Main") "Extra" else "Main" }) {
                    Text(if (stream == "Main") "HD" else "SD", color = Color.White)
                }
            }
        }

        // --- рация внизу (часть автоскрываемых кнопок) ---
        AnimatedVisibility(visible = controls, modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(
                modifier = Modifier.fillMaxWidth().pointerInput(hasMic) {
                    detectTapGestures(onPress = {
                        if (!hasMic) {
                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@detectTapGestures
                        }
                        talking = true; talk.start(); tryAwaitRelease(); talk.stop(); talking = false
                    })
                },
                color = if (talking) MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                else Color.Black.copy(alpha = 0.35f),
            ) {
                Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (talking) "🔴 Говорите…"
                        else if (hasMic) "🎙 Удерживать — рация"
                        else "🎙 Нажмите — разрешить микрофон",
                        color = Color.White,
                    )
                }
            }
        }

        if (scale > 1f) {
            Text(
                "×%.1f".format(scale),
                Modifier.align(Alignment.TopEnd).padding(top = 44.dp, end = 8.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall,
            )
        }
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
