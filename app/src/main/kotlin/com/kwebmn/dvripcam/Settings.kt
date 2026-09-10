package com.kwebmn.dvripcam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kwebmn.dvripcam.dvrip.DvripClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Настройки приватности («де-китаизация»): выключить облако Xiongmai (NetWork.Nat, secu100.net)
 * и пуш-сервис (NetWork.PMS, push.umeye.cn). Плюс инфо о текущем Wi-Fi.
 */
@Composable
fun SettingsScreen(host: String, port: Int, user: String, pass: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val wifiSf = remember { wifiSocketFactory(ctx) }
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Загружаю настройки…") }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    var natRaw by remember { mutableStateOf<JSONObject?>(null) }
    var pmsRaw by remember { mutableStateOf<JSONObject?>(null) }
    var natEnabled by remember { mutableStateOf(false) }
    var pmsEnabled by remember { mutableStateOf(false) }
    var wifiSsid by remember { mutableStateOf("—") }

    // статус + камера
    var battery by remember { mutableStateOf<Int?>(null) }
    var sdText by remember { mutableStateOf("—") }
    var camTime by remember { mutableStateOf("—") }
    var camName by remember { mutableStateOf("") }
    var pirEnabled by remember { mutableStateOf(false) }
    var pirSens by remember { mutableStateOf(0f) }
    var motionEnabled by remember { mutableStateOf(false) }

    // helper: одно соединение на операцию
    fun withClient(block: suspend (DvripClient) -> Unit) {
        if (busy) return
        busy = true
        scope.launch(Dispatchers.IO) {
            val c = DvripClient(host, port)
            try {
                if (!c.connectAwait(user, pass, wifiSf) { status = it }) return@launch
                block(c)
            } catch (e: Exception) {
                status = "Ошибка: ${e.message}"
            } finally {
                runCatching { c.close() }; busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        withClient { c ->
            val nat = c.getConfig("NetWork.Nat").optJSONObject("NetWork.Nat")
            val pms = c.getConfig("NetWork.PMS").optJSONObject("NetWork.PMS")
            val wifi = c.getConfig("NetWork.Wifi").optJSONObject("NetWork.Wifi")
            natRaw = nat; pmsRaw = pms
            natEnabled = nat?.optBoolean("NatEnable", nat.optInt("NatEnable", 0) == 1) ?: false
            pmsEnabled = pms?.optBoolean("Enable", pms.optInt("Enable", 0) == 1) ?: false
            wifiSsid = wifi?.optString("SSID")?.ifBlank { "—" } ?: "—"
            battery = runCatching { c.batteryPercent() }.getOrNull()
            sdText = runCatching { c.storageInfo() }.getOrNull()?.let { (rem, tot, span) ->
                "%.1f / %.1f ГБ своб.".format(rem / 1024f, tot / 1024f) + "\nзаписи: $span"
            } ?: "—"
            camTime = runCatching { c.getTime() }.getOrNull() ?: "—"
            camName = runCatching { c.getChannelTitle() }.getOrNull() ?: ""
            runCatching { c.getPir() }.getOrNull()?.let { (en, s) -> pirEnabled = en; pirSens = s.toFloat() }
            motionEnabled = runCatching { c.getMotionEnabled() }.getOrNull() ?: false
            loaded = true
            status = "Готово"
        }
    }

    fun syncTime() = withClient { c ->
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val ok = c.setTime(now)
        if (ok) camTime = now
        status = if (ok) "Часы синхронизированы: $now" else "Не удалось синхронизировать время"
    }

    fun saveName() = withClient { c ->
        val ok = c.setChannelTitle(camName)
        status = if (ok) "Имя камеры сохранено" else "Не удалось сохранить имя"
    }

    fun applyPir(en: Boolean, sens: Int) = withClient { c ->
        val ok = c.setPir(en, sens)
        if (ok) { pirEnabled = en; pirSens = sens.toFloat() }
        status = if (ok) "PIR: ${if (en) "вкл" else "выкл"}, чувствит. $sens" else "Не удалось изменить PIR"
    }

    fun applyMotion(en: Boolean) = withClient { c ->
        val ok = c.setMotionEnabled(en)
        if (ok) motionEnabled = en
        status = if (ok) "Детекция движения: ${if (en) "вкл" else "выкл"}" else "Не удалось изменить детекцию"
    }

    fun applyNat(enable: Boolean) = withClient { c ->
        val obj = natRaw ?: JSONObject()
        obj.put("NatEnable", enable)
        val resp = c.setConfig("NetWork.Nat", obj)
        val ok = resp.optInt("Ret", -1).let { it == 100 || it == 0 }
        natEnabled = if (ok) enable else natEnabled
        status = if (ok) "Облако secu100.net: ${if (enable) "включено" else "ВЫКЛЮЧЕНО"}" else "Не удалось (Ret ${resp.optInt("Ret")})"
    }

    fun applyPms(enable: Boolean) = withClient { c ->
        val obj = pmsRaw ?: JSONObject()
        obj.put("Enable", enable)
        val resp = c.setConfig("NetWork.PMS", obj)
        val ok = resp.optInt("Ret", -1).let { it == 100 || it == 0 }
        pmsEnabled = if (ok) enable else pmsEnabled
        status = if (ok) "Пуш push.umeye.cn: ${if (enable) "включён" else "ВЫКЛЮЧЕН"}" else "Не удалось (Ret ${resp.optInt("Ret")})"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Настройки", style = MaterialTheme.typography.titleLarge)
        }

        // --- Статус ---
        Text("Статус", style = MaterialTheme.typography.titleMedium)
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val batTxt = when (val b = battery) {
                    null -> "—"; -1 -> "на внешнем питании"; -2 -> "—"; else -> "$b%"
                }
                Text("🔋 Батарея: $batTxt")
                Text("💾 SD: $sdText", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🕐 Время камеры: $camTime", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { syncTime() }, enabled = loaded && !busy) { Text("Синхр.") }
                }
            }
        }

        // --- Камера ---
        Text("Камера", style = MaterialTheme.typography.titleMedium)
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = camName, onValueChange = { camName = it },
                    label = { Text("Имя камеры (OSD-титул)") }, singleLine = true,
                    enabled = loaded && !busy, modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { saveName() }, enabled = loaded && !busy) { Text("Сохранить имя") }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PIR-датчик (движение/пробуждение)")
                        Text("Чувствительность: ${pirSens.toInt()} (0–4)", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = pirEnabled, enabled = loaded && !busy,
                        onCheckedChange = { applyPir(it, pirSens.toInt()) })
                }
                Slider(
                    value = pirSens, onValueChange = { pirSens = it },
                    onValueChangeFinished = { applyPir(pirEnabled, pirSens.toInt()) },
                    valueRange = 0f..4f, steps = 3, enabled = loaded && !busy && pirEnabled,
                )

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Детекция движения (по видео)", Modifier.weight(1f))
                    Switch(checked = motionEnabled, enabled = loaded && !busy,
                        onCheckedChange = { applyMotion(it) })
                }
            }
        }

        Text("Приватность (де-китаизация)", style = MaterialTheme.typography.titleMedium)

        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Облако XMEye (secu100.net)")
                        Text("Отвязать камеру от китайского облака", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = natEnabled, enabled = loaded && !busy,
                        onCheckedChange = { applyNat(it) })
                }
            }
        }
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Пуш-сервис (push.umeye.cn)")
                        Text("Отключить облачные push-уведомления", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = pmsEnabled, enabled = loaded && !busy,
                        onCheckedChange = { applyPms(it) })
                }
            }
        }

        Text("Wi-Fi камеры: $wifiSsid", style = MaterialTheme.typography.bodyMedium)

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(status, style = MaterialTheme.typography.bodySmall)
        Text(
            "Выключение относится только к облаку. Локальный доступ по DVRIP (просмотр, архив) " +
                "продолжит работать. Чтобы полностью отрезать камеру от интернета — заблокируй её на роутере.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
    }
}
