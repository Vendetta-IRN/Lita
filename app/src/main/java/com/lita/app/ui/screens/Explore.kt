@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lita.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.lita.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.lita.app.LitaViewModel
import com.lita.app.playback.PlaybackError
import com.lita.app.ui.EmptyVaultMessage
import com.lita.app.ui.CenterMessage
import com.lita.app.ui.ThumbImage
import com.lita.app.ui.formatDuration
import com.lita.app.ui.theme.LitaBrush
import com.lita.app.ui.theme.LitaPink
import com.lita.app.ui.theme.LitaTextMid
import com.lita.app.vault.VideoEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun ExploreScreen(vm: LitaViewModel) {
    val feed by vm.feed.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val bookmarks by vm.userState.bookmarks.collectAsStateWithLifecycle()
    val muted by vm.player.muted.collectAsStateWithLifecycle()
    val error by vm.player.error.collectAsStateWithLifecycle()
    val isPlaying by vm.player.isPlaying.collectAsStateWithLifecycle()
    val renderedId by vm.player.firstFrameId.collectAsStateWithLifecycle()
    val userPaused by vm.player.userPaused.collectAsStateWithLifecycle()
    val byId = remember(entries) { entries.associateBy { it.id } }
    var confirmDelete by remember { mutableStateOf<VideoEntry?>(null) }
    var confirmExport by remember { mutableStateOf<VideoEntry?>(null) }

    // Leaving Explore (tab switch, lock, background) pauses playback; the screen stays awake only while playing.
    DisposableEffect(Unit) { onDispose { vm.player.pause() } }
    val view = LocalView.current
    DisposableEffect(isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::pullRefresh, modifier = Modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            EmptyVaultMessage(vm)
        } else if (feed.isEmpty()) {
            CenterMessage(
                Icons.Rounded.VisibilityOff, "Nothing to play",
                "Every video is hidden by a filter or unavailable. Check Settings → Filters, or pull down to refresh.",
            )
        } else {
            val pager = rememberPagerState(initialPage = vm.feedPosition.value.coerceIn(0, feed.lastIndex)) { feed.size }
            val settled = pager.settledPage
            val settledSlot = feed.getOrNull(settled)?.slot
            LaunchedEffect(settled, settledSlot) { vm.onPageSettled(settled) }
            val tick by vm.reshuffleTick.collectAsStateWithLifecycle()
            val startTick = remember { vm.reshuffleTick.value }
            LaunchedEffect(tick) {
                if (tick != startTick && feed.isNotEmpty()) pager.scrollToPage(vm.feedPosition.value.coerceIn(0, feed.lastIndex))
            }

            VerticalPager(
                state = pager, modifier = Modifier.fillMaxSize(),
                key = { feed.getOrNull(it)?.slot ?: it }, beyondViewportPageCount = 1,
            ) { page ->
                val entry = feed.getOrNull(page)?.let { byId[it.videoId] }
                if (entry == null) {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                } else {
                    val active = page == settled
                    VideoPage(
                        vm = vm, entry = entry, active = active, firstFrame = renderedId == entry.id, userPaused = userPaused,
                        error = error?.takeIf { it.videoId == entry.id },
                        bookmarked = entry.id in bookmarks, muted = muted,
                        onSave = { vm.toggleBookmark(entry.id) },
                        onMute = { vm.setMuted(!muted) },
                        onExport = { confirmExport = entry },
                        onDelete = { confirmDelete = entry },
                        onRetry = { vm.retryPlayback(entry.id) },
                    )
                }
            }
        }
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

@Composable
internal fun VideoPage(
    vm: LitaViewModel, entry: VideoEntry, active: Boolean, firstFrame: Boolean, userPaused: Boolean, error: PlaybackError?,
    bookmarked: Boolean, muted: Boolean,
    onSave: () -> Unit, onMute: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit, onRetry: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (active) {
            val exo = vm.player.player
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setKeepContentOnPlayerReset(true)
                        player = exo
                    }
                },
                update = { it.player = exo },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The thumbnail stays on top of the video surface until the first frame is rendered: no black flash while loading.
        if (!active || !firstFrame || error != null) ThumbImage(vm, entry.id, Modifier.fillMaxSize(), ContentScale.Fit, Color.Black)
        // Tap anywhere to play/pause.
        Box(Modifier.fillMaxSize().pointerInput(active) { detectTapGestures(onTap = { if (active) vm.player.togglePlay() }) })

        if (active && userPaused && error == null) {
            Box(Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Paused", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }

        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 88.dp, bottom = 44.dp)) {
            Text(entry.name, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            if (entry.folder.isNotEmpty()) Text(entry.folder, color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        }

        // Right-hand action rail: Save, Mute, Export, Delete.
        Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RailButton(if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (bookmarked) "Saved" else "Save", if (bookmarked) LitaPink else Color.White, onSave)
            RailButton(if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, if (muted) "Unmute" else "Mute", Color.White, onMute)
            RailButton(Icons.Rounded.Download, "Export", Color.White, onExport)
            RailButton(Icons.Rounded.DeleteOutline, "Delete", Color.White, onDelete)
        }

        if (error != null) {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xE6101018)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("This video can't be played", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text("The file may be damaged or use an unsupported format. It was not deleted. Swipe up to continue.", color = LitaTextMid, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }

        if (active) VideoSeekBar(vm.player.player, enabled = error == null, modifier = Modifier.align(Alignment.BottomCenter))
    }
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // The ripple lives on the clipped circle, so the click effect is round (not a square around icon + label).
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0x66000000)).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = tint)
        }
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

/** Thin seek bar: tap to seek, drag horizontally, grows while touched. Position is drawn without recomposing. */
@Composable
fun VideoSeekBar(player: Player, enabled: Boolean, modifier: Modifier = Modifier) {
    var pos by remember(player) { mutableLongStateOf(0L) }
    var dur by remember(player) { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(player, enabled) {
        while (isActive && enabled) {
            if (!dragging) {
                pos = player.currentPosition
                dur = player.duration.let { if (it < 0) 0L else it }
            }
            delay(200)
        }
    }
    val barHeight by animateDpAsState(if (dragging) 8.dp else 2.dp, label = "seekHeight")

    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(visible = dragging) {
            val shown = (dragFraction * dur).toLong()
            Text(
                "${formatDuration(shown)} / ${formatDuration(dur)}", color = Color.White, fontSize = 13.sp,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
        }
        Box(
            Modifier.fillMaxWidth().height(32.dp)
                .pointerInput(player, enabled) {
                    detectTapGestures { o ->
                        if (!enabled || dur <= 0 || size.width == 0) return@detectTapGestures
                        val f = (o.x / size.width).coerceIn(0f, 1f)
                        player.seekTo((f * dur).toLong()); pos = (f * dur).toLong()
                    }
                }
                .pointerInput(player, enabled) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> if (enabled && dur > 0 && size.width > 0) { dragging = true; dragFraction = (o.x / size.width).coerceIn(0f, 1f) } },
                        onDragEnd = { if (dragging) { player.seekTo((dragFraction * dur).toLong()); pos = (dragFraction * dur).toLong(); dragging = false } },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            if (dragging && size.width > 0) dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                    )
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Canvas(Modifier.fillMaxWidth().height(barHeight)) {
                drawRect(Color(0x55FFFFFF), size = size)
                val f = if (dragging) dragFraction else if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                drawRect(LitaBrush, size = Size(size.width * f, size.height))
            }
        }
    }
}

/**
 * Volume panel: appears when the volume keys are pressed (1% per press). While it is visible, a vertical swipe
 * anywhere on the screen changes the volume (up = louder, down = quieter). Always drawn on the physical left side.
 */
@Composable
fun VolumeOverlay(vm: LitaViewModel, modifier: Modifier = Modifier) {
    val ev by vm.volume.collectAsStateWithLifecycle()
    val startTick = remember { vm.volume.value.tick }   // ignore stale events when the overlay re-enters composition
    var visible by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    LaunchedEffect(ev.tick) {
        if (ev.tick != startTick) { visible = true; delay(1000); visible = false }   // every change restarts the timer
    }
    val show = visible || touching
    val level by animateFloatAsState(ev.fraction, tween(80), label = "volume")
    val swipe = if (show) {
        Modifier.pointerInput(Unit) {
            var frac = 0f
            detectVerticalDragGestures(
                onDragStart = { frac = vm.systemVolumeFraction(); touching = true },
                onDragEnd = { touching = false },
                onDragCancel = { touching = false },
            ) { change, dy ->
                change.consume()
                frac = (frac + (-dy / density) * 0.004f).coerceIn(0f, 1f)   // a swipe of about 250 dp covers the whole range
                vm.setVolumeFraction(frac)
            }
        }
    } else Modifier
    Box(modifier.then(swipe)) {
        AnimatedVisibility(
            visible = show, modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp),
            enter = fadeIn(tween(100)), exit = fadeOut(tween(250)),
        ) {
            Column(
                Modifier.width(46.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xE6101018)).padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(if (ev.fraction <= 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, contentDescription = "Volume", tint = Color.White, modifier = Modifier.size(18.dp))
                Box(Modifier.width(5.dp).height(96.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF)), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.width(5.dp).fillMaxHeight(level.coerceIn(0f, 1f)).background(LitaBrush))
                }
                Text("${(ev.fraction * 100).toInt()}%", color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.width(40.dp))
            }
        }
    }
}
