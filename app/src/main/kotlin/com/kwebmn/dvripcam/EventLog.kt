package com.kwebmn.dvripcam

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kwebmn.dvripcam.dvrip.DvripClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Человеческое название типа события камеры. */
private fun logTitle(type: String): String = when (type) {
    "LogIn" -> "Вход"
    "LogOut" -> "Выход"
    "Reboot" -> "Перезагрузка"
    "PowerOn" -> "Включение"
    "ShutDown" -> "Выключение"
    "SetTime" -> "Смена времени"
    "SaveConfig", "ConfigChange" -> "Изменение настроек"
    "MotionDetect" -> "Движение"
    "PIRAlarm" -> "PIR-тревога"
    "HumanDetect" -> "Обнаружен человек"
    "VideoBlind" -> "Камера перекрыта"
    "StorageNotExist" -> "Нет SD-карты"
    "StorageLowSpace" -> "Мало места на SD"
    "StorageFailure" -> "Сбой SD"
    "DelFile" -> "Удаление файла"
    "Upgrade" -> "Обновление прошивки"
    else -> type
}

private fun logIcon(type: String): String = when {
    type.contains("Motion", true) || type.contains("PIR", true) || type.contains("Human", true) -> "🏃"
    type.contains("Storage", true) -> "💾"
    type.contains("Log", true) -> "🔑"
    type.contains("Reboot", true) || type.contains("Power", true) || type.contains("Shut", true) -> "⚡"
    type.contains("Config", true) || type.contains("Time", true) -> "⚙"
    type.contains("Upgrade", true) -> "⬆"
    else -> "•"
}

/** Журнал событий камеры (OPLogQuery, Type=LogAll — проверено на камере). */
@Composable
fun EventLogScreen(host: String, port: Int, user: String, pass: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val wifiSf = remember { wifiSocketFactory(ctx) }
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }

    var days by remember { mutableStateOf(7) }
    val entries = remember { mutableStateListOf<DvripClient.LogEntry>() }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var nextPos by remember { mutableStateOf(0) }
    var done by remember { mutableStateOf(false) }

    fun load(reset: Boolean) {
        if (loading) return
        loading = true
        if (reset) { entries.clear(); nextPos = 0; done = false }
        status = "Загружаю…"
        scope.launch(Dispatchers.IO) {
            val c = DvripClient(host, port)
            try {
                if (!c.connectAwait(user, pass, wifiSf) { status = it }) return@launch
                val begin = today.minusDays(days.toLong() - 1).toString() + " 00:00:00"
                val end = today.toString() + " 23:59:59"
                val batch = c.queryLog(begin, end, nextPos)
                if (batch.isEmpty()) {
                    done = true
                } else {
                    entries.addAll(batch)
                    nextPos = batch.maxOf { it.position } + 1
                }
                status = "Событий: ${entries.size}" + if (done) " (все)" else ""
            } catch (e: Exception) {
                status = "Ошибка: ${e.message}"
            } finally {
                runCatching { c.close() }; loading = false
            }
        }
    }

    LaunchedEffect(days) { load(reset = true) }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("← Назад") }
            Text("Журнал событий", style = MaterialTheme.typography.titleLarge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(days == 1, { days = 1 }, { Text("Сегодня") })
            FilterChip(days == 7, { days = 7 }, { Text("7 дней") })
            FilterChip(days == 30, { days = 30 }, { Text("30 дней") })
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (loading && entries.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(entries) { e ->
                ListItem(
                    leadingContent = { Text(logIcon(e.type)) },
                    headlineContent = { Text(logTitle(e.type)) },
                    supportingContent = {
                        val extra = listOfNotNull(
                            e.user.takeIf { it.isNotBlank() },
                            e.data.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        Text(if (extra.isBlank()) e.time else "${e.time} · $extra")
                    },
                )
                HorizontalDivider()
            }
            item {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> CircularProgressIndicator()
                        done -> Text("Это весь журнал за период", style = MaterialTheme.typography.bodySmall)
                        else -> TextButton(onClick = { load(reset = false) }) { Text("Загрузить ещё") }
                    }
                }
            }
        }
    }
}
