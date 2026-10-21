package com.lita.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lita.app.LitaViewModel
import com.lita.app.ui.Text
import com.lita.app.ui.ThumbImage
import com.lita.app.ui.theme.LitaSurface
import com.lita.app.ui.theme.LitaSurfaceHigh
import com.lita.app.ui.theme.LitaTextMid
import com.lita.app.ui.theme.litaSwitchColors

@Composable
private fun FilterShell(title: String, onDismiss: () -> Unit, content: LazyListScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.82f), shape = RoundedCornerShape(24.dp), color = LitaSurface) {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp))
                Text("Switch ON = hidden, OFF = visible.", color = LitaTextMid, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                LazyColumn(Modifier.weight(1f)) { content() }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(8.dp)) { Text("OK") }
            }
        }
    }
}

@Composable
fun FolderFiltersDialog(vm: LitaViewModel, onDismiss: () -> Unit) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val hidden by vm.userState.hiddenFolders.collectAsStateWithLifecycle()
    val folders = remember(entries) {
        entries.map { it.folder }.filter { it.isNotEmpty() }.flatMap { f ->
            val parts = f.split('/'); (1..parts.size).map { parts.take(it).joinToString("/") }
        }.distinct().sorted()
    }
    FilterShell("Folder filters", onDismiss) {
        if (folders.isEmpty()) item { Text("No folders in the vault.", color = LitaTextMid, modifier = Modifier.padding(20.dp)) }
        items(folders.size, key = { "f:" + folders[it] }) { i ->
            val f = folders[i]
            val inside = entries.filter { it.folder == f || it.folder.startsWith("$f/") }
            FilterRow(
                title = f, subtitle = "${inside.size} videos", checked = f in hidden,
                thumb = { if (inside.isNotEmpty()) ThumbImage(vm, inside.first().id, Modifier.size(64.dp, 44.dp).clip(RoundedCornerShape(8.dp)))
                          else Box(Modifier.size(64.dp, 44.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Folder, null, tint = LitaTextMid) } },
                onChange = { vm.userState.setFolderHidden(f, it) },
            )
        }
    }
}

@Composable
fun VideoFiltersDialog(vm: LitaViewModel, onDismiss: () -> Unit) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val hidden by vm.userState.hiddenVideos.collectAsStateWithLifecycle()
    FilterShell("Video filters", onDismiss) {
        items(entries.size, key = { "v:" + entries[it].id }) { i ->
            val e = entries[i]
            FilterRow(
                title = e.name, subtitle = e.folder.ifEmpty { "Vault" }, checked = e.id in hidden,
                thumb = { ThumbImage(vm, e.id, Modifier.size(64.dp, 44.dp).clip(RoundedCornerShape(8.dp))) },
                onChange = { vm.userState.setVideoHidden(e.id, it) },
            )
        }
    }
}

@Composable
private fun FilterRow(title: String, subtitle: String, checked: Boolean, thumb: @Composable () -> Unit, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.clip(RoundedCornerShape(8.dp)).then(Modifier)) { thumb() }
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, color = LitaTextMid, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = litaSwitchColors())
    }
}
