package com.kwebmn.dvripcam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
                    ConnectScreen()
                }
            }
        }
    }
}

@Composable
private fun ConnectScreen() {
    var host by remember { mutableStateOf("192.168.1.10") }
    var port by remember { mutableStateOf("34567") }
    var user by remember { mutableStateOf("admin") }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Введите данные камеры и нажмите «Подключиться»") }
    var busy by remember { mutableStateOf(false) }

    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("DVRIP Cam", style = MaterialTheme.typography.headlineMedium)
        Text("Локальное подключение к камере (DVRIP, порт 34567)", style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("IP камеры") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Порт") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Логин") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Пароль") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())

        Button(
            onClick = {
                busy = true
                status = "Подключаюсь…"
                activity.lifecycleScope.launch {
                    val client = DvripClient(host.trim(), port.trim().toIntOrNull() ?: 34567)
                    try {
                        client.connect()
                        if (!client.login(user.trim(), password)) {
                            status = "Ошибка логина (неверный пароль?)"
                        } else {
                            val info: JSONObject = client.systemInfo()
                            status = buildString {
                                appendLine("✅ Подключено")
                                appendLine("HardWare: " + info.optString("HardWare", "—"))
                                appendLine("Прошивка: " + info.optString("SoftWareVersion", "—"))
                                appendLine("Serial: " + info.optString("SerialNo", "—"))
                                appendLine("Сборка: " + info.optString("BuildTime", "—"))
                            }
                        }
                    } catch (e: Exception) {
                        status = "Не удалось: ${e.message}\n(камера спит? разбуди движением)"
                    } finally {
                        client.close()
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "…" else "Подключиться") }

        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Text(status, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
