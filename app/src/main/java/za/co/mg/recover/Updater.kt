package za.co.mg.recover

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionName: String, val versionCode: Int, val apkUrl: String, val size: Long)

/** Checks GitHub Releases for a newer APK, downloads it and hands it to Android's installer. */
object Updater {
    private const val API = "https://api.github.com/repos/m9r1u5/Recover/releases/latest"

    /** Returns the newer release, or null if this is already the latest. Throws on network errors. */
    fun check(): UpdateInfo? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Recover-App")
        }
        try {
            if (conn.responseCode != 200) throw Exception("GitHub replied ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").removePrefix("v")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            if (code <= BuildConfig.VERSION_CODE) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return UpdateInfo(tag, code, a.getString("browser_download_url"), a.optLong("size"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    fun apkFile(ctx: Context): File = File(File(ctx.cacheDir, "update").apply { mkdirs() }, "Recover-update.apk")

    fun download(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File {
        val out = apkFile(ctx)
        out.delete()
        val conn = open(info.apkUrl)
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.size
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    private fun open(start: String): HttpURLConnection {
        var url = URL(start)
        repeat(6) {
            val c = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Recover-App")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw Exception("Bad redirect")
                url = URL(url, loc)
            } else if (code == 200) {
                return c
            } else {
                c.disconnect()
                throw Exception("Download failed ($code)")
            }
        }
        throw Exception("Too many redirects")
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Opens the "Allow from this source" screen for Recover. */
    fun askInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
