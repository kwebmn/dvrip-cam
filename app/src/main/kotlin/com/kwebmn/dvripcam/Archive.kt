package com.kwebmn.dvripcam

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kwebmn.dvripcam.dvrip.DvripClient
import com.kwebmn.dvripcam.dvrip.RecordingFile
import com.kwebmn.dvripcam.video.AudioFrameExtractor
import com.kwebmn.dvripcam.video.AudioOut
import com.kwebmn.dvripcam.video.G711
import com.kwebmn.dvripcam.video.H264Decoder
import com.kwebmn.dvripcam.video.LatencyMode
import com.kwebmn.dvripcam.video.Mp4Saver
import com.kwebmn.dvripcam.video.NalExtractor
import com.kwebmn.dvripcam.video.SofiaDemuxer
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.net.SocketFactory

private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun parseTs(s: String): LocalDateTime? = runCatching { LocalDateTime.parse(s.trim(), TS) }.getOrNull()

/** Длительность записи в секундах (или 0). */
private fun durationSec(f: RecordingFile): Long {
    val a = parseTs(f.begin); val b = parseTs(f.end) ?: return 0
    if (a == null) return 0
    return java.time.Duration.between(a, b).seconds.coerceAtLeast(0)
}

private fun fmtDur(sec: Long): String {
    val m = sec / 60; val s = sec % 60
    return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, s) else "%d:%02d".format(m, s)
}

private fun fmtClock(sec: Long): String = "%d:%02d".format(sec / 60, sec % 60)

/** Список записей за один день (окна 6ч, если записей >64). */
private suspend fun listDay(client: DvripClient, day: LocalDate): List<RecordingFile> {
    val ds = day.toString()
    val out = LinkedHashMap<String, RecordingFile>()
    val full = client.queryFiles("$ds 00:00:00", "$ds 23:59:59")
    if (full.size >= 64) {
        val windows = listOf("00:00:00" to "05:59:59", "06:00:00" to "11:59:59", "12:00:00" to "17:59:59", "18:00:00" to "23:59:59")
        for ((a, b) in windows) for (f in client.queryFiles("$ds $a", "$ds $b")) out[f.name] = f
    } else for (f in full) out[f.name] = f
    return out.values.sortedBy { it.begin }
}

/** Группа записей за один день (для режима «Все»). */
private data class DayGroup(val date: LocalDate, val files: List<RecordingFile>)

/** Дата начала записей на SD (из StorageInfo span) — граница для режима «Все». */
private suspend fun storageStartDate(c: DvripClient): LocalDate? {
    val span = runCatching { c.storageInfo() }.getOrNull()?.third ?: return null
    val startStr = span.substringBefore('…').trim().ifBlank { return null }
    return parseTs(startStr)?.toLocalDate()
}

/** Одна строка списка записи. */
@Composable
private fun RecordingRow(f: RecordingFile, onPlay: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onPlay() },
        leadingContent = {
            Text(if (f.isAlarm) "🏃" else "⏺",
                color = if (f.isAlarm) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
        },
        headlineContent = { Text("${f.begin.takeLast(8)} → ${f.end.takeLast(8)}") },
        supportingContent = { Text("длительность ${fmtDur(durationSec(f))}") },
        trailingContent = { Text(if (f.isAlarm) "движение" else "запись", style = MaterialTheme.typography.labelSmall) },
    )
    HorizontalDivider()
}

@Composable
fun ArchiveScreen(
    host: String, port: Int, user: String, pass: String,
    onBack: () -> Unit, onPlay: (RecordingFile) -> Unit,
) {
    val ctx = LocalContext.current
    val wifiSf = remember { wifiSocketFactory(ctx) }
    val today = remember { LocalDate.now() }
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf("all") }   // "all" | "day"
    var motionOnly by rememberSaveable { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    // --- режим «По дням» ---
    var day by rememberSaveable { mutableStateOf(today.toString()) }
    var files by remember { mutableStateOf<List<RecordingFile>>(emptyList()) }
    var loadingDay by remember { mutableStateOf(false) }

    fun loadDay() {
        if (loadingDay) return
        loadingDay = true; files = emptyList(); status = "Загружаю $day …"
        scope.launch(Dispatchers.IO) {
            val client = DvripClient(host, port)
            try {
                if (!client.connectAwait(user, pass, wifiSf) { status = it }) return@launch
                val d = runCatching { LocalDate.parse(day) }.getOrNull() ?: return@launch
                files = listDay(client, d)
                status = "Записей за $day: ${files.size}"
            } catch (e: Exception) { status = "Ошибка: ${e.message}" }
            finally { runCatching { client.close() }; loadingDay = false }
        }
    }
    LaunchedEffect(day, mode) { if (mode == "day") loadDay() }

    fun pickDate() {
        val d = runCatching { LocalDate.parse(day) }.getOrDefault(today)
        DatePickerDialog(ctx, { _, y, m, dom -> day = LocalDate.of(y, m + 1, dom).toString() },
            d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    // --- режим «Все» ---
    val groups = remember { mutableStateListOf<DayGroup>() }
    var cursor by remember { mutableStateOf(today) }         // следующий день для попытки
    var storageStart by remember { mutableStateOf<LocalDate?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var reachedEnd by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    fun loadMoreAll() {
        if (loadingMore || reachedEnd) return
        loadingMore = true; status = "Загружаю…"
        scope.launch(Dispatchers.IO) {
            val client = DvripClient(host, port)
            try {
                if (!client.connectAwait(user, pass, wifiSf) { status = it }) return@launch
                if (storageStart == null) storageStart = storageStartDate(client)
                val floor = storageStart ?: today.minusYears(5)
                var d = cursor
                var addedNonEmpty = 0; var scanned = 0
                while (!d.isBefore(floor) && addedNonEmpty < 2 && scanned < 21) {
                    val list = listDay(client, d)
                    if (list.isNotEmpty()) { groups.add(DayGroup(d, list)); addedNonEmpty++ }
                    d = d.minusDays(1); scanned++
                }
                cursor = d
                if (d.isBefore(floor)) reachedEnd = true
                status = "Дней с записями: ${groups.size}" + if (reachedEnd) " (все)" else ""
            } catch (e: Exception) { status = "Ошибка: ${e.message}" }
            finally { runCatching { client.close() }; loadingMore = false }
        }
    }
    LaunchedEffect(mode) { if (mode == "all" && groups.isEmpty()) loadMoreAll() }
    // подгрузка при прокрутке к концу
    LaunchedEffect(listState, mode) {
        if (mode != "all") return@LaunchedEffect
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }.collect { (last, total) -> if (total > 0 && last >= total - 3) loadMoreAll() }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Архив (SD)", style = MaterialTheme.typography.titleLarge)
        }
        // переключатель режима
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(mode == "all", { mode = "all" }, { Text("Все записи") })
            FilterChip(mode == "day", { mode = "day" }, { Text("По дням") })
            Spacer(Modifier.weight(1f))
            FilterChip(motionOnly, { motionOnly = !motionOnly }, { Text("Движение") })
        }

        if (mode == "day") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { day = LocalDate.parse(day).minusDays(1).toString() }) { Text("◀") }
                OutlinedButton(onClick = { pickDate() }, modifier = Modifier.weight(1f)) { Text(day) }
                TextButton(onClick = { day = LocalDate.parse(day).plusDays(1).toString() },
                    enabled = LocalDate.parse(day).isBefore(today)) { Text("▶") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(day == today.toString(), { day = today.toString() }, { Text("Сегодня") })
                FilterChip(day == today.minusDays(1).toString(), { day = today.minusDays(1).toString() }, { Text("Вчера") })
            }
        }

        Text(status, style = MaterialTheme.typography.bodySmall)
        if (loadingDay || (loadingMore && groups.isEmpty())) LinearProgressIndicator(Modifier.fillMaxWidth())

        if (mode == "day") {
            val shown = if (motionOnly) files.filter { it.isAlarm } else files
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(shown, key = { it.name }) { f -> RecordingRow(f) { onPlay(f) } }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                groups.forEach { g ->
                    val gf = if (motionOnly) g.files.filter { it.isAlarm } else g.files
                    if (gf.isNotEmpty()) {
                        item(key = "h_${g.date}") {
                            Text("${g.date} · ${gf.size}", style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp))
                            HorizontalDivider()
                        }
                        items(gf, key = { it.name }) { f -> RecordingRow(f) { onPlay(f) } }
                    }
                }
                item(key = "footer") {
                    Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                        when {
                            loadingMore -> CircularProgressIndicator()
                            reachedEnd -> Text("Это все записи на камере", style = MaterialTheme.typography.bodySmall)
                            else -> TextButton(onClick = { loadMoreAll() }) { Text("Загрузить ещё") }
                        }
                    }
                }
            }
        }
    }
}

/** Плеер записи: транспорт (seek/пауза/скорость), звук, авто-переподбор кадра. */
private class PlaybackPlayer(
    private val host: String, private val port: Int, private val user: String, private val pass: String,
    private val wifiSf: SocketFactory?, private val file: RecordingFile,
    private val onStatus: (String) -> Unit,
    private val onVideoSize: (Int, Int) -> Unit,
    private val onProgress: (posSec: Long, durSec: Long) -> Unit,
) {
    @Volatile private var running = false
    @Volatile var paused = false
    @Volatile var speed = 1f          // 1/2/4; 0 = максимум (без темпа)
    @Volatile var soundOn = true
    @Volatile private var seekReq: Long = -1   // секунда от начала клипа, куда перемотать
    private var scope: CoroutineScope? = null
    private var client: DvripClient? = null
    private var decoder: H264Decoder? = null
    private val audio = AudioOut()

    private val startTs = parseTs(file.begin) ?: LocalDateTime.now()
    private val durSec = durationSec(file)
    private val fps = 25.0

    fun seekTo(sec: Long) { seekReq = sec.coerceIn(0, durSec) }

    fun start(surface: Surface) {
        if (running) return
        running = true
        val s = CoroutineScope(Dispatchers.IO + SupervisorJob()); scope = s
        s.launch {
            try {
                onStatus("Подключаюсь…")
                val c = DvripClient(host, port); client = c
                if (!c.connectAwait(user, pass, wifiSf) { onStatus(it) }) return@launch
                onStatus("Воспроизведение…")
                val dec = H264Decoder(surface, onError = { onStatus(it) }, onVideoSize = onVideoSize)
                    .also { it.mode = LatencyMode.SMOOTH }
                decoder = dec

                var frames = 0
                var originSec = 0L
                val video = NalExtractor { nal ->
                    dec.submitNal(nal)
                    val t = com.kwebmn.dvripcam.video.nalType(nal)
                    if (t == 1 || t == 5) frames++
                }
                val audioEx = AudioFrameExtractor { payload, fmt ->
                    if (soundOn && speed == 1f && !paused) audio.write(G711.toPcm16(payload, fmt))
                }
                audio.start()

                fun posSec() = (originSec + (frames / fps).toLong()).coerceIn(0, durSec)
                fun tsAt(sec: Long) = startTs.plusSeconds(sec).format(TS)

                c.playbackStart(tsAt(originSec), file.end)
                var paceBase = originSec.toDouble(); var paceWall = System.nanoTime()
                var wasPaused = false
                fun rebase() { paceBase = posSec().toDouble(); paceWall = System.nanoTime() }

                while (running) {
                    val sk = seekReq
                    if (sk >= 0) {
                        seekReq = -1; originSec = sk; frames = 0
                        dec.requestKeyframe()
                        c.playbackSeek(tsAt(sk), file.end)
                        rebase(); onProgress(posSec(), durSec)
                        continue
                    }
                    if (paused) { delay(60); wasPaused = true; onProgress(posSec(), durSec); continue }
                    if (wasPaused) { wasPaused = false; rebase() }

                    val (_, body) = c.readRawPacket()
                    if (body.isEmpty()) {                    // EOF диапазона
                        onProgress(durSec, durSec); onStatus("Конец записи"); paused = true; continue
                    }
                    if (body[0] == '{'.code.toByte()) continue
                    video.feed(body); audioEx.feed(body)
                    onProgress(posSec(), durSec)

                    if (speed > 0f) {
                        val expected = (posSec() - paceBase) / speed
                        val actual = (System.nanoTime() - paceWall) / 1e9
                        if (expected > actual) delay(((expected - actual) * 1000).toLong().coerceIn(0, 500))
                    }
                }
            } catch (e: Exception) {
                if (running) onStatus("Прервано: ${e.message}")
            } finally {
                runCatching { audio.stop() }; runCatching { decoder?.stop() }; runCatching { client?.close() }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { audio.stop() }; runCatching { decoder?.stop() }; runCatching { client?.close() }; scope?.cancel()
    }
}

@Composable
fun PlaybackScreen(
    host: String, port: Int, user: String, pass: String,
    file: RecordingFile, onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val wifiSf = remember { wifiSocketFactory(ctx) }
    val view = LocalView.current
    var status by remember { mutableStateOf("Готовлюсь…") }
    var aspect by remember { mutableStateOf(16f / 9f) }
    var saving by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var sound by remember { mutableStateOf(true) }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(durationSec(file)) }
    var scrub by remember { mutableStateOf<Float?>(null) }  // при перетаскивании
    var controls by remember { mutableStateOf(true) }
    var scale by remember { mutableStateOf(1f) }
    var offX by remember { mutableStateOf(0f) }
    var offY by remember { mutableStateOf(0f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }
    val scope = rememberCoroutineScope()

    val player = remember {
        PlaybackPlayer(host, port, user, pass, wifiSf, file,
            onStatus = { status = it },
            onVideoSize = { w, h -> if (h > 0) aspect = w.toFloat() / h },
            onProgress = { p, d -> pos = p; if (d > 0) dur = d })
    }
    DisposableEffect(player) { onDispose { player.stop() } }

    // keep-screen-on + immersive
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

    LaunchedEffect(controls, paused) { if (controls && !paused) { delay(3500); controls = false } }

    fun cycleSpeed() {
        speed = when (speed) { 1f -> 2f; 2f -> 4f; 4f -> 0f; else -> 1f }
        player.speed = speed
    }
    val speedLabel = when (speed) { 1f -> "1×"; 2f -> "2×"; 4f -> "4×"; else -> "макс" }

    fun download() {
        if (saving) return
        saving = true; saveMsg = "Скачиваю…"
        scope.launch(Dispatchers.IO) {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.cacheDir
            val safe = file.begin.replace(Regex("[^0-9]"), "").ifBlank { System.currentTimeMillis().toString() }
            val out = File(dir, "cam_$safe.mp4")
            val client = DvripClient(host, port)
            val saver = Mp4Saver(out)
            try {
                if (!client.connectAwait(user, pass, wifiSf) { saveMsg = it }) return@launch
                val extractor = NalExtractor { nal -> saver.onNal(nal) }
                val demux = SofiaDemuxer(onVideo = { extractor.feed(it) })
                var running = true
                client.playback(file.name, file.begin, file.end,
                    onPayload = { demux.feed(it); if (saver.frames % 30 == 0L) saveMsg = "Скачиваю… кадров: ${saver.frames}" },
                    isRunning = { running })
                running = false
                val ok = saver.finish()
                saveMsg = if (ok) "✅ Сохранено: ${out.name} (${saver.frames} кадров)" else "Не удалось: ${saver.error ?: "нет кадров"}"
            } catch (e: Exception) {
                runCatching { saver.finish() }; saveMsg = "Ошибка: ${e.message}"
            } finally {
                runCatching { client.close() }; saving = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
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
                                offX = (boxSize.width / 2f - tap.x) * scale; offY = (boxSize.height / 2f - tap.y) * scale
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offX, translationY = offY),
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

        // верхняя панель
        AnimatedVisibility(visible = controls, modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("←", color = Color.White) }
                Text(file.begin.takeLast(8), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { sound = !sound; player.soundOn = sound }) { Text(if (sound) "🔊" else "🔈") }
                TextButton(onClick = { takeSnap(ctx, surfaceView) { status = it } }) { Text("📷") }
                TextButton(onClick = { download() }, enabled = !saving) { Text(if (saving) "…" else "⬇") }
            }
        }

        // нижняя панель транспорта
        AnimatedVisibility(visible = controls, modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Slider(
                    value = scrub ?: pos.toFloat(),
                    onValueChange = { scrub = it },
                    onValueChangeFinished = { scrub?.let { player.seekTo(it.toLong()); pos = it.toLong() }; scrub = null },
                    valueRange = 0f..(dur.coerceAtLeast(1)).toFloat(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtClock((scrub ?: pos.toFloat()).toLong()) + " / " + fmtClock(dur), color = Color.White,
                        style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { player.seekTo((pos - 10).coerceAtLeast(0)) }) { Text("-10", color = Color.White) }
                    TextButton(onClick = { paused = !paused; player.paused = paused }) {
                        Text(if (paused) "▶" else "⏸", color = Color.White)
                    }
                    TextButton(onClick = { player.seekTo((pos + 10).coerceAtMost(dur)) }) { Text("+10", color = Color.White) }
                    TextButton(onClick = { cycleSpeed() }) { Text(speedLabel, color = Color.White) }
                }
            }
        }

        Text(status, Modifier.align(Alignment.TopStart).padding(top = 40.dp, start = 8.dp),
            color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        if (saveMsg.isNotBlank()) {
            Text(saveMsg, Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.6f)).padding(8.dp),
                color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Снимок кадра из SurfaceView -> JPEG. */
private fun takeSnap(ctx: Context, sv: SurfaceView?, onResult: (String) -> Unit) {
    if (sv == null || sv.width == 0 || sv.height == 0) { onResult("Нет кадра"); return }
    val bmp = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(sv, bmp, { result ->
            if (result == PixelCopy.SUCCESS) {
                try {
                    val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.cacheDir
                    val out = File(dir, "snap_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    onResult("📷 ${out.name}")
                    Toast.makeText(ctx, "Снимок: ${out.name}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { onResult("Ошибка снимка: ${e.message}") }
            } else onResult("PixelCopy: $result")
        }, Handler(Looper.getMainLooper()))
    } catch (e: Exception) { onResult("Снимок недоступен: ${e.message}") }
}
