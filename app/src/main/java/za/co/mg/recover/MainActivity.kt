package za.co.mg.recover

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.util.Date

const val REPO_URL = "https://github.com/m9r1u5/Recover"

class MainActivity : ComponentActivity() {
    private val vm: RecoverViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RecoverTheme { RecoverScreen(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshPermissions()
        // Coming back from "Install unknown apps" settings: carry on with the update.
        if (Updater.canInstall(this)) vm.installPending(this)
    }
}

@Composable
fun RecoverTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF90CAF9))
        else -> lightColorScheme(primary = Color(0xFF1565C0))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(ctx, "No browser found", Toast.LENGTH_SHORT).show()
    }
}

private fun openApp(ctx: Context, pkg: String, missing: String) {
    val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
    if (i != null) ctx.startActivity(i) else Toast.makeText(ctx, missing, Toast.LENGTH_LONG).show()
}

private fun fmtDate(ctx: Context, ms: Long): String =
    if (ms <= 0) "unknown" else DateFormat.getMediumDateFormat(ctx).format(Date(ms)) + " " +
            DateFormat.getTimeFormat(ctx).format(Date(ms))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoverScreen(vm: RecoverViewModel) {
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var preview by remember { mutableStateOf<Found?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.refreshPermissions() }

    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r -> vm.onTrashResult(r.resultCode == Activity.RESULT_OK) }

    fun doRestore(items: List<Found>) {
        val sender = vm.restore(items)
        if (sender != null) trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    fun askAllFiles() {
        val i = Perms.allFilesIntent(ctx)
        if (i != null) {
            try {
                ctx.startActivity(i)
            } catch (_: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            permLauncher.launch(Perms.photoPermissions())
        }
    }

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            snackbar.showSnackbar(m)
            vm.message = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Recover", fontWeight = FontWeight.Bold)
                        Text(
                            "Find deleted photos · no root · v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Select all shown") }, onClick = {
                            menuOpen = false; vm.selectAllShown()
                        })
                        DropdownMenuItem(text = { Text("Clear selection") }, onClick = {
                            menuOpen = false; vm.clearSelection()
                        })
                        DropdownMenuItem(text = { Text("How it works") }, onClick = {
                            menuOpen = false; showHelp = true
                        })
                        DropdownMenuItem(text = { Text("Check for update") }, onClick = {
                            menuOpen = false; vm.checkUpdate(manual = true)
                        })
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (vm.selected.isNotEmpty()) {
                BottomAppBar {
                    Text(
                        "${vm.selected.size} selected",
                        modifier = Modifier.padding(start = 16.dp).weight(1f)
                    )
                    TextButton(onClick = { vm.clearSelection() }) { Text("Clear") }
                    Button(
                        enabled = !vm.restoring,
                        onClick = { doRestore(vm.results.filter { it.key in vm.selected }) },
                        modifier = Modifier.padding(end = 12.dp)
                    ) { Text(if (vm.restoring) "Saving…" else "Restore") }
                }
            }
        }
    ) { pad ->
        val shown = vm.shown()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Header(vm, onAllFiles = { askAllFiles() }, onPhotos = {
                    permLauncher.launch(Perms.photoPermissions())
                })
            }
            items(shown, key = { it.key }) { f ->
                Thumb(
                    f = f,
                    selected = f.key in vm.selected,
                    onTap = { vm.toggle(f.key) },
                    onLong = { preview = f }
                )
            }
            if (vm.scannedOnce && !vm.scanning && shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "Nothing found here. Check the cloud bins below — Google Photos and Samsung Cloud keep deleted photos for 30–60 days.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { CloudCard() }
        }
    }

    preview?.let { f ->
        PreviewDialog(
            f = f,
            onDismiss = { preview = null },
            onRestore = {
                preview = null
                doRestore(listOf(f))
            }
        )
    }

    vm.update?.let { info ->
        val p = vm.updateProgress
        AlertDialog(
            onDismissRequest = { if (p == null) vm.update = null },
            title = { Text("Update available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Version ${info.versionName} is ready (you have ${BuildConfig.VERSION_NAME}).")
                    if (p != null) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                        Text("Downloading… ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(enabled = p == null, onClick = { vm.downloadAndInstall(ctx) }) { Text("Update now") }
            },
            dismissButton = {
                if (p == null) TextButton(onClick = { vm.update = null }) { Text("Later") }
            }
        )
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("OK") } },
            title = { Text("How Recover works") },
            text = {
                Text(
                    "Without root, no app can read the raw phone storage, and modern phones wipe deleted data quickly. " +
                            "Recover finds copies that are still there:\n\n" +
                            "• Phone bin – the phone's own bin (Android 11+, ~30 days). Restores to the original album.\n" +
                            "• Recycle bins – leftovers in gallery recycle-bin folders.\n" +
                            "• Gallery thumbnails – small preview copies the gallery kept. Lower quality, but often all that's left.\n" +
                            "• WhatsApp / Telegram – pictures chat apps kept that aren't in your gallery.\n" +
                            "• Photo folders – files in DCIM, Pictures or Download the gallery doesn't show.\n\n" +
                            "Pictures that match a photo still in your gallery, and images other apps downloaded (song covers, video thumbnails), are not counted as deleted. Tap \"Everything\" to see them. Each picture shows the folder or app it was found in.\n\n" +
                            "Tap to select, long-press to preview. Restored files go to Pictures/Recovered.\n\n" +
                            "Tip: scan as soon as possible after deleting, and don't save new files in the meantime."
                )
            }
        )
    }
}

@Composable
private fun Header(vm: RecoverViewModel, onAllFiles: () -> Unit, onPhotos: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        if (!vm.hasAllFiles) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Give Recover access", fontWeight = FontWeight.Bold)
                    Text(
                        "\"All files access\" lets Recover look in hidden folders, bins, thumbnail caches and chat-app folders. " +
                                "Nothing leaves your phone – internet is only used to check for app updates.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onAllFiles) { Text("Allow all files") }
                        if (!vm.hasPhotoAccess) OutlinedButton(onClick = onPhotos) { Text("Photos only") }
                    }
                }
            }
        }

        if (vm.scanning) {
            Card {
                Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Scanning…", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.cancelScan() }) { Text("Stop") }
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        vm.progress,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Button(
                onClick = { vm.startScan() },
                enabled = vm.hasPhotoAccess || vm.hasAllFiles,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            ) {
                Text(if (vm.scannedOnce) "Scan again" else "Scan for deleted photos", fontSize = 17.sp)
            }
        }

        if (vm.scannedOnce && !vm.scanning) {
            val bin = vm.results.count { it.source == Source.TRASH }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (vm.deletedCount > 0) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (vm.deletedCount > 0) "${vm.deletedCount} possible deleted photos"
                        else "No deleted photos left on this phone",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Phone bin: $bin · Copies of gallery photos: ${vm.inGalleryCount} · App images: ${vm.appCacheCount} (not counted)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (vm.deletedCount == 0) {
                        Text(
                            "Your phone has already wiped them. Check the cloud bins at the bottom – that's the best chance now.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (vm.results.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = vm.deletedOnly,
                    onClick = { vm.deletedOnly = true; vm.filter = null },
                    label = { Text("Deleted only ${vm.deletedCount}") }
                )
                FilterChip(
                    selected = !vm.deletedOnly,
                    onClick = { vm.deletedOnly = false; vm.filter = null },
                    label = { Text("Everything ${vm.results.size}") }
                )
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = vm.filter == null,
                    onClick = { vm.filter = null },
                    label = { Text("All ${vm.visible().size}") }
                )
                Source.values().forEach { s ->
                    val c = vm.count(s)
                    if (c > 0) {
                        FilterChip(
                            selected = vm.filter == s,
                            onClick = { vm.filter = if (vm.filter == s) null else s },
                            label = { Text("${s.label} $c") }
                        )
                    }
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Sort:", style = MaterialTheme.typography.labelMedium)
                SortBy.values().forEach { s ->
                    FilterChip(
                        selected = vm.sortBy == s,
                        onClick = { vm.sortBy = s },
                        label = { Text(s.label, maxLines = 1) }
                    )
                }
            }
            vm.filter?.let {
                Text(it.blurb, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "Tap to select · long-press to preview",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Thumb(f: Found, selected: Boolean, onTap: () -> Unit, onLong: () -> Unit) {
    val ctx = LocalContext.current
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onTap, onLongClick = onLong)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(f.model).size(300).crossfade(false).build(),
            contentDescription = f.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        val badge = when (f.source) {
            Source.TRASH -> {
                val days = if (f.expires > 0) ((f.expires - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0) else -1
                if (days >= 0) "Bin · ${days}d left" else "Bin"
            }
            else -> f.place.ifBlank { f.source.label }
        }
        Text(
            if (f.copies > 1) "$badge ×${f.copies}" else badge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White,
            fontSize = 10.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0x99000000))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
        if (selected) {
            Box(Modifier.fillMaxSize().background(Color(0x552962FF)))
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp)
            )
        }
    }
}

@Composable
private fun PreviewDialog(f: Found, onDismiss: () -> Unit, onRestore: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(f.model).size(1600).build(),
                    contentDescription = f.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                )
                Text(f.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${f.source.label} · ${f.place} · ${Formatter.formatShortFileSize(ctx, f.size)}", style = MaterialTheme.typography.bodySmall)
                Text(
                    if (f.inGallery) "A copy of a photo that is still in your gallery"
                    else if (!f.source.personal) "An image another app downloaded (${f.place}) – not one of your photos"
                    else if (f.copies > 1) "Not in your gallery any more · ${f.copies} copies found (showing the biggest)"
                    else "Not in your gallery any more",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Text("Date: ${fmtDate(ctx, f.modified)}", style = MaterialTheme.typography.bodySmall)
                if (f.source == Source.TRASH && f.expires > 0) {
                    Text("Deleted for good on: ${fmtDate(ctx, f.expires)}", style = MaterialTheme.typography.bodySmall)
                }
                Text(f.path, style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Button(onClick = onRestore) { Text("Restore") }
                }
            }
        }
    }
}

@Composable
private fun CloudCard() {
    val ctx = LocalContext.current
    Card(Modifier.padding(top = 14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Also check your cloud bins", fontWeight = FontWeight.Bold)
            Text(
                "If backup was on, deleted photos sit in the cloud bin for 30–60 days, at full quality.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = { openUrl(ctx, "https://photos.google.com/trash") }, modifier = Modifier.fillMaxWidth()) {
                Text("Google Photos bin")
            }
            OutlinedButton(onClick = {
                openApp(ctx, "com.sec.android.gallery3d", "Samsung Gallery not found on this phone")
                Toast.makeText(ctx, "In Gallery: ☰ Menu ▸ Recycle bin", Toast.LENGTH_LONG).show()
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Samsung Gallery recycle bin")
            }
        }
    }
}
