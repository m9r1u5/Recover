package za.co.mg.recover

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.IntStream

/**
 * Works out which found pictures are copies of photos STILL in the gallery (so not deleted).
 * Uses a 64-bit "difference hash" of a centre-square crop, so a small cached thumbnail
 * matches its full-size original even if it was resized, cropped square or rotated.
 */
object Matcher {
    private const val MAX_DIST = 7

    // ---------- hashing ----------

    private fun grid(input: Bitmap): IntArray? = try {
        gridUnsafe(input)
    } catch (_: Throwable) {
        null
    }

    private fun gridUnsafe(input: Bitmap): IntArray? {
        val src = if (Build.VERSION.SDK_INT >= 26 && input.config == Bitmap.Config.HARDWARE)
            input.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        else input
        val s = minOf(src.width, src.height)
        if (s < 9) return null
        val sq = Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
        val small = Bitmap.createScaledBitmap(sq, 9, 9, true)
        val g = IntArray(81)
        for (y in 0 until 9) for (x in 0 until 9) {
            val p = small.getPixel(x, y)
            g[y * 9 + x] = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114)
        }
        if (small !== sq) small.recycle()
        if (sq !== src) sq.recycle()
        if (src !== input) src.recycle()
        return g
    }

    private fun rotate(g: IntArray): IntArray {
        val r = IntArray(81)
        for (y in 0 until 9) for (x in 0 until 9) r[y * 9 + x] = g[(8 - x) * 9 + y]
        return r
    }

    private fun dhash(g: IntArray): Long {
        var h = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (g[y * 9 + x] > g[y * 9 + x + 1]) h = h or (1L shl bit)
            bit++
        }
        return h
    }

    /** Hashes for 0°, 90°, 180°, 270°. */
    private fun hashes4(b: Bitmap): LongArray? {
        var g = grid(b) ?: return null
        val out = LongArray(4)
        for (i in 0 until 4) {
            out[i] = dhash(g)
            g = rotate(g)
        }
        return out
    }

    private fun decodeSmall(f: File): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else {
            var sample = 1
            while (minOf(o.outWidth, o.outHeight) / (sample * 2) >= 48) sample *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Throwable) {
        null
    }

    // ---------- fast lookup (8 byte-buckets; a match within 7 bits shares at least one byte) ----------

    private class Index {
        private val buckets = Array(8) { HashMap<Int, MutableList<Int>>() }
        private val hashes = ArrayList<Long>()

        fun add(h: Long): Int {
            val id = hashes.size
            hashes += h
            for (b in 0 until 8) {
                val key = ((h ushr (b * 8)) and 0xFF).toInt()
                buckets[b].getOrPut(key) { ArrayList(4) }.add(id)
            }
            return id
        }

        fun find(h: Long): Int {
            for (b in 0 until 8) {
                val key = ((h ushr (b * 8)) and 0xFF).toInt()
                val list = buckets[b][key] ?: continue
                for (id in list) {
                    if (java.lang.Long.bitCount(hashes[id] xor h) <= MAX_DIST) return id
                }
            }
            return -1
        }

        val size get() = hashes.size
    }

    // ---------- gallery hashes (cached between scans) ----------

    private fun galleryHashes(ctx: Context, report: (String) -> Unit, cancelled: () -> Boolean): Index {
        val cacheFile = File(ctx.filesDir, "gallery_hashes.txt")
        val cache = HashMap<String, Long>()
        try {
            cacheFile.forEachLine { line ->
                val i = line.lastIndexOf('=')
                if (i > 0) line.substring(i + 1).toLongOrNull()?.let { cache[line.substring(0, i)] = it }
            }
        } catch (_: Exception) {
        }

        data class Item(val key: String, val uri: Uri, val id: Long, val video: Boolean)
        val items = ArrayList<Item>()
        val cols = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true
        )
        for ((col, video) in cols) {
            try {
                ctx.contentResolver.query(
                    col,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_MODIFIED),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val k = (if (video) "v" else "i") + "$id:${c.getLong(1)}"
                        items += Item(k, ContentUris.withAppendedId(col, id), id, video)
                    }
                }
            } catch (_: Exception) {
            }
        }

        val result = LongArray(items.size)
        val have = BooleanArray(items.size)
        val done = AtomicInteger()
        IntStream.range(0, items.size).parallel().forEach { i ->
            if (cancelled()) return@forEach
            val item = items[i]
            val cached = cache[item.key]
            if (cached != null) {
                result[i] = cached; have[i] = true
            } else {
                val bmp = loadThumb(ctx, item.uri, item.id, item.video)
                if (bmp != null) {
                    grid(bmp)?.let { g -> result[i] = dhash(g); have[i] = true }
                    bmp.recycle()
                }
            }
            val n = done.incrementAndGet()
            if (n % 100 == 0) report("Reading your gallery… $n of ${items.size}")
        }

        val index = Index()
        val sb = StringBuilder()
        for (i in items.indices) if (have[i]) {
            index.add(result[i])
            sb.append(items[i].key).append('=').append(result[i]).append('\n')
        }
        try { cacheFile.writeText(sb.toString()) } catch (_: Exception) { }
        return index
    }

    @Suppress("DEPRECATION")
    private fun loadThumb(ctx: Context, uri: Uri, id: Long, video: Boolean): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) {
            ctx.contentResolver.loadThumbnail(uri, Size(96, 96), null)
        } else if (video) {
            MediaStore.Video.Thumbnails.getThumbnail(ctx.contentResolver, id, MediaStore.Video.Thumbnails.MICRO_KIND, null)
        } else {
            MediaStore.Images.Thumbnails.getThumbnail(ctx.contentResolver, id, MediaStore.Images.Thumbnails.MICRO_KIND, null)
        }
    } catch (_: Throwable) {
        null
    }

    // ---------- main entry ----------

    /**
     * Marks items that match a photo still in the gallery, and groups duplicate copies of the
     * same deleted photo (keeps the biggest, notes how many copies).
     */
    fun classify(
        ctx: Context,
        found: List<Found>,
        report: (String) -> Unit,
        cancelled: () -> Boolean
    ): List<Found> {
        val toCheck = found.filter { it.source != Source.TRASH && it.file != null }
        if (toCheck.isEmpty()) return found

        val gallery = galleryHashes(ctx, report, cancelled)
        if (cancelled()) return found

        val hashes = arrayOfNulls<LongArray>(toCheck.size)
        val done = AtomicInteger()
        IntStream.range(0, toCheck.size).parallel().forEach { i ->
            if (cancelled()) return@forEach
            val bmp = decodeSmall(toCheck[i].file!!)
            if (bmp != null) {
                hashes[i] = hashes4(bmp)
                bmp.recycle()
            }
            val n = done.incrementAndGet()
            if (n % 100 == 0) report("Comparing with your gallery… $n of ${toCheck.size}")
        }
        if (cancelled()) return found

        report("Sorting out deleted photos…")
        val inGallery = BooleanArray(toCheck.size)
        for (i in toCheck.indices) {
            val h = hashes[i] ?: continue
            if (gallery.size > 0 && h.any { gallery.find(it) >= 0 }) inGallery[i] = true
        }

        // Group copies of the same deleted photo: biggest file first wins.
        val order = toCheck.indices.filter { !inGallery[it] }.sortedByDescending { toCheck[it].size }
        val dupIndex = Index()
        val owner = ArrayList<Int>()          // dupIndex id -> toCheck index
        val copies = HashMap<Int, Int>()      // keeper index -> number of copies
        val dropped = BooleanArray(toCheck.size)
        for (i in order) {
            val h = hashes[i]
            if (h == null) continue
            var hit = -1
            for (r in h) {
                hit = dupIndex.find(r)
                if (hit >= 0) break
            }
            if (hit >= 0) {
                val keeper = owner[hit]
                dropped[i] = true
                copies[keeper] = (copies[keeper] ?: 1) + 1
            } else {
                dupIndex.add(h[0])
                owner += i
            }
        }

        val checked = HashMap<String, Int>()
        toCheck.forEachIndexed { i, f -> checked[f.key] = i }
        val out = ArrayList<Found>(found.size)
        for (f in found) {
            val i = checked[f.key]
            if (i == null) {
                out += f
            } else if (!dropped[i]) {
                out += f.copy(inGallery = inGallery[i], copies = copies[i] ?: 1)
            }
        }
        return out
    }
}
