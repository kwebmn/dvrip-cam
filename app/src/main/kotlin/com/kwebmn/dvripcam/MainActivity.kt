package com.kwebmn.dvripcam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.kwebmn.dvripcam.dvrip.DvripClient
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Авто-старт: есть сохранённая камера — сразу её Live; иначе — список.
                    val startRoute = remember {
                        CameraStore(this@MainActivity).list().lastOrNull()?.let {
                            Route.Live(it.host, it.port, it.user, it.pass)
                        } ?: Route.Home
                    }
                    var route by remember { mutableStateOf<Route>(startRoute) }
                    when (val r = route) {
                        is Route.Home -> HomeScreen(
                            onLive = { c -> route = Route.Live(c.host, c.port, c.user, c.pass) },
                            onArchive = { c -> route = Route.Archive(c.host, c.port, c.user, c.pass) },
                            onSettings = { c -> route = Route.Settings(c.host, c.port, c.user, c.pass) },
                            onEdit = { c -> route = Route.EditCamera(c) },
                            onAdd = { prefill -> route = Route.EditCamera(prefill) },
                        )
                        is Route.EditCamera -> EditCameraScreen(
                            existing = r.existing,
                            onDone = { route = Route.Home },
                        )
                        is Route.Live -> LiveScreen(r.host, r.port, r.user, r.pass) { route = Route.Home }
                        is Route.Archive -> ArchiveScreen(
                            r.host, r.port, r.user, r.pass,
                            onBack = { route = Route.Home },
                            onPlay = { f -> route = Route.Playback(r.host, r.port, r.user, r.pass, f) },
                        )
                        is Route.Playback -> PlaybackScreen(
                            r.host, r.port, r.user, r.pass, r.file,
                            onBack = { route = Route.Archive(r.host, r.port, r.user, r.pass) },
                        )
                        is Route.Settings -> SettingsScreen(
                            r.host, r.port, r.user, r.pass,
                            onBack = { route = Route.Home },
                        )
                    }
                }
            }
        }
    }
}

sealed interface Route {
    object Home : Route
    data class EditCamera(val existing: CameraEntry?) : Route
    data class Live(val host: String, val port: Int, val user: String, val pass: String) : Route
    data class Archive(val host: String, val port: Int, val user: String, val pass: String) : Route
    data class Settings(val host: String, val port: Int, val user: String, val pass: String) : Route
    data class Playback(
        val host: String, val port: Int, val user: String, val pass: String,
        val file: com.kwebmn.dvripcam.dvrip.RecordingFile,
    ) : Route
}

/** Главный экран — список камер с действиями, добавление и поиск. */
@Composable
private fun HomeScreen(
    onLive: (CameraEntry) -> Unit,
    onArchive: (CameraEntry) -> Unit,
    onSettings: (CameraEntry) -> Unit,
    onEdit: (CameraEntry) -> Unit,
    onAdd: (CameraEntry?) -> Unit,
) {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val store = remember { CameraStore(activity) }
    var cameras by remember { mutableStateOf(store.list()) }
    var toDelete by remember { mutableStateOf<CameraEntry?>(null) }

    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<FoundCamera>>(emptyList()) }
    var scanMsg by remember { mutableStateOf("") }

    // автообновление
    var update by remember { mutableStateOf<com.kwebmn.dvripcam.update.ReleaseInfo?>(null) }
    var updMsg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val rel = com.kwebmn.dvripcam.update.Updater.latestRelease()
        if (rel != null && com.kwebmn.dvripcam.update.Updater.isNewer(rel.versionName)) update = rel
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Камеры", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                if (scanning) return@TextButton
                scanning = true; found = emptyList(); scanMsg = "Ищу в сети…"
                val sf = wifiSocketFactory(activity)
                activity.lifecycleScope.launch {
                    val acc = LinkedHashMap<String, FoundCamera>()
                    fun add(c: FoundCamera) { acc[c.host] = c; found = acc.values.toList() }
                    Discovery.scan(activity, durationMs = 3000) { add(it) }
                    scanMsg = "Сканирую сеть…"
                    Discovery.scanSubnet(sf) { add(it) }
                    scanning = false
                    scanMsg = if (found.isEmpty()) "В сети ничего не найдено" else "Найдено: ${found.size}"
                }
            }) { Text("🔎 Найти") }
            TextButton(onClick = { onAdd(null) }) { Text("➕ Добавить") }
        }

        if (cameras.isEmpty()) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text(
                    "Пока нет камер.\nНажмите «➕ Добавить», чтобы ввести IP/логин, или «🔎 Найти» для поиска в сети.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        cameras.forEach { cam ->
            CameraCard(
                cam = cam,
                onLive = { onLive(cam) },
                onArchive = { onArchive(cam) },
                onSettings = { onSettings(cam) },
                onEdit = { onEdit(cam) },
                onDelete = { toDelete = cam },
            )
        }

        if (scanning || scanMsg.isNotBlank()) {
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (scanMsg.isNotBlank()) Text(scanMsg, style = MaterialTheme.typography.bodySmall)
        }
        if (found.isNotEmpty()) {
            Text("Найдено в сети (нажмите, чтобы добавить):", style = MaterialTheme.typography.labelMedium)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                found.forEach { f ->
                    AssistChip(
                        onClick = { onAdd(CameraEntry(f.name.ifBlank { f.host }, f.host, 34567, "admin", "")) },
                        label = { Text(f.host) },
                    )
                }
            }
        }

        update?.let { rel ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⬆️ Доступно обновление ${rel.tag}", style = MaterialTheme.typography.titleMedium)
                    if (updMsg.isNotBlank()) Text(updMsg, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        val u = com.kwebmn.dvripcam.update.Updater
                        if (!u.ensureInstallPermission(activity)) {
                            updMsg = "Разреши установку из этого источника и нажми снова"; return@Button
                        }
                        updMsg = "Скачиваю…"
                        activity.lifecycleScope.launch {
                            try {
                                val apk = u.downloadApk(activity, rel.apkUrl, rel.apkName) { p ->
                                    updMsg = if (p >= 0) "Скачиваю… $p%" else "Скачиваю…"
                                }
                                updMsg = "Установка…"; u.installApk(activity, apk)
                            } catch (e: Exception) { updMsg = "Ошибка: ${e.message}" }
                        }
                    }) { Text("Обновить до ${rel.tag}") }
                }
            }
        }

        Text(
            "Версия ${com.kwebmn.dvripcam.BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }

    toDelete?.let { cam ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить камеру?") },
            text = { Text("«${cam.name.ifBlank { cam.host }}» будет удалена из списка.") },
            confirmButton = {
                TextButton(onClick = {
                    store.remove(cam.host, cam.port); cameras = store.list(); toDelete = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CameraCard(
    cam: CameraEntry,
    onLive: () -> Unit,
    onArchive: () -> Unit,
    onSettings: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(onClick = onLive, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📷 ${cam.name.ifBlank { cam.host }}", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Text("▶", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text("${cam.host}:${cam.port} · ${cam.user}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onLive) { Text("▶ Live") }
                TextButton(onClick = onArchive) { Text("🗂") }
                TextButton(onClick = onSettings) { Text("⚙") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onEdit) { Text("✏️") }
                TextButton(onClick = onDelete) { Text("🗑") }
            }
        }
    }
}

/** Форма добавления/редактирования камеры. */
@Composable
private fun EditCameraScreen(existing: CameraEntry?, onDone: () -> Unit) {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val store = remember { CameraStore(activity) }

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var host by rememberSaveable { mutableStateOf(existing?.host ?: "192.168.1.10") }
    var port by rememberSaveable { mutableStateOf((existing?.port ?: 34567).toString()) }
    var user by rememberSaveable { mutableStateOf(existing?.user ?: "admin") }
    var password by rememberSaveable { mutableStateOf(existing?.pass ?: "") }
    var showPass by rememberSaveable { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDone) { Text("← Назад") }
            Text(if (existing == null) "Добавить камеру" else "Изменить камеру",
                style = MaterialTheme.typography.titleLarge)
        }

        OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(host, { host = it }, label = { Text("IP камеры") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it }, label = { Text("Порт") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it }, label = { Text("Логин") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            password, { password = it }, label = { Text("Пароль") }, singleLine = true,
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Скрыть" else "Показать") }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(
            onClick = {
                if (testing) return@OutlinedButton
                testing = true; testMsg = "Проверяю…"
                val sf = wifiSocketFactory(activity)
                val h = host.trim(); val p = port.trim().toIntOrNull() ?: 34567
                activity.lifecycleScope.launch {
                    val c = DvripClient(h, p)
                    try {
                        c.connect(3000, sf)
                        testMsg = if (c.login(user.trim(), password)) "✅ Подключение успешно" else "❌ Неверный логин/пароль"
                    } catch (e: Exception) {
                        testMsg = "❌ Не отвечает (спит?): ${e.message}"
                    } finally { runCatching { c.close() }; testing = false }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (testing) "Проверка…" else "Проверить подключение") }
        if (testMsg.isNotBlank()) Text(testMsg, style = MaterialTheme.typography.bodySmall)

        Button(
            onClick = {
                val h = host.trim(); val p = port.trim().toIntOrNull() ?: 34567
                if (h.isBlank()) { testMsg = "Укажите IP камеры"; return@Button }
                // при смене адреса удаляем старую запись, чтобы не плодить дубли
                existing?.let { if (it.host != h || it.port != p) store.remove(it.host, it.port) }
                store.save(CameraEntry(name.trim().ifBlank { h }, h, p, user.trim(), password))
                onDone()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("💾 Сохранить") }

        if (existing != null) {
            TextButton(
                onClick = { store.remove(existing.host, existing.port); onDone() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("🗑 Удалить камеру", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/**
 * SocketFactory, привязанный к текущей Wi-Fi-сети — чтобы локальное соединение шло через
 * Wi-Fi даже при активных мобильных данных (иначе "No route to host").
 */
internal fun wifiSocketFactory(context: android.content.Context): javax.net.SocketFactory? {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
    val net = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    return net?.socketFactory
}
