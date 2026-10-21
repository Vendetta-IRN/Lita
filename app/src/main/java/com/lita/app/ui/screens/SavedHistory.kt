package com.lita.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.lita.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lita.app.LitaViewModel
import com.lita.app.ui.CenterMessage
import com.lita.app.ui.ThumbImage
import com.lita.app.ui.formatDuration
import com.lita.app.ui.relativeTime
import com.lita.app.ui.theme.LitaTextMid
import com.lita.app.vault.VideoEntry

@Composable
private fun VideoRow(vm: LitaViewModel, e: VideoEntry?, subtitle: String, trailing: @Composable () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = e != null, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (e != null) {
            ThumbImage(vm, e.id, Modifier.size(width = 96.dp, height = 60.dp).clip(RoundedCornerShape(8.dp)))
        } else {
            Box(Modifier.size(width = 96.dp, height = 60.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.ErrorOutline, null, tint = LitaTextMid) }
        }
        Column(Modifier.weight(1f)) {
            Text(e?.name ?: "Unavailable video", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (e != null && e.durationMs > 0) "$subtitle · ${formatDuration(e.durationMs)}" else subtitle,
                color = LitaTextMid, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            )
        }
        trailing()
    }
}

@Composable
fun SavedScreen(vm: LitaViewModel) {
    val bookmarks by vm.userState.bookmarks.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val byId = remember(entries) { entries.associateBy { it.id } }
    if (bookmarks.isEmpty()) {
        CenterMessage(Icons.Rounded.BookmarkBorder, "Nothing saved yet", "Tap Save on a video in Explore to bookmark it here. Removing a bookmark never deletes the video.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Saved", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp)) }
        items(bookmarks.toList().reversed(), key = { it }) { id ->
            val e = byId[id]
            VideoRow(vm, e, e?.folder?.ifEmpty { "Vault" } ?: "Not found in the vault index",
                trailing = { IconButton(onClick = { vm.userState.removeBookmark(id) }) { Icon(Icons.Rounded.Bookmark, "Remove bookmark") } },
                onClick = { vm.openViewer(bookmarks.toList().reversed(), id) })
        }
    }
}

@Composable
fun HistoryScreen(vm: LitaViewModel) {
    val history by vm.userState.history.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val byId = remember(entries) { entries.associateBy { it.id } }
    var confirmClear by remember { mutableStateOf(false) }
    if (history.isEmpty()) {
        CenterMessage(Icons.Rounded.History, "No history yet", "Videos you watch in Explore appear here.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("History", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }) { Text("Clear") }
            }
        }
        items(history, key = { "${it.videoId}@${it.playedAt}" }) { h ->
            VideoRow(vm, byId[h.videoId], relativeTime(h.playedAt), trailing = {}, onClick = { vm.openViewer(history.map { it.videoId }, h.videoId) })
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("Only the watch history is cleared. No videos are deleted.") },
            confirmButton = { TextButton(onClick = { vm.userState.clearHistory(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
