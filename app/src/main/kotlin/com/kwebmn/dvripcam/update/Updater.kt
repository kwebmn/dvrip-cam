package com.kwebmn.dvripcam.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.kwebmn.dvripcam.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tag: String,
    val versionName: String,
    val changelog: String,
    val apkUrl: String,
    val apkName: String,
)

object Updater {

    /** Проверить последний релиз в GitHub Releases. Возвращает null, если релизов нет / ошибка. */
    suspend fun latestRelease(repo: String = BuildConfig.GITHUB_REPO): ReleaseInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection()
                        as HttpURLConnection)
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "dvrip-cam")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode != 200) return@runCatching null
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val tag = json.optString("tag_name")
                val body = json.optString("body", "")
                val assets: JSONArray = json.optJSONArray("assets") ?: JSONArray()
                var apkUrl = ""; var apkName = ""
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url"); apkName = name; break
                    }
                }
                if (tag.isEmpty() || apkUrl.isEmpty()) null
                else ReleaseInfo(tag, tag.removePrefix("v"), body, apkUrl, apkName)
            }.getOrNull()
        }

    /** true, если latest строго новее текущей версии (сравнение semver a.b.c). */
    fun isNewer(latestVersionName: String, current: String = BuildConfig.VERSION_NAME): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").split(".", "-")
            .map { it.toIntOrNull() ?: 0 }
        val a = parts(latestVersionName); val b = parts(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Скачать APK во внутренний кеш. onProgress: 0..100 (или -1 если размер неизвестен). */
    suspend fun downloadApk(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, if (fileName.isNotBlank()) fileName else "update.apk")
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.setRequestProperty("User-Agent", "dvrip-cam")
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 10000
        conn.readTimeout = 30000
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            out.outputStream().use { fos ->
                val buf = ByteArray(64 * 1024)
                var read: Int; var done = 0L
                while (input.read(buf).also { read = it } >= 0) {
                    fos.write(buf, 0, read); done += read
                    onProgress(if (total > 0) ((done * 100) / total).toInt() else -1)
                }
            }
        }
        out
    }

    /** Есть ли разрешение ставить APK; если нет — открывает системные настройки. */
    fun ensureInstallPermission(context: Context): Boolean {
        if (context.packageManager.canRequestPackageInstalls()) return true
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        return false
    }

    /** Запустить системную установку скачанного APK через FileProvider. */
    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
