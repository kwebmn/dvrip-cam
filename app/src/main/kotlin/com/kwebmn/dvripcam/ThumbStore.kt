package com.kwebmn.dvripcam

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import com.kwebmn.dvripcam.dvrip.DvripClient
import com.kwebmn.dvripcam.dvrip.RecordingFile
import com.kwebmn.dvripcam.video.KeyFrameGrabber
import com.kwebmn.dvripcam.video.NalExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.net.SocketFactory

/**
 * Миниатюры записей. Камера OPCompressPic не поддерживает (Ret 109), поэтому превью
 * делаем сами: коротко открываем плейбэк, ловим первый I-кадр, кладём JPEG в кэш.
 *
 * Генерация строго последовательная (Mutex) — камера батарейная, параллельные
 * подключения ей вредны. Готовые файлы переживают перезапуск приложения.
 */
class ThumbStore(
    ctx: Context,
    private val host: String,
    private val port: Int,
    private val user: String,
    private val pass: String,
) {
    private val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
    private val mutex = Mutex()

    /** Готовые миниатюры: имя файла записи -> путь к JPEG. */
    val ready = mutableStateMapOf<String, String>()

    /** Записи, для которых превью получить не удалось — чтобы не долбить повторно. */
    private val failed = HashSet<String>()

    private fun fileFor(name: String): File {
        val key = name.replace(Regex("[^A-Za-z0-9]"), "_").takeLast(90)
        return File(dir, "$key.jpg")
    }

    suspend fun request(f: RecordingFile, wifiSf: SocketFactory?) {
        if (failed.contains(f.name) || ready.containsKey(f.name)) return
        val out = fileFor(f.name)
        if (out.exists() && out.length() > 0) { ready[f.name] = out.absolutePath; return }
        mutex.withLock {
            if (failed.contains(f.name) || ready.containsKey(f.name)) return
            if (out.exists() && out.length() > 0) { ready[f.name] = out.absolutePath; return }
            if (generate(f, out, wifiSf)) ready[f.name] = out.absolutePath else failed.add(f.name)
        }
    }

    private suspend fun generate(f: RecordingFile, out: File, wifiSf: SocketFactory?): Boolean =
        withContext(Dispatchers.IO) {
            val c = DvripClient(host, port)
            val grab = KeyFrameGrabber()
            try {
                c.connect(4000, wifiSf)
                if (!c.login(user, pass)) return@withContext false
                val ex = NalExtractor { nal -> grab.submit(nal) }
                c.playbackStart(f.begin, f.end)
                val deadline = System.currentTimeMillis() + 8000
                while (!grab.isDone && System.currentTimeMillis() < deadline) {
                    val (_, body) = c.readRawPacket()
                    if (body.isEmpty()) break
                    if (body[0] == '{'.code.toByte()) continue
                    ex.feed(body)
                }
                grab.saveTo(out)
            } catch (e: Exception) {
                false
            } finally {
                grab.release()
                runCatching { c.close() }
            }
        }
}
