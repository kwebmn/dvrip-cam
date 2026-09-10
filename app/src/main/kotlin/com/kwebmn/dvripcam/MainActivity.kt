package com.kwebmn.dvripcam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.kwebmn.dvripcam.dvrip.DvripClient
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var route by remember { mutableStateOf<Route>(Route.Connect) }
                    when (val r = route) {
                        is Route.Connect -> ConnectScreen(
                            onOpenLive = { h, p, u, pw -> route = Route.Live(h, p, u, pw) },
                            onOpenArchive = { h, p, u, pw -> route = Route.Archive(h, p, u, pw) },
                            onOpenSettings = { h, p, u, pw -> route = Route.Settings(h, p, u, pw) },
                        )
                        is Route.Live -> LiveScreen(r.host, r.port, r.user, r.pass) { route = Route.Connect }
                        is Route.Archive -> ArchiveScreen(
                            r.host, r.port, r.user, r.pass,
                            onBack = { route = Route.Connect },
                            onPlay = { f -> route = Route.Playback(r.host, r.port, r.user, r.pass, f) },
                        )
                        is Route.Playback -> PlaybackScreen(
                            r.host, r.port, r.user, r.pass, r.file,
                            onBack = { route = Route.Archive(r.host, r.port, r.user, r.pass) },
                        )
                        is Route.Settings -> SettingsScreen(
                            r.host, r.port, r.user, r.pass,
                            onBack = { route = Route.Connect },
                        )
                    }
                }
            }
        }
    }
}

sealed interface Route {
    object Connect : Route
    data class Live(val host: String, val port: Int, val user: String, val pass: String) : Route
    data class Archive(val host: String, val port: Int, val user: String, val pass: String) : Route
    data class Settings(val host: String, val port: Int, val user: String, val pass: String) : Route
    data class Playback(
        val host: String, val port: Int, val user: String, val pass: String,
        val file: com.kwebmn.dvripcam.dvrip.RecordingFile,
    ) : Route
}

@Composable
private fun ConnectScreen(
    onOpenLive: (String, Int, String, String) -> Unit,
    onOpenArchive: (String, Int, String, String) -> Unit,
    onOpenSettings: (String, Int, String, String) -> Unit,
) {
    var host by rememberSaveable { mutableStateOf("192.168.1.10") }
    var port by rememberSaveable { mutableStateOf("34567") }
    var user by rememberSaveable { mutableStateOf("admin") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("Введите данные камеры и нажмите «Подключиться»") }
    var busy by remember { mutableStateOf(false) }
    var canLive by rememberSaveable { mutableStateOf(false) }
    var updCheckMsg by remember { mutableStateOf("") }

    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity

    // --- сохранённые камеры ---
    val store = remember { CameraStore(activity) }
    var saved by remember { mutableStateOf(store.list()) }

    // --- поиск в сети ---
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<FoundCamera>>(emptyList()) }

    // --- автообновление из GitHub Releases ---
    var update by remember { mutableStateOf<com.kwebmn.dvripcam.update.ReleaseInfo?>(null) }
    var updMsg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val rel = com.kwebmn.dvripcam.update.Updater.latestRelease()
        if (rel != null && com.kwebmn.dvripcam.update.Updater.isNewer(rel.versionName)) update = rel
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("DVRIP Cam", style = MaterialTheme.typography.headlineMedium)
        Text("Локальное подключение к камере (DVRIP, порт 34567)", style = MaterialTheme.typography.bodySmall)

        if (saved.isNotEmpty()) {
            Text("Мои камеры", style = MaterialTheme.typography.labelMedium)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                saved.forEach { cam ->
                    InputChip(
                        selected = host == cam.host && port == cam.port.toString(),
                        onClick = {
                            host = cam.host; port = cam.port.toString()
                            user = cam.user; password = cam.pass
                            canLive = false
                            status = "Выбрана «${cam.name}». Нажмите «Подключиться»"
                        },
                        label = { Text(cam.name.ifBlank { cam.host }) },
                        trailingIcon = {
                            Text("✕", modifier = Modifier.clickable {
                                store.remove(cam.host, cam.port); saved = store.list()
                            })
                        },
                    )
                }
            }
        }

        OutlinedButton(
            onClick = {
                if (scanning) return@OutlinedButton
                scanning = true; found = emptyList(); status = "Ищу камеры в сети…"
                activity.lifecycleScope.launch {
                    val acc = LinkedHashMap<String, FoundCamera>()
                    Discovery.scan(activity, durationMs = 6000) { cam ->
                        acc[cam.host] = cam; found = acc.values.toList()
                    }
                    scanning = false
                    status = if (found.isEmpty())
                        "Камеры не найдены. Разбуди камеру движением и повтори."
                    else "Найдено: ${found.size}"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (scanning) "Поиск…" else "🔎 Найти камеру в сети") }

        if (found.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                found.forEach { cam ->
                    AssistChip(
                        onClick = { host = cam.host; canLive = false; status = "Выбрано ${cam.host}" },
                        label = { Text(cam.name.ifBlank { cam.host }) },
                    )
                }
            }
        }

        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("IP камеры") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Порт") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Логин") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Пароль") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showPassword = !showPassword }) {
                    Text(if (showPassword) "Скрыть" else "Показать")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                if (busy) { busy = false; return@Button }   // повторный тап = отмена ожидания
                busy = true
                status = "Подключаюсь…"
                val sf = wifiSocketFactory(activity)
                val h = host.trim()
                val p = port.trim().toIntOrNull() ?: 34567
                activity.lifecycleScope.launch {
                    val deadline = System.currentTimeMillis() + 60_000
                    var done = false
                    while (!done && busy) {
                        val client = DvripClient(h, p)
                        try {
                            client.connect(timeoutMs = 3000, socketFactory = sf)
                            if (!client.login(user.trim(), password)) {
                                status = "Ошибка логина (неверный пароль?)"
                                done = true
                            } else {
                                val info: JSONObject = client.systemInfo()
                                status = buildString {
                                    appendLine("✅ Подключено")
                                    appendLine("HardWare: " + info.optString("HardWare", "—"))
                                    appendLine("Прошивка: " + info.optString("SoftWareVersion", "—"))
                                    appendLine("Serial: " + info.optString("SerialNo", "—"))
                                    appendLine("Сборка: " + info.optString("BuildTime", "—"))
                                }
                                canLive = true
                                done = true
                            }
                        } catch (e: Exception) {
                            // камера спит / недоступна — ждём и переспрашиваем
                            if (System.currentTimeMillis() >= deadline) {
                                status = "Камера не ответила на $h.\nИщу её в сети (может, сменился IP)…"
                                done = true
                                // авто-поиск: вдруг адрес изменился (напр. .25 → .27)
                                val acc = LinkedHashMap<String, FoundCamera>()
                                Discovery.scan(activity, durationMs = 6000) { c -> acc[c.host] = c; found = acc.values.toList() }
                                status = if (found.isEmpty())
                                    "Камера не ответила на $h и не найдена в сети.\nРазбуди движением (PIR) и повтори."
                                else "Не ответила на $h. Найдено в сети: ${found.joinToString { it.host }} — выбери камеру выше."
                            } else {
                                val left = ((deadline - System.currentTimeMillis()) / 1000)
                                status = "Жду пробуждения камеры… помаши рукой перед ней ($left с)\n(тап по кнопке — отмена)"
                                kotlinx.coroutines.delay(2000)
                            }
                        } finally {
                            client.close()
                        }
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Ожидание… (отмена)" else "Подключиться") }

        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Text(status, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }

        if (canLive) {
            Button(
                onClick = { onOpenLive(host.trim(), port.trim().toIntOrNull() ?: 34567, user.trim(), password) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("▶ Смотреть Live") }
            OutlinedButton(
                onClick = { onOpenArchive(host.trim(), port.trim().toIntOrNull() ?: 34567, user.trim(), password) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("🗂 Архив (SD)") }
            OutlinedButton(
                onClick = { onOpenSettings(host.trim(), port.trim().toIntOrNull() ?: 34567, user.trim(), password) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("⚙ Настройки / Приватность") }
            TextButton(
                onClick = {
                    val h = host.trim(); val p = port.trim().toIntOrNull() ?: 34567
                    val existing = saved.firstOrNull { it.host == h && it.port == p }?.name
                    store.save(CameraEntry(existing?.ifBlank { h } ?: h, h, p, user.trim(), password))
                    saved = store.list()
                    status = "Камера сохранена ($h)"
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("💾 Сохранить камеру") }
        }

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                "Версия ${com.kwebmn.dvripcam.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                updCheckMsg = "Проверяю…"
                activity.lifecycleScope.launch {
                    val rel = com.kwebmn.dvripcam.update.Updater.latestRelease()
                    when {
                        rel == null -> updCheckMsg = "Не удалось проверить обновления"
                        com.kwebmn.dvripcam.update.Updater.isNewer(rel.versionName) -> {
                            update = rel; updCheckMsg = ""
                        }
                        else -> updCheckMsg = "У вас последняя версия (${rel.tag})"
                    }
                }
            }) { Text("Проверить обновления") }
        }
        if (updCheckMsg.isNotBlank()) {
            Text(updCheckMsg, style = MaterialTheme.typography.bodySmall)
        }

        update?.let { rel ->
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("⬆️ Доступно обновление ${rel.tag}", style = MaterialTheme.typography.titleMedium)
                    if (rel.changelog.isNotBlank())
                        Text(rel.changelog.take(400), style = MaterialTheme.typography.bodySmall)
                    if (updMsg.isNotBlank()) Text(updMsg, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        val u = com.kwebmn.dvripcam.update.Updater
                        if (!u.ensureInstallPermission(activity)) {
                            updMsg = "Разреши установку приложений из этого источника и нажми снова"
                            return@Button
                        }
                        updMsg = "Скачиваю…"
                        activity.lifecycleScope.launch {
                            try {
                                val apk = u.downloadApk(activity, rel.apkUrl, rel.apkName) { p ->
                                    updMsg = if (p >= 0) "Скачиваю… $p%" else "Скачиваю…"
                                }
                                updMsg = "Запускаю установку…"
                                u.installApk(activity, apk)
                            } catch (e: Exception) {
                                updMsg = "Ошибка обновления: ${e.message}"
                            }
                        }
                    }) { Text("Обновить до ${rel.tag}") }
                }
            }
        }
    }
}

/**
 * SocketFactory, привязанный к текущей Wi-Fi-сети. Гарантирует, что локальное соединение
 * к камере пойдёт через Wi-Fi, даже если включены мобильные данные (иначе Android может
 * маршрутизировать сокет через соту → "No route to host").
 */
internal fun wifiSocketFactory(context: android.content.Context): javax.net.SocketFactory? {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
    val net = cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    return net?.socketFactory
}
