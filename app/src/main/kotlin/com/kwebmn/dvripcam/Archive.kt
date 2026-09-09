package com.kwebmn.dvripcam

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kwebmn.dvripcam.dvrip.DvripClient
import com.kwebmn.dvripcam.dvrip.RecordingFile
import com.kwebmn.dvripcam.video.H264Decoder
import com.kwebmn.dvripcam.video.Mp4Saver
import com.kwebmn.dvripcam.video.NalExtractor
import com.kwebmn.dvripcam.video.SofiaDemuxer
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.net.SocketFactory

/** Список записей за диапазон дат с пагинацией (лимит ~64/запрос -> окна по 6ч). */
private suspend fun listRecordings(
    client: DvripClient, d0: LocalDate, d1: LocalDate, onStatus: (String) -> Unit,
): List<RecordingFile> {
    val out = LinkedHashMap<String, RecordingFile>()
    val windows = listOf(
        "00:00:00" to "05:59:59", "06:00:00" to "11:59:59",
        "12:00:00" to "17:59:59", "18:00:00" to "23:59:59",
    )
    val last = if (ChronoUnit.DAYS.between(d0, d1) > 62) d0.plusDays(62) else d1
    var day = d0
    while (!day.isAfter(last)) {
        val ds = day.toString()
        onStatus("Читаю $ds …")
        val full = client.queryFiles("$ds 00:00:00", "$ds 23:59:59")
        if (full.size >= 64) {
            for ((a, b) in windows) for (f in client.queryFiles("$ds $a", "$ds $b")) out[f.name] = f
        } else for (f in full) out[f.name] = f
        day = day.plusDays(1)
    }
    return out.values.sortedBy { it.begin }
}

@Composable
fun ArchiveScreen(
    host: String, port: Int, user: String, pass: String,
    onBack: () -> Unit, onPlay: (RecordingFile) -> Unit,
) {
    val ctx = LocalContext.current
    val wifiSf = remember { wifiSocketFactory(ctx) }
    val today = remember { LocalDate.now() }
    var begin by rememberSaveable { mutableStateOf(today.minusDays(7).toString()) }
    var end by rememberSaveable { mutableStateOf(today.toString()) }
    var status by remember { mutableStateOf("Задай период и нажми «Показать»") }
    var files by remember { mutableStateOf<List<RecordingFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load() {
        if (loading) return
        loading = true; files = emptyList(); status = "Подключаюсь…"
        scope.launch(Dispatchers.IO) {
            val client = DvripClient(host, port)
            try {
                if (!client.connectAwait(user, pass, wifiSf) { status = it }) return@launch
                status = "Загружаю список…"
                val d0 = runCatching { LocalDate.parse(begin) }.getOrNull()
                val d1 = runCatching { LocalDate.parse(end) }.getOrNull()
                if (d0 == null || d1 == null) { status = "Неверная дата (YYYY-MM-DD)"; return@launch }
                val list = listRecordings(client, d0, d1) { status = it }
                files = list
                status = "Записей: ${list.size}  ([A] = движение, [R] = обычная)"
            } catch (e: Exception) {
                status = "Ошибка: ${e.message}"
            } finally {
                runCatching { client.close() }; loading = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Архив (SD)", style = MaterialTheme.typography.titleLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(begin, { begin = it }, label = { Text("С") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(end, { end = it }, label = { Text("По") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Button(onClick = { load() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            Text(if (loading) "Загрузка…" else "Показать")
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(files) { f ->
                ListItem(
                    modifier = Modifier.clickable { onPlay(f) },
                    leadingContent = {
                        Text(if (f.isAlarm) "[A]" else "[R]",
                            color = if (f.isAlarm) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                    },
                    headlineContent = { Text(f.begin) },
                    supportingContent = { Text("→ ${f.end.takeLast(8)}") },
                )
                HorizontalDivider()
            }
        }
    }
}

/** Плеер плейбэка записи (аналог LivePlayer, но тянет OPPlayBack). */
private class PlaybackPlayer(
    private val host: String, private val port: Int, private val user: String, private val pass: String,
    private val wifiSf: SocketFactory?, private val file: RecordingFile,
    private val onStatus: (String) -> Unit, private val onVideoSize: (Int, Int) -> Unit,
) {
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
                if (!c.connectAwait(user, pass, wifiSf) { onStatus(it) }) return@launch
                onStatus("Воспроизведение…")
                val dec = H264Decoder(surface, onError = { onStatus(it) }, onVideoSize = onVideoSize); decoder = dec
                val extractor = NalExtractor { nal -> dec.submitNal(nal) }
                val demux = SofiaDemuxer(onVideo = { extractor.feed(it) })
                c.playback(file.name, file.begin, file.end, onPayload = { demux.feed(it) }, isRunning = { running })
                if (running) onStatus("Воспроизведение завершено")
            } catch (e: Exception) {
                if (running) onStatus("Прервано: ${e.message}")
            } finally {
                runCatching { decoder?.stop() }; runCatching { client?.close() }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { decoder?.stop() }; runCatching { client?.close() }; scope?.cancel()
    }
}

@Composable
fun PlaybackScreen(
    host: String, port: Int, user: String, pass: String,
    file: RecordingFile, onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val wifiSf = remember { wifiSocketFactory(ctx) }
    var status by remember { mutableStateOf("Готовлюсь…") }
    var aspect by remember { mutableStateOf(4f / 3f) }
    var saving by remember { mutableStateOf(false) }
    var saveMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val player = remember {
        PlaybackPlayer(host, port, user, pass, wifiSf, file,
            onStatus = { status = it }, onVideoSize = { w, h -> if (h > 0) aspect = w.toFloat() / h })
    }
    DisposableEffect(player) { onDispose { player.stop() } }

    fun download() {
        if (saving) return
        saving = true; player.stop(); saveMsg = "Скачиваю…"
        scope.launch(Dispatchers.IO) {
            val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: ctx.cacheDir
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
                saveMsg = if (ok) "✅ Сохранено: ${out.name} (${saver.frames} кадров)\n${out.absolutePath}"
                else "Не удалось сохранить: ${saver.error ?: "нет кадров"}"
            } catch (e: Exception) {
                runCatching { saver.finish() }
                saveMsg = "Ошибка скачивания: ${e.message}"
            } finally {
                runCatching { client.close() }; saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text(file.begin, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { download() }, enabled = !saving) {
                Text(if (saving) "…" else "⬇ MP4")
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
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
        if (saveMsg.isNotBlank()) Text(saveMsg, style = MaterialTheme.typography.bodySmall)
    }
}
