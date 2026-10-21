package com.lita.app.ui.screens

import android.os.Build
import android.provider.MediaStore
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
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.lita.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lita.app.LitaViewModel
import com.lita.app.PickerMode
import com.lita.app.data.DeviceVideo
import com.lita.app.data.DeviceVideos
import com.lita.app.ui.CenterMessage
import com.lita.app.ui.formatBytes
import com.lita.app.ui.formatDuration
import com.lita.app.ui.theme.LitaSurfaceHigh
import com.lita.app.ui.theme.LitaTextMid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class DeviceFolder(val id: Long, val name: String, val videos: List<DeviceVideo>)

@Composable
private fun DeviceThumb(video: DeviceVideo, modifier: Modifier) {
    val resolver = LocalContext.current.contentResolver
    val bmp by produceState<ImageBitmap?>(null, video.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) resolver.loadThumbnail(video.uri, Size(240, 240), null)
                else @Suppress("DEPRECATION") MediaStore.Video.Thumbnails.getThumbnail(resolver, video.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            }.getOrNull()?.asImageBitmap()
        }
    }
    Box(modifier.background(LitaSurfaceHigh)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** MX-Player-style browser: device video folders → videos, multi-select, import into the vault. */
@Composable
fun ImportPickerScreen(vm: LitaViewModel) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(DeviceVideos.hasPermission(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it || DeviceVideos.hasPermission(ctx) }
    var videos by remember { mutableStateOf<List<DeviceVideo>?>(null) }
    var openFolder by remember { mutableStateOf<Long?>(null) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }

    LaunchedEffect(granted) {
        if (granted) videos = withContext(Dispatchers.IO) { DeviceVideos.query(ctx.contentResolver) }
    }
    BackHandler { if (openFolder != null) openFolder = null else vm.pickerMode.value = null }

    val list = videos
    val folders = remember(list) {
        list?.groupBy { it.bucketId }?.map { (id, v) -> DeviceFolder(id, v.first().bucketName, v) }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }
    val current = folders.firstOrNull { it.id == openFolder }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (openFolder != null) openFolder = null else vm.pickerMode.value = null }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
            Text(current?.name ?: "Import videos", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (current == null) TextButton(onClick = { vm.pickerMode.value = PickerMode.Files }) { Text("Browse files") }
            if (current != null) {
                val all = current.videos.all { it.id in selected }
                TextButton(onClick = {
                    selected = if (all) selected - current.videos.map { it.id }.toSet() else selected + current.videos.map { it.id }
                }) { Text(if (all) "Clear" else "Select all") }
            }
        }

        Box(Modifier.weight(1f)) {
            when {
                !granted -> CenterMessage(
                    Icons.Rounded.VideoLibrary, "Allow access to your videos",
                    "Lita needs permission to list the videos on this device so you can choose what to import. Lita only reads the videos you select.",
                ) { Button(onClick = { vm.expectExternalActivity(); permLauncher.launch(DeviceVideos.permission()) }) { Text("Allow") } }

                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                list.isEmpty() -> CenterMessage(Icons.Rounded.VideoLibrary, "No videos found", "No videos are visible to Lita on this device. If you chose limited access, allow more videos in the system settings.")

                current == null -> LazyColumn(Modifier.fillMaxSize()) {
                    items(folders, key = { it.id }) { f ->
                        val n = f.videos.count { it.id in selected }
                        val state = when (n) { 0 -> ToggleableState.Off; f.videos.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }
                        Row(
                            Modifier.fillMaxWidth().clickable { openFolder = f.id }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TriStateCheckbox(state = state, onClick = {
                                val ids = f.videos.map { it.id }
                                selected = if (state == ToggleableState.On) selected - ids.toSet() else selected + ids
                            })
                            DeviceThumb(f.videos.first(), Modifier.size(72.dp, 54.dp).clip(RoundedCornerShape(8.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                Text("${f.videos.size} videos · ${formatBytes(f.videos.sumOf { it.size })}", color = LitaTextMid, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(current.videos, key = { it.id }) { v ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = if (v.id in selected) selected - v.id else selected + v.id }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(checked = v.id in selected, onCheckedChange = { selected = if (it) selected + v.id else selected - v.id })
                            DeviceThumb(v, Modifier.size(72.dp, 54.dp).clip(RoundedCornerShape(8.dp)))
                            Column(Modifier.weight(1f)) {
                                Text(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                                Text("${formatDuration(v.durationMs)} · ${formatBytes(v.size)}", color = LitaTextMid, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        if (selected.isNotEmpty() && list != null) {
            val chosen = list.filter { it.id in selected }
            Button(
                onClick = { vm.importDeviceVideos(chosen) },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
            ) { Text("Import ${chosen.size} video(s) · ${formatBytes(chosen.sumOf { it.size })}") }
        } else {
            Box(Modifier.navigationBarsPadding())
        }
    }
}
