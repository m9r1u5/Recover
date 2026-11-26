package za.co.mg.recover

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

/** Where a found photo came from. */
enum class Source(val label: String, val blurb: String) {
    TRASH("Recently deleted", "In the phone's bin. Restores to its original album."),
    BINS("Bins & hidden", "Gallery recycle-bin and hidden leftover copies."),
    THUMBS("Thumbnails", "Small preview copies kept by the gallery."),
    MESSAGING("WhatsApp / Telegram", "Copies kept by chat apps."),
    OTHER("Not in gallery", "Photos the gallery doesn't show.")
}

data class Found(
    val key: String,
    val source: Source,
    val name: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val file: File? = null,
    val uri: Uri? = null,
    val expires: Long = 0L
) {
    val model: Any get() = uri ?: file!!
}

object Scanner {
    val imageExts = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "avif", "dng")

    private val skipExts = setOf(
        "mp4", "mkv", "3gp", "mov", "avi", "webm", "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr",
        "apk", "apks", "xapk", "zip", "rar", "7z", "gz", "tar", "pdf", "doc", "docx", "xls", "xlsx",
        "ppt", "pptx", "txt", "csv", "json", "xml", "db", "db-wal", "db-shm", "sqlite", "log", "html",
        "js", "css", "so", "dex", "odex", "vdex", "oat", "obb", "crypt14", "crypt15", "ttf", "otf", "vcf"
    )

    private const val MIN_SIZE = 3 * 1024L
    private const val MAX_CARVE_SIZE = 96L * 1024 * 1024

    fun scanAll(
        ctx: Context,
        fileAccess: Boolean,
        report: (String) -> Unit,
        cancelled: () -> Boolean
    ): List<Found> {
        val out = ArrayList<Found>()
        report("Checking recently deleted…")
        val trash = queryTrash(ctx)
        out += trash
        if (!fileAccess || cancelled()) return out

        report("Reading gallery index…")
        val gallery = queryGalleryPaths(ctx)
        val known = HashSet<String>()
        trash.forEach { known += it.path }

        val carveDir = File(ctx.cacheDir, "carved")
        carveDir.deleteRecursively()
        carveDir.mkdirs()

        walk(Environment.getExternalStorageDirectory(), gallery, known, carveDir, out, report, cancelled)
        return out
    }

    // ---------- 1. System trash (Android 11+) ----------

    @SuppressLint("InlinedApi")
    private fun queryTrash(ctx: Context): List<Found> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val out = ArrayList<Found>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        @Suppress("DEPRECATION")
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DATE_EXPIRES
        )
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        try {
            ctx.contentResolver.query(collection, proj, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "photo_$id.jpg"
                    val path = c.getString(4) ?: ""
                    out += Found(
                        key = "trash:$id",
                        source = Source.TRASH,
                        name = name,
                        path = path,
                        size = c.getLong(2),
                        modified = c.getLong(3) * 1000L,
                        uri = ContentUris.withAppendedId(collection, id),
                        expires = c.getLong(5) * 1000L
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun queryGalleryPaths(ctx: Context): Set<String> {
        val set = HashSet<String>()
        @Suppress("DEPRECATION")
        val proj = arrayOf(MediaStore.MediaColumns.DATA)
        val uris = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )
        for (u in uris) {
            try {
                ctx.contentResolver.query(u, proj, null, null, null)?.use { c ->
                    while (c.moveToNext()) c.getString(0)?.let { set += it }
                }
            } catch (_: Exception) {
            }
        }
        return set
    }

    // ---------- 2. Walk storage for leftover copies ----------

    private fun walk(
        root: File,
        gallery: Set<String>,
        known: Set<String>,
        carveDir: File,
        out: MutableList<Found>,
        report: (String) -> Unit,
        cancelled: () -> Boolean
    ) {
        val rootPath = root.absolutePath
        val blocked = setOf("$rootPath/Android/data", "$rootPath/Android/obb")
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        var count = 0
        val header = ByteArray(16)

        while (stack.isNotEmpty()) {
            if (cancelled()) return
            val dir = stack.removeLast()
            val children: Array<File> = (try { dir.listFiles() } catch (_: Exception) { null }) ?: continue
            for (f in children) {
                if (cancelled()) return
                val abs = f.absolutePath
                if (f.isDirectory) {
                    if (abs !in blocked) stack.addLast(f)
                    continue
                }
                count++
                if (count % 300 == 0) {
                    report("Scanned $count files · found ${out.size}\n${dir.absolutePath.removePrefix(rootPath)}")
                }
                val size = f.length()
                if (size < MIN_SIZE) continue
                if (abs in gallery || abs in known) continue

                val name = f.name
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in skipExts) continue

                val rel = abs.removePrefix(rootPath).lowercase()
                val hidden = rel.split('/').any { it.startsWith(".") }
                val thumbs = rel.contains("/.thumbnails") || name.startsWith(".thumbdata") ||
                        rel.contains("/thumbnails/") || rel.contains("/.thumbs")
                val bins = rel.contains("/.trash") || rel.contains("trashed") ||
                        rel.contains("recycle") || rel.contains("/.deleted")
                val messaging = rel.contains("whatsapp") || rel.contains("telegram")

                val candidate = ext in imageExts || ext.isEmpty() || hidden || thumbs || bins
                if (!candidate) continue

                val kind = kindOf(f, header)
                val source = when {
                    bins -> Source.BINS
                    thumbs -> Source.THUMBS
                    messaging -> Source.MESSAGING
                    hidden -> Source.BINS
                    else -> Source.OTHER
                }

                if (kind != null) {
                    out += Found(
                        key = "file:$abs",
                        source = source,
                        name = name,
                        path = abs,
                        size = size,
                        modified = f.lastModified(),
                        file = f
                    )
                } else if (thumbs && size <= MAX_CARVE_SIZE) {
                    // Old-style thumbnail databases hold many JPEGs glued together.
                    report("Extracting previews from $name…")
                    carveJpegs(f, carveDir).forEachIndexed { i, piece ->
                        out += Found(
                            key = "carved:$abs#$i",
                            source = Source.THUMBS,
                            name = "${name.trimStart('.')}_$i.jpg",
                            path = "$abs (#${i + 1})",
                            size = piece.length(),
                            modified = f.lastModified(),
                            file = piece
                        )
                    }
                }
            }
        }
        report("Scanned $count files · found ${out.size}")
    }

    fun kindOf(f: File, buf: ByteArray = ByteArray(16)): String? {
        val n = try {
            FileInputStream(f).use { it.read(buf, 0, 16) }
        } catch (_: Exception) {
            return null
        }
        return kindOf(buf, n)
    }

    private fun b(c: Char): Byte = c.code.toByte()

    fun kindOf(h: ByteArray, n: Int): String? {
        if (n >= 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte()) return "jpg"
        if (n >= 4 && h[0] == 0x89.toByte() && h[1] == b('P') && h[2] == b('N') && h[3] == b('G')) return "png"
        if (n >= 12 && h[0] == b('R') && h[1] == b('I') && h[2] == b('F') && h[3] == b('F') &&
            h[8] == b('W') && h[9] == b('E') && h[10] == b('B') && h[11] == b('P')
        ) return "webp"
        if (n >= 6 && h[0] == b('G') && h[1] == b('I') && h[2] == b('F') && h[3] == b('8')) return "gif"
        if (n >= 12 && h[4] == b('f') && h[5] == b('t') && h[6] == b('y') && h[7] == b('p')) {
            val brand = String(h, 8, 4, Charsets.US_ASCII)
            if (brand in setOf("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1")) return "heic"
            if (brand == "avif" || brand == "avis") return "avif"
        }
        return null
    }

    // ---------- 3. Pull JPEGs out of thumbnail cache files ----------

    private fun carveJpegs(src: File, outDir: File): List<File> {
        val bytes = try { src.readBytes() } catch (_: Throwable) { return emptyList() }
        val res = ArrayList<File>()
        val n = bytes.size
        val tag = Integer.toHexString(src.absolutePath.hashCode())
        var i = 0
        var idx = 0
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        while (i < n - 3 && res.size < 5000) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte() && bytes[i + 2] == 0xFF.toByte()) {
                var j = i + 3
                var end = -1
                while (j < n - 1) {
                    if (bytes[j] == 0xFF.toByte() && bytes[j + 1] == 0xD9.toByte()) {
                        end = j + 2
                        break
                    }
                    j++
                }
                if (end == -1) break
                val len = end - i
                if (len in 2048..(20 * 1024 * 1024)) {
                    opts.outWidth = 0
                    BitmapFactory.decodeByteArray(bytes, i, len, opts)
                    if (opts.outWidth >= 32 && opts.outHeight >= 32) {
                        val f = File(outDir, "${tag}_$idx.jpg")
                        try {
                            f.outputStream().use { it.write(bytes, i, len) }
                            res += f
                            idx++
                        } catch (_: Exception) {
                        }
                    }
                }
                i = end
            } else {
                i++
            }
        }
        return res
    }
}
