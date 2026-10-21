@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.lita.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.lita.app.ui.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.lita.app.LitaViewModel
import com.lita.app.playback.previewMediaItem
import com.lita.app.ui.CenterMessage
import com.lita.app.ui.EmptyVaultMessage
import com.lita.app.ui.ThumbImage
import com.lita.app.ui.formatDuration
import com.lita.app.ui.theme.LitaPink
import com.lita.app.vault.VideoEntry

/** Width/height ratio per tile: tall featured, medium, small. Derived from the stable ID, so tiles never change shape on refresh. */
private fun tileRatio(id: String): Float {
    val h = (id.hashCode() and 0x7fffffff) % 10
    return when {
        h < 3 -> 0.66f   // large featured tile
        h < 6 -> 0.85f
        else -> 1.05f
    }
}

@Composable
fun ShowcaseScreen(vm: LitaViewModel) {
    val visible by vm.showcaseEntries.collectAsStateWithLifecycle()
    val all by vm.entries.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val previews by vm.previews.collectAsStateWithLifecycle()
    val bookmarks by vm.userState.bookmarks.collectAsStateWithLifecycle()

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::pullRefresh, modifier = Modifier.fillMaxSize()) {
        if (all.isEmpty()) {
            EmptyVaultMessage(vm)
        } else if (visible.isEmpty()) {
            CenterMessage(Icons.Rounded.VisibilityOff, "Nothing to show", "Every video is hidden by a filter or unavailable. Pull down to refresh or check Settings → Filters.")
        } else {
            val gridState = rememberLazyStaggeredGridState(vm.showcaseIndex, vm.showcaseOffset)
            DisposableEffect(gridState) {
                onDispose { vm.showcaseIndex = gridState.firstVisibleItemIndex; vm.showcaseOffset = gridState.firstVisibleItemScrollOffset }
            }
            val tick by vm.reshuffleTick.collectAsStateWithLifecycle()
            val startTick = remember { vm.reshuffleTick.value }
            LaunchedEffect(tick) { if (tick != startTick) gridState.scrollToItem(0) }
            // At most one preview player: the first fully visible large tile (or any fully visible tile).
            val previewId by remember(previews) {
                derivedStateOf {
                    if (!previews) null else {
                        val info = gridState.layoutInfo
                        val full = info.visibleItemsInfo.filter {
                            it.offset.y >= 0 && it.offset.y + it.size.height <= info.viewportSize.height
                        }
                        (full.firstOrNull { (it.key as? String)?.let(::tileRatio) == 0.66f } ?: full.firstOrNull())?.key as? String
                    }
                }
            }
            val preview: ExoPlayer? = remember(previews) { if (previews) vm.createPreview() else null }
            DisposableEffect(preview) { onDispose { preview?.release() } }
            LaunchedEffect(previewId, preview) {
                if (preview != null) {
                    val e = previewId?.let { vm.entryById(it) }
                    if (e != null) { preview.setMediaItem(previewMediaItem(e)); preview.prepare(); preview.playWhenReady = true }
                    else preview.stop()
                }
            }
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) { preview?.pause() }
            LifecycleEventEffect(Lifecycle.Event.ON_START) { if (previewId != null) preview?.play() }

            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2), state = gridState, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp,
            ) {
                items(items = visible, key = { it.id }) { e ->
                    Tile(vm, e, bookmarked = e.id in bookmarks, player = if (e.id == previewId) preview else null) { vm.openViewer(visible.map { it.id }, e.id) }
                }
            }
        }
    }
}

@Composable
private fun Tile(vm: LitaViewModel, e: VideoEntry, bookmarked: Boolean, player: Player?, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(tileRatio(e.id)).clip(RoundedCornerShape(14.dp)).clickable(onClickLabel = "Play", onClick = onClick),
    ) {
        ThumbImage(vm, e.id, Modifier.fillMaxSize())
        if (player != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        this.player = player
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(8.dp)) {
            Text(e.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(end = 40.dp))
            if (e.durationMs > 0) Text(formatDuration(e.durationMs), color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomEnd))
        }
        if (bookmarked) Icon(Icons.Rounded.Bookmark, contentDescription = "Saved", tint = LitaPink, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
    }
}
