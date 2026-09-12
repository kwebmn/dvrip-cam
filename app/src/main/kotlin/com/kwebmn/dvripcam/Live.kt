package com.kwebmn.dvripcam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream
import com.kwebmn.dvripcam.dvrip.DvripClient
import com.kwebmn.dvripcam.video.AudioFrameExtractor
import com.kwebmn.dvripcam.video.AudioOut
import com.kwebmn.dvripcam.video.G711
import com.kwebmn.dvripcam.video.H264Decoder
import com.kwebmn.dvripcam.video.LatencyMode
import com.kwebmn.dvripcam.video.Mp4Saver
import com.kwebmn.dvripcam.video.NalExtractor
import com.kwebmn.dvripcam.video.VideoStats
import kotlinx.coroutines.*
import javax.net.SocketFactory

/**
 * Живой поток: одно соединение с авто-переподключением, горячее переключение HD/SD без реконнекта,
 * low-latency декод на Surface, live-запись в MP4, статистика для HUD.
 */
class LivePlayer(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val pass: String,
    private val wifiSf: SocketFactory?,
    private val onStatus: (String) -> Unit,
    private val onVideoSize: (Int, Int) -> Unit = { _, _ -> },
    private val onStats: (VideoStats) -> Unit = {},
) {
    @Volatile var streamType: String = "Extra"
    @Volatile private var mode: LatencyMode = LatencyMode.ADAPTIVE
    @Volatile private var running = false
    private var scope: CoroutineScope? = null
    @Volatile private var client: DvripClient? = null
    @Volatile private var decoder: H264Decoder? = null
    private val audio = AudioOut()
    @Volatile private var soundOn = false
    @Volatile private var recorder: Mp4Saver? = null

    val isRecording: Boolean get() = recorder != null

    fun setSound(on: Boolean) {
        soundOn = on
        if (on) audio.start() else audio.stop()
    }

    fun setMode(m: LatencyMode) { mode = m; decoder?.mode = m }

    /** Горячее переключение потока: без реконнекта, декодер переконфигурится на новом SPS. */
    fun requestStream(t: String) {
        if (t == streamType) return
        val old = streamType; streamType = t
        val c = client
        scope?.launch(Dispatchers.IO) {
            runCatching { c?.switchStream(old, t) }
            decoder?.requestKeyframe()
            onStatus("Live • ${if (t == "Main") "HD" else "SD"}")
        }
    }

    fun startRecord(file: File): Boolean {
        if (recorder != null) return false
        recorder = Mp4Saver(file, fps = if (streamType == "Main") 25 else 20)
        return true
    }

    fun stopRecord(): Boolean {
        val r = recorder; recorder = null
        return r?.finish() ?: false
    }

    fun start(surface: Surface) {
        if (running) return
        running = true
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob()); scope = s
        val dec = H264Decoder(surface, onError = { onStatus(it) }, onVideoSize = onVideoSize)
            .also { it.mode = mode }
        decoder = dec
        val video = NalExtractor { nal -> dec.submitNal(nal); recorder?.onNal(nal) }
        val audioEx = AudioFrameExtractor { payload, fmt -> if (soundOn) audio.write(G711.toPcm16(payload, fmt)) }

        // тикер статистики для HUD
        s.launch { while (running) { delay(1000); runCatching { onStats(dec.snapshotStats()) } } }

        // сессия с авто-переподключением
        s.launch {
            var backoff = 1000L
            while (running) {
                val c = DvripClient(host, port); client = c
                try {
                    onStatus("Подключаюсь…")
                    val deadline = System.currentTimeMillis() + 60_000
                    var ok = false
                    while (running && !ok && System.currentTimeMillis() < deadline) {
                        try {
                            c.connect(3000, wifiSf); ok = c.login(user, pass)
                            if (!ok) { onStatus("Ошибка логина"); break }
                        } catch (e: Exception) {
                            onStatus("Жду пробуждения камеры… помаши рукой")
                            runCatching { c.close() }; delay(1500)
                        }
                    }
                    if (!ok) {
                        if (running) { onStatus("Переподключаюсь…"); delay(backoff); backoff = (backoff * 2).coerceAtMost(8000) }
                        continue
                    }
                    backoff = 1000
                    onStatus("Live • ${if (streamType == "Main") "HD" else "SD"}")
                    dec.requestKeyframe()
                    c.runMonitor(streamType, onPayload = { p -> video.feed(p); audioEx.feed(p) }, isRunning = { running })
                } catch (e: Exception) {
                    if (running) onStatus("Переподключаюсь…")
                } finally {
                    runCatching { c.close() }
                }
                if (running) { delay(backoff); backoff = (backoff * 2).coerceAtMost(8000); dec.requestKeyframe() }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { recorder?.finish() }; recorder = null
        runCatching { audio.stop() }
        runCatching { decoder?.stop() }
        runCatching { client?.close() }
        scope?.cancel()
    }
}

@Composable
fun LiveScreen(host: String, port: Int, user: String, pass: String, onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val wifiSf = remember { wifiSocketFactory(ctx) }
    val view = LocalView.current

    var stream by remember { mutableStateOf(ViewerPrefs.stream(ctx)) }   // "Main"=HD / "Extra"=SD
    var latency by remember { mutableStateOf(ViewerPrefs.latency(ctx)) }
    var hud by remember { mutableStateOf(ViewerPrefs.hud(ctx)) }
    var sound by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Подключаюсь…") }
    var videoStarted by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(16f / 9f) }
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }
    var controls by remember { mutableStateOf(true) }
    var talking by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf(VideoStats(0, 0, 0, 0)) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // keep-screen-on + immersive на время просмотра
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ctrl = window?.let { WindowInsetsControllerCompat(it, view) }
        ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val player = remember(host, port, user, pass) {
        LivePlayer(
            host, port, user, pass, wifiSf,
            onStatus = { status = it },
            onVideoSize = { w, h -> if (h > 0) aspect = w.toFloat() / h; videoStarted = true },
            onStats = { stats = it },
        ).also { it.streamType = stream; it.setMode(latency) }
    }
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

    // автоскрытие кнопок ~3.5с (пока видео идёт и не говорим)
    LaunchedEffect(controls, videoStarted, talking) {
        if (controls && videoStarted && !talking) { delay(3500); controls = false }
    }

    fun toggleRecord() {
        if (player.isRecording) {
            val ok = player.stopRecord(); recording = false
            status = if (ok) "⏹ Запись сохранена" else "Запись не удалась"
            Toast.makeText(ctx, status, Toast.LENGTH_SHORT).show()
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.cacheDir
            val f = File(dir, "live_${System.currentTimeMillis()}.mp4")
            recording = player.startRecord(f)
            status = if (recording) "⏺ Запись…" else "Не удалось начать запись"
        }
    }

    fun cycleLatency() {
        latency = when (latency) {
            LatencyMode.LOW -> LatencyMode.SMOOTH
            LatencyMode.SMOOTH -> LatencyMode.ADAPTIVE
            LatencyMode.ADAPTIVE -> LatencyMode.LOW
        }
        player.setMode(latency); ViewerPrefs.setLatency(ctx, latency)
    }
    val latLabel = when (latency) {
        LatencyMode.LOW -> "мин.задержка"; LatencyMode.SMOOTH -> "плавно"; LatencyMode.ADAPTIVE -> "авто"
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // --- видео на весь экран ---
        Box(
            Modifier.fillMaxSize()
                .onSizeChanged { boxSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) { offX += pan.x; offY += pan.y } else { offX = 0f; offY = 0f }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controls = !controls },
                        onDoubleTap = { tap ->
                            if (scale > 1f) { scale = 1f; offX = 0f; offY = 0f } else {
                                scale = 2.5f
                                val cx = boxSize.width / 2f; val cy = boxSize.height / 2f
                                offX = (cx - tap.x) * scale; offY = (cy - tap.y) * scale
                            }
                        },
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

        // --- индикатор подключения ---
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

        // --- HUD ---
        if (hud && videoStarted) {
            Text(
                "${if (stream == "Main") "HD" else "SD"} • ${stats.fps} fps • ${stats.kbps} kbps • буф ${stats.bufferedFrames} • ~${stats.latencyMs}ms • $latLabel",
                Modifier.align(Alignment.TopStart).padding(top = 40.dp, start = 8.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall,
            )
        }

        // индикатор записи
        if (recording) {
            Text(
                "⏺ REC",
                Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 8.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color.Red.copy(alpha = 0.7f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall,
            )
        }

        // --- верхняя панель (автоскрытие) ---
        AnimatedVisibility(visible = controls, modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                TextButton(onClick = onBack) { Text("←", color = Color.White) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { cycleLatency() }) { Text(latLabel, color = Color.White) }
                TextButton(onClick = { hud = !hud; ViewerPrefs.setHud(ctx, hud) }) {
                    Text("ⓘ", color = if (hud) Color.White else Color.Gray)
                }
                TextButton(onClick = { toggleRecord() }) {
                    Text(if (recording) "⏹" else "⏺", color = if (recording) Color.Red else Color.White)
                }
                TextButton(onClick = { takeSnapshot(ctx, surfaceView) { status = it } }) { Text("📷") }
                TextButton(onClick = { sound = !sound }) { Text(if (sound) "🔊" else "🔈") }
                TextButton(onClick = {
                    stream = if (stream == "Main") "Extra" else "Main"
                    player.requestStream(stream); ViewerPrefs.setStream(ctx, stream)
                }) {
                    Text(if (stream == "Main") "HD" else "SD", color = Color.White)
                }
            }
        }

        // --- рация внизу ---
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
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                color = Color.White, style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Снимок текущего кадра из SurfaceView через PixelCopy → JPEG в папку Pictures приложения. */
private fun takeSnapshot(ctx: Context, sv: SurfaceView?, onResult: (String) -> Unit) {
    if (sv == null || sv.width == 0 || sv.height == 0) { onResult("Нет кадра для снимка"); return }
    val bmp = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(sv, bmp, { result ->
            if (result == PixelCopy.SUCCESS) {
                try {
                    val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.cacheDir
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
