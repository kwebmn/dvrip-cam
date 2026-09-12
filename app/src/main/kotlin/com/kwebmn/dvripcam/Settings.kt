package com.kwebmn.dvripcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.kwebmn.dvripcam.alarm.AlarmPrefs
import com.kwebmn.dvripcam.alarm.AlarmService
import com.kwebmn.dvripcam.dvrip.DvripClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Настройки приватности («де-китаизация»): выключить облако Xiongmai (NetWork.Nat, secu100.net)
 * и пуш-сервис (NetWork.PMS, push.umeye.cn). Плюс инфо о текущем Wi-Fi.
 */
@Composable
fun SettingsScreen(
    host: String, port: Int, user: String, pass: String,
    onBack: () -> Unit,
    onEventLog: () -> Unit = {},
) {
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
    var motionLevel by remember { mutableStateOf(3f) }
    var humanEnabled by remember { mutableStateOf<Boolean?>(null) }
    var img by remember { mutableStateOf<DvripClient.ImageParam?>(null) }
    // запись
    var recordMode by remember { mutableStateOf<String?>(null) } // ClosedRecord/ConfigRecord/ManualRecord
    var packetLen by remember { mutableStateOf(3f) }
    // цвет
    var color by remember { mutableStateOf<DvripClient.VideoColor?>(null) }
    // качество основного потока
    var encFps by remember { mutableStateOf<Int?>(null) }
    var encBitrate by remember { mutableStateOf(2048) }
    var encRes by remember { mutableStateOf("") }
    // сторож движения
    var watchOn by remember { mutableStateOf(AlarmPrefs.enabled(ctx)) }

    fun enableWatch() {
        AlarmPrefs.setEnabled(ctx, true); AlarmPrefs.setTarget(ctx, "$host:$port")
        AlarmService.start(ctx); watchOn = true; status = "Сторож движения включён"
    }

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableWatch() else { watchOn = false; status = "Нужно разрешение на уведомления" }
    }

    fun toggleWatch(on: Boolean) {
        if (!on) {
            AlarmPrefs.setEnabled(ctx, false); AlarmService.stop(ctx)
            watchOn = false; status = "Сторож движения выключен"
            return
        }
        val needPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needPerm) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else enableWatch()
    }

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
            runCatching { c.getMotion() }.getOrNull()?.let { (en, lv) -> motionEnabled = en; motionLevel = lv.toFloat() }
            humanEnabled = runCatching { c.getHumanDetect() }.getOrNull()
            img = runCatching { c.getImageParam() }.getOrNull()
            runCatching { c.getRecord() }.getOrNull()?.let { (m, pl) -> recordMode = m; packetLen = pl.toFloat() }
            color = runCatching { c.getVideoColor() }.getOrNull()
            runCatching { c.getEncodeMain() }.getOrNull()?.let { (f, b, r) -> encFps = f; encBitrate = b; encRes = r }
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

    fun applyMotion(en: Boolean, level: Int) = withClient { c ->
        val ok = c.setMotion(en, level)
        if (ok) { motionEnabled = en; motionLevel = level.toFloat() }
        status = if (ok) "Детекция движения: ${if (en) "вкл" else "выкл"}, уровень $level" else "Не удалось изменить детекцию"
    }

    fun applyHuman(en: Boolean) = withClient { c ->
        val ok = c.setHumanDetect(en)
        if (ok) humanEnabled = en
        status = if (ok) "Детекция человека: ${if (en) "вкл" else "выкл"}" else "Не удалось изменить"
    }

    fun applyRecord(mode: String, pl: Int) = withClient { c ->
        val ok = c.setRecord(mode, pl)
        if (ok) { recordMode = mode; packetLen = pl.toFloat() }
        status = if (ok) "Запись сохранена" else "Не удалось изменить запись"
    }

    fun applyColor(v: DvripClient.VideoColor) = withClient { c ->
        val ok = c.setVideoColor(v)
        if (ok) color = v
        status = if (ok) "Цвет сохранён" else "Не удалось изменить цвет"
    }

    fun applyEncode(fps: Int, bitrate: Int) = withClient { c ->
        val ok = c.setEncodeMain(fps, bitrate)
        if (ok) { encFps = fps; encBitrate = bitrate }
        status = if (ok) "Качество сохранено: ${fps}fps / ${bitrate}k" else "Не удалось изменить качество"
    }

    fun applyImage(n: DvripClient.ImageParam) = withClient { c ->
        val ok = c.setImageParam(n)
        if (ok) img = n
        status = if (ok) "Изображение сохранено" else "Не удалось изменить изображение"
    }

    fun rebootCam() = withClient { c ->
        val ok = c.reboot()
        status = if (ok) "Команда перезагрузки отправлена — камера уходит в ребут…" else "Не удалось перезагрузить"
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
                    Column(Modifier.weight(1f)) {
                        Text("Детекция движения (по видео)")
                        Text("Уровень: ${motionLevel.toInt()} (1–6)", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = motionEnabled, enabled = loaded && !busy,
                        onCheckedChange = { applyMotion(it, motionLevel.toInt()) })
                }
                Slider(
                    value = motionLevel, onValueChange = { motionLevel = it },
                    onValueChangeFinished = { applyMotion(motionEnabled, motionLevel.toInt()) },
                    valueRange = 1f..6f, steps = 4, enabled = loaded && !busy && motionEnabled,
                )

                humanEnabled?.let { he ->
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Детекция человека (AI)", Modifier.weight(1f))
                        Switch(checked = he, enabled = loaded && !busy, onCheckedChange = { applyHuman(it) })
                    }
                }
            }
        }

        // --- Изображение ---
        Text("Изображение", style = MaterialTheme.typography.titleMedium)
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val p = img
                if (p == null) {
                    Text(
                        if (loaded) "Недоступно на этой камере" else "Загрузка…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    val en = loaded && !busy
                    SwitchRow("Зеркало (по горизонтали)", p.mirror, en) { applyImage(p.copy(mirror = it)) }
                    SwitchRow("Переворот (по вертикали)", p.flip, en) { applyImage(p.copy(flip = it)) }
                    SwitchRow("Компенсация засветки (BLC)", p.blc, en) { applyImage(p.copy(blc = it)) }
                    SwitchRow("Коридорный режим (90°)", p.corridor, en) { applyImage(p.copy(corridor = it)) }
                    SwitchRow("Стабилизация (DIS)", p.dis, en) { applyImage(p.copy(dis = it)) }
                    SwitchRow("Ночной супер-режим (LowLux)", p.lowLux, en) { applyImage(p.copy(lowLux = it)) }
                    HorizontalDivider()
                    Text("Режим день/ночь", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Авто", "Цвет", "Ч/Б").forEachIndexed { i, lbl ->
                            FilterChip(selected = p.dayNight == i, enabled = en,
                                onClick = { applyImage(p.copy(dayNight = i)) }, label = { Text(lbl) })
                        }
                    }
                    Text("Антимерцание", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Выкл", "50 Гц", "60 Гц").forEachIndexed { i, lbl ->
                            FilterChip(selected = p.antiFlicker == i, enabled = en,
                                onClick = { applyImage(p.copy(antiFlicker = i)) }, label = { Text(lbl) })
                        }
                    }
                }
            }
        }

        // --- Цвет изображения ---
        Text("Цвет изображения", style = MaterialTheme.typography.titleMedium)
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val c = color
                val en = loaded && !busy
                if (c == null) {
                    Text(if (loaded) "Недоступно на этой камере" else "Загрузка…", style = MaterialTheme.typography.bodySmall)
                } else {
                    ColorSlider("Яркость", c.brightness, en) { applyColor(c.copy(brightness = it)) }
                    ColorSlider("Контраст", c.contrast, en) { applyColor(c.copy(contrast = it)) }
                    ColorSlider("Насыщенность", c.saturation, en) { applyColor(c.copy(saturation = it)) }
                    ColorSlider("Оттенок", c.hue, en) { applyColor(c.copy(hue = it)) }
                }
            }
        }

        // --- Запись на SD ---
        Text("Запись на SD", style = MaterialTheme.typography.titleMedium)
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val m = recordMode
                val en = loaded && !busy
                if (m == null) {
                    Text(if (loaded) "Недоступно" else "Загрузка…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Режим записи", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Выкл" to "ClosedRecord", "Расписание" to "ConfigRecord", "Всегда" to "ManualRecord")
                            .forEach { (lbl, mode) ->
                                FilterChip(selected = m == mode, enabled = en,
                                    onClick = { applyRecord(mode, packetLen.toInt()) }, label = { Text(lbl) })
                            }
                    }
                    Text("Длина файла: ${packetLen.toInt()} мин", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = packetLen, onValueChange = { packetLen = it },
                        onValueChangeFinished = { applyRecord(m, packetLen.toInt()) },
                        valueRange = 1f..60f, enabled = en,
                    )
                }
            }
        }

        // --- Качество основного потока ---
        Text("Качество${if (encRes.isNotBlank()) " (осн. поток $encRes)" else ""}", style = MaterialTheme.typography.titleMedium)
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val f = encFps
                val en = loaded && !busy
                if (f == null) {
                    Text(if (loaded) "Недоступно" else "Загрузка…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Кадры/с: ${encFps ?: f}", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = (encFps ?: f).toFloat(), onValueChange = { encFps = it.toInt() },
                        onValueChangeFinished = { applyEncode(encFps ?: f, encBitrate) },
                        valueRange = 1f..25f, steps = 23, enabled = en,
                    )
                    Text("Битрейт: $encBitrate кбит/с", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(512, 1024, 2048, 4096).forEach { br ->
                            FilterChip(selected = encBitrate == br, enabled = en,
                                onClick = { applyEncode(encFps ?: f, br) }, label = { Text("$br") })
                        }
                    }
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

        // --- Уведомления о движении ---
        Text("Уведомления", style = MaterialTheme.typography.titleMedium)
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Сторож движения")
                        Text("Фоновая подписка на тревоги камеры + пуш-уведомления",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = watchOn, onCheckedChange = { on -> toggleWatch(on) })
                }
                Text(
                    "Камера батарейная: из глубокого сна по сети её не разбудить, поэтому события " +
                        "приходят, пока она бодрствует (её будит движение/PIR).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // --- Обслуживание ---
        Text("Обслуживание", style = MaterialTheme.typography.titleMedium)
        ElevatedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onEventLog) { Text("Журнал событий камеры") }
                OutlinedButton(onClick = { rebootCam() }, enabled = loaded && !busy) {
                    Text("Перезагрузить камеру")
                }
                Text("Камера уйдёт в ребут на ~30–60 сек и переподключится.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(status, style = MaterialTheme.typography.bodySmall)
        Text(
            "Выключение относится только к облаку. Локальный доступ по DVRIP (просмотр, архив) " +
                "продолжит работать. Чтобы полностью отрезать камеру от интернета — заблокируй её на роутере.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
    }
}

/** Строка «текст + переключатель» для секции изображения. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

/** Слайдер 0..100 с локальным перетаскиванием и применением по отпусканию. */
@Composable
private fun ColorSlider(label: String, value: Int, enabled: Boolean, onApply: (Int) -> Unit) {
    var v by remember(value) { mutableStateOf(value.toFloat()) }
    Text("$label: ${v.toInt()}", style = MaterialTheme.typography.bodySmall)
    Slider(
        value = v, onValueChange = { v = it }, onValueChangeFinished = { onApply(v.toInt()) },
        valueRange = 0f..100f, enabled = enabled,
    )
}
