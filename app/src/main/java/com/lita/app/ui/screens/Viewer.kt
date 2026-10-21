package com.lita.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.lita.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lita.app.LitaViewModel
import com.lita.app.vault.VideoEntry

/**
 * Separate full-screen player for the list it was opened from (Showcase, Saved or History).
 * It does not touch the Explore queue; Back returns to the screen it was opened from.
 */
@Composable
fun ViewerScreen(vm: LitaViewModel) {
    val session by vm.viewer.collectAsStateWithLifecycle()
    val s = session ?: return
    val entries by vm.entries.collectAsStateWithLifecycle()
    val bookmarks by vm.userState.bookmarks.collectAsStateWithLifecycle()
    val muted by vm.player.muted.collectAsStateWithLifecycle()
    val error by vm.player.error.collectAsStateWithLifecycle()
    val isPlaying by vm.player.isPlaying.collectAsStateWithLifecycle()
    val renderedId by vm.player.firstFrameId.collectAsStateWithLifecycle()
    val userPaused by vm.player.userPaused.collectAsStateWithLifecycle()
    val byId = remember(entries) { entries.associateBy { it.id } }
    val ids = remember(s, byId) { s.ids.filter { it in byId } }   // deleted videos drop out of the list
    var confirmDelete by remember { mutableStateOf<VideoEntry?>(null) }
    var confirmExport by remember { mutableStateOf<VideoEntry?>(null) }

    if (ids.isEmpty()) { LaunchedEffect(Unit) { vm.closeViewer() }; return }

    BackHandler { vm.closeViewer() }
    DisposableEffect(Unit) { onDispose { vm.player.pause() } }
    val view = LocalView.current
    DisposableEffect(isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }

    val pager = rememberPagerState(initialPage = ids.indexOf(s.startId).coerceAtLeast(0)) { ids.size }
    val settled = pager.settledPage
    val currentId = ids.getOrNull(settled)
    LaunchedEffect(currentId) { currentId?.let { vm.onViewerSettled(it) } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { ids[it] }, beyondViewportPageCount = 1) { page ->
            val entry = byId[ids[page]]
            if (entry == null) {
                Box(Modifier.fillMaxSize().background(Color.Black))
            } else {
                VideoPage(
                    vm = vm, entry = entry, active = page == settled, firstFrame = renderedId == entry.id, userPaused = userPaused,
                    error = error?.takeIf { it.videoId == entry.id }, bookmarked = entry.id in bookmarks, muted = muted,
                    onSave = { vm.toggleBookmark(entry.id) }, onMute = { vm.setMuted(!muted) },
                    onExport = { confirmExport = entry }, onDelete = { confirmDelete = entry },
                    onRetry = { vm.retryPlayback(entry.id) },
                )
            }
        }
        IconButton(
            onClick = { vm.closeViewer() },
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color(0x66000000)),
        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White) }
    }

    confirmExport?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmExport = null },
            title = { Text("Export video?") },
            text = { Text("A decrypted, playable copy will be saved to Movies/Lita Export. That copy is not protected by the vault. The encrypted original stays in the vault.") },
            confirmButton = { TextButton(onClick = { vm.exportVideo(e.id); confirmExport = null }) { Text("Export") } },
            dismissButton = { TextButton(onClick = { confirmExport = null }) { Text("Cancel") } },
        )
    }
    confirmDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete from vault?") },
            text = { Text("This permanently deletes the encrypted video from the vault. Export it first if you want to keep a copy.") },
            confirmButton = { TextButton(onClick = { vm.deleteVideo(e.id); confirmDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
