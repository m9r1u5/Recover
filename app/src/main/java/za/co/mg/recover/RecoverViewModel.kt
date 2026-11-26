package za.co.mg.recover

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortBy(val label: String) { NEWEST("Newest"), LARGEST("Largest"), SOURCE("Source") }

object Perms {
    fun photoPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    }

    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasAllFiles(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasPhotos(ctx: Context): Boolean =
        hasAllFiles(ctx) || photoPermissions().all { granted(ctx, it) }

    fun allFilesIntent(ctx: Context): Intent? =
        if (Build.VERSION.SDK_INT >= 30)
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            )
        else null
}

class RecoverViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var pendingTrash: List<Found> = emptyList()

    var scanning by mutableStateOf(false)
        private set
    var scannedOnce by mutableStateOf(false)
        private set
    var restoring by mutableStateOf(false)
        private set
    var progress by mutableStateOf("")
    var results by mutableStateOf<List<Found>>(emptyList())
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
    var filter by mutableStateOf<Source?>(null)
    var sortBy by mutableStateOf(SortBy.NEWEST)
    var hasPhotoAccess by mutableStateOf(false)
        private set
    var hasAllFiles by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    init { refreshPermissions() }

    fun refreshPermissions() {
        hasPhotoAccess = Perms.hasPhotos(ctx)
        hasAllFiles = Perms.hasAllFiles(ctx)
    }

    fun shown(): List<Found> {
        val list = results.filter { filter == null || it.source == filter }
        return when (sortBy) {
            SortBy.NEWEST -> list.sortedByDescending { it.modified }
            SortBy.LARGEST -> list.sortedByDescending { it.size }
            SortBy.SOURCE -> list.sortedWith(compareBy<Found> { it.source.ordinal }.thenByDescending { it.modified })
        }
    }

    fun count(s: Source) = results.count { it.source == s }

    fun startScan() {
        if (scanning) return
        refreshPermissions()
        val fileAccess = hasAllFiles
        job = viewModelScope.launch {
            scanning = true
            selected = emptySet()
            results = emptyList()
            progress = "Starting…"
            val found = withContext(Dispatchers.IO) {
                val scope = this
                Scanner.scanAll(
                    ctx,
                    fileAccess,
                    report = { msg -> main.post { progress = msg } },
                    cancelled = { !scope.isActive }
                )
            }
            results = found
            scanning = false
            scannedOnce = true
            message = if (found.isEmpty()) "No deleted photos found" else "Found ${found.size} photos"
        }
    }

    fun cancelScan() {
        job?.cancel()
        scanning = false
        progress = ""
    }

    fun toggle(key: String) {
        selected = if (key in selected) selected - key else selected + key
    }

    fun selectAllShown() { selected = selected + shown().map { it.key } }
    fun clearSelection() { selected = emptySet() }

    /**
     * Saves file-based items to Pictures/Recovered. For items in the system trash it returns
     * an IntentSender: Android shows its own "Restore?" dialog, then puts them back where they were.
     */
    fun restore(items: List<Found>): IntentSender? {
        val trash = items.filter { it.source == Source.TRASH && it.uri != null }
        val files = items.filter { it.source != Source.TRASH }

        if (files.isNotEmpty()) {
            restoring = true
            viewModelScope.launch {
                val ok = withContext(Dispatchers.IO) { files.count { Restorer.restoreFile(ctx, it) } }
                restoring = false
                selected = selected - files.map { it.key }.toSet()
                message = "Saved $ok of ${files.size} photos to Pictures/${Restorer.FOLDER}"
            }
        }

        if (trash.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
            pendingTrash = trash
            return try {
                MediaStore.createTrashRequest(ctx.contentResolver, trash.map { it.uri!! }, false).intentSender
            } catch (e: Exception) {
                message = "Couldn't restore from trash: ${e.message}"
                null
            }
        }
        return null
    }

    fun onTrashResult(ok: Boolean) {
        val keys = pendingTrash.map { it.key }.toSet()
        if (ok) {
            results = results.filterNot { it.key in keys }
            selected = selected - keys
            message = "Restored ${keys.size} photos back to your gallery"
        } else {
            message = "Restore from trash was cancelled"
        }
        pendingTrash = emptyList()
    }
}
