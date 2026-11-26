package za.co.mg.recover

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

object Restorer {
    const val FOLDER = "Recovered"

    /** Copies a found file into Pictures/Recovered so it shows in the gallery again. */
    fun restoreFile(ctx: Context, item: Found): Boolean {
        val src = item.file ?: return false
        if (!src.exists()) return false
        val kind = Scanner.kindOf(src) ?: "jpg"
        val name = cleanName(item.name, kind)
        val mime = when (kind) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "avif" -> "image/avif"
            else -> "image/jpeg"
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val target = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = try { resolver.insert(target, values) } catch (_: Exception) { null } ?: return false
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("no stream")
                out.use { o -> src.inputStream().use { it.copyTo(o) } }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                true
            } catch (_: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) { }
                false
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
            dir.mkdirs()
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.')
            var dest = File(dir, name)
            var k = 1
            while (dest.exists()) {
                dest = File(dir, "${base}_$k.$ext")
                k++
            }
            try {
                src.copyTo(dest)
                MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun cleanName(raw: String, kind: String): String {
        var n = raw.substringAfterLast('/')
        n = n.replace(Regex("^\\.trashed-\\d+-"), "")
        n = n.trimStart('.')
        if (n.isBlank()) n = "recovered_${System.currentTimeMillis()}"
        val ext = n.substringAfterLast('.', "").lowercase()
        if (ext !in Scanner.imageExts) n = "$n.$kind"
        return n
    }
}
