package com.lita.app.ui.screens

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.lita.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.lita.app.LitaViewModel
import com.lita.app.PickerMode
import com.lita.app.data.AllFiles
import com.lita.app.data.FileVideos
import com.lita.app.ui.CenterMessage
import com.lita.app.ui.formatBytes
import com.lita.app.ui.theme.LitaSurfaceHigh
import com.lita.app.ui.theme.LitaTextMid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun storageRoots(ctx: android.content.Context): List<Pair<String, File>> {
    val roots = ArrayList<Pair<String, File>>()
    roots.add("Internal storage" to Environment.getExternalStorageDirectory())
    ctx.getExternalFilesDirs(null).drop(1).forEach { d ->
        val p = d?.absolutePath?.substringBefore("/Android/")
        if (p != null && File(p).canRead()) roots.add("SD card (${File(p).name})" to File(p))
    }
    return roots
}

@Composable
private fun FileThumb(file: File, modifier: Modifier) {
    val bmp by produceState<ImageBitmap?>(null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            runCatching<Bitmap?> {
                if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createVideoThumbnail(file, Size(240, 240), null)
                else @Suppress("DEPRECATION") ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Images.Thumbnails.MINI_KIND)
            }.getOrNull()?.asImageBitmap()
        }
    }
    Box(modifier.background(LitaSurfaceHigh)) { bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
}

/**
 * Lita's own file browser. [folderMode] = choose a folder for the vault (no files shown);
 * otherwise pick videos and folders to import. Needs Android's "All files access".
 */
@Composable
fun FileBrowserScreen(vm: LitaViewModel, folderMode: Boolean) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(AllFiles.granted(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = AllFiles.granted(ctx) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = AllFiles.granted(ctx) }
    val roots = remember { storageRoots(ctx) }
    var dir by remember { mutableStateOf<File?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var newFolder by remember { mutableStateOf(false) }

    fun close() { vm.pickerMode.value = null }
    fun up() { val d = dir; if (d == null) close() else if (roots.any { it.second == d }) dir = null else dir = d.parentFile }
    BackHandler { up() }

    // null = still loading (shows a spinner); an empty list means the folder really has nothing to show
    val entriesState by produceState<List<File>?>(null, dir, refreshKey, granted) {
        value = null
        val d = dir
        value = if (d == null || !granted) emptyList() else withContext(Dispatchers.IO) {
            (d.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") && (it.isDirectory || (!folderMode && FileVideos.isVideoName(it.name))) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { up() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(
                    dir?.name?.ifEmpty { "Storage" } ?: if (folderMode) "Choose vault folder" else "Browse files",
                    style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (dir != null) Text(dir!!.absolutePath, color = LitaTextMid, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (folderMode && dir != null) IconButton(onClick = { newFolder = true }) { Icon(Icons.Rounded.CreateNewFolder, "New folder") }
            if (!folderMode) TextButton(onClick = { vm.pickerMode.value = PickerMode.Folders }) { Text("Video folders") }
        }

        Box(Modifier.weight(1f)) {
            when {
                !granted -> CenterMessage(
                    Icons.Rounded.FolderOpen, "Allow access to all files",
                    "Lita's own file browser needs Android's “All files access”. Turn it on for Lita in the next screen, then come back.",
                ) {
                    Button(onClick = {
                        vm.expectExternalActivity()
                        if (Build.VERSION.SDK_INT >= 30) {
                            try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))) }
                            catch (e: Exception) { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                        } else permLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    }) { Text(if (Build.VERSION.SDK_INT >= 30) "Open settings" else "Allow") }
                }

                dir == null -> LazyColumn(Modifier.fillMaxSize()) {
                    items(roots, key = { it.second.absolutePath }) { (label, f) ->
                        Row(Modifier.fillMaxWidth().clickable { dir = f }.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.tertiary)
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }

                entriesState == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                entriesState!!.isEmpty() -> CenterMessage(Icons.Rounded.FolderOpen, if (folderMode) "No subfolders" else "No videos or folders here", if (folderMode) "Use the button below to choose this folder, or create a new one." else "Open another folder.")

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entriesState ?: emptyList(), key = { it.absolutePath }) { f ->
                        val checked = f.absolutePath in selected
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (f.isDirectory) dir = f else selected = if (checked) selected - f.absolutePath else selected + f.absolutePath
                            }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (!folderMode) Checkbox(checked = checked, onCheckedChange = { selected = if (it) selected + f.absolutePath else selected - f.absolutePath })
                            if (f.isDirectory) {
                                Box(Modifier.size(72.dp, 54.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(34.dp)) }
                            } else FileThumb(f, Modifier.size(72.dp, 54.dp).clip(RoundedCornerShape(8.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                if (f.isFile) Text(formatBytes(f.length()), color = LitaTextMid, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        val d = dir
        if (folderMode) {
            Button(
                onClick = { if (d != null) vm.folderPicked(d.absolutePath) }, enabled = d != null && granted,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            ) { Text(if (d != null) "Use “${d.name}” as the vault folder" else "Open a folder first") }
        } else if (selected.isNotEmpty()) {
            Button(
                onClick = { vm.importFiles(selected.map { File(it) }) },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            ) { Text("Import ${selected.size} item(s)") }
        } else Box(Modifier.navigationBarsPadding())
    }

    if (newFolder) {
        var name by remember { mutableStateOf("LitaVault") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text("New folder") },
            text = { OutlinedTextField(name, { name = it.filter { c -> c != '/' && c != '\\' } }, singleLine = true, label = { Text("Folder name") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    dir?.let { File(it, name.trim()).mkdirs() }
                    refreshKey++; newFolder = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("Cancel") } },
        )
    }
}
