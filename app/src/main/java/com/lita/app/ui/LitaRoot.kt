package com.lita.app.ui

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lita.app.LitaViewModel
import com.lita.app.OpState
import com.lita.app.i18n.I18n
import com.lita.app.PickerMode
import com.lita.app.Tab
import com.lita.app.VaultStatus
import com.lita.app.ui.screens.CleanerScreen
import com.lita.app.ui.screens.ExploreScreen
import com.lita.app.ui.screens.HistoryScreen
import com.lita.app.ui.screens.LockScreen
import com.lita.app.ui.screens.RecoveryRequiredScreen
import com.lita.app.ui.screens.SavedScreen
import com.lita.app.ui.screens.SettingsScreen
import com.lita.app.ui.screens.ViewerScreen
import com.lita.app.ui.screens.FileBrowserScreen
import com.lita.app.ui.screens.ImportPickerScreen
import com.lita.app.ui.screens.SetupScreen
import com.lita.app.ui.screens.SplashScreen
import kotlinx.coroutines.delay
import com.lita.app.ui.screens.ShowcaseScreen
import com.lita.app.ui.screens.VaultProblemScreen
import com.lita.app.ui.screens.VolumeOverlay
import com.lita.app.ui.screens.findFragmentActivity
import com.lita.app.ui.theme.LitaBrush
import com.lita.app.ui.theme.LitaSurfaceHigh
import com.lita.app.ui.theme.LitaTextMid

@Composable
fun LitaRoot(vm: LitaViewModel, onUiReady: () -> Unit = {}) {
    val status by vm.status.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val blockShots by vm.blockScreenshots.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val viewerSession by vm.viewer.collectAsStateWithLifecycle()
    var lastBack by remember { mutableLongStateOf(0L) }
    // Overlays (viewer, pickers) register their own Back handlers later and therefore take priority over this one.
    BackHandler {
        if (vm.backPressed()) return@BackHandler
        val now = SystemClock.elapsedRealtime()
        if (now - lastBack < 2000L) ctx.findFragmentActivity()?.finish()
        else { lastBack = now; vm.toast("Press Back again to exit") }
    }
    var minSplashDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(1000); minSplashDone = true }
    // Splash stays from the very first frame until the vault state is known (and at least ~1 s).
    val splashVisible = !minSplashDone || (status is VaultStatus.Loading && !locked)
    val picker by vm.pickerMode.collectAsStateWithLifecycle()

    LaunchedEffect(blockShots) {
        val w = ctx.findFragmentActivity()?.window ?: return@LaunchedEffect
        if (blockShots) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(I18n.tr(it)) } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (splashVisible) {
            SplashScreen(onUiReady)
        } else if (locked) {
            LockScreen(vm)
        } else {
            when (val s = status) {
                is VaultStatus.Loading -> LoadingScreen("Opening vault…")
                is VaultStatus.NoVault -> SetupScreen(vm)
                is VaultStatus.RecoveryRequired -> RecoveryRequiredScreen(vm)
                is VaultStatus.PermissionRequired -> VaultProblemScreen(vm, "Permission required", s.detail, permission = true)
                is VaultStatus.Inaccessible -> VaultProblemScreen(vm, "Vault inaccessible", s.detail, permission = false)
                is VaultStatus.Corrupted -> VaultProblemScreen(vm, "Vault problem", s.detail, permission = false)
                is VaultStatus.Ready -> MainScaffold(vm)
            }
            when (picker) {
                PickerMode.Folders -> ImportPickerScreen(vm)
                PickerMode.Files -> FileBrowserScreen(vm, folderMode = false)
                PickerMode.VaultFolder -> FileBrowserScreen(vm, folderMode = true)
                null -> {}
            }
            if (viewerSession != null && status is VaultStatus.Ready) ViewerScreen(vm)
            OpBanner(vm, Modifier.align(Alignment.TopCenter))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp)) { Snackbar(it) }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            VolumeOverlay(vm, Modifier.fillMaxSize())
        }
    }
    DeleteOriginalsDialog(vm)
}

@Composable
private fun MainScaffold(vm: LitaViewModel) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            Crossfade(targetState = tab, animationSpec = tween(140), label = "tab") { t ->
                Box(Modifier.fillMaxSize().then(if (t == Tab.Explore) Modifier else Modifier.statusBarsPadding())) {
                    when (t) {
                        Tab.Explore -> ExploreScreen(vm)
                        Tab.Showcase -> ShowcaseScreen(vm)
                        Tab.Saved -> SavedScreen(vm)
                        Tab.History -> HistoryScreen(vm)
                        Tab.Cleaner -> CleanerScreen(vm)
                        Tab.Settings -> SettingsScreen(vm)
                    }
                }
            }
        }
        BottomBar(tab) { vm.selectTab(it) }
    }
}

private fun iconFor(t: Tab): ImageVector = when (t) {
    Tab.Explore -> Icons.Rounded.Explore
    Tab.Showcase -> Icons.Rounded.GridView
    Tab.Saved -> Icons.Rounded.Bookmarks
    Tab.History -> Icons.Rounded.History
    Tab.Cleaner -> Icons.Rounded.FileCopy
    Tab.Settings -> Icons.Rounded.Settings
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding().height(56.dp)) {
        for (t in Tab.entries) {
            val sel = t == selected
            val tint by animateColorAsState(if (sel) Color.White else LitaTextMid, tween(160), label = "tint")
            val bar by animateFloatAsState(if (sel) 0.45f else 0f, tween(200), label = "bar")
            val scale by animateFloatAsState(if (sel) 1.12f else 1f, tween(160), label = "scale")
            Column(
                Modifier.weight(1f).fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = t.label) { if (!sel) onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(Modifier.height(2.dp).fillMaxWidth(bar).background(LitaBrush))
                Icon(iconFor(t), contentDescription = t.label, tint = tint, modifier = Modifier.padding(top = 4.dp).graphicsLayer { scaleX = scale; scaleY = scale })
                Text(t.label, fontSize = 10.sp, color = tint, maxLines = 1)
            }
        }
    }
}

@Composable
private fun OpBanner(vm: LitaViewModel, modifier: Modifier) {
    val op by vm.op.collectAsStateWithLifecycle()
    AnimatedVisibility(visible = op !is OpState.Idle, modifier = modifier.statusBarsPadding().padding(12.dp)) {
        val o = op
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LitaSurfaceHigh).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (o) {
                is OpState.Busy -> {
                    Text(o.label, style = MaterialTheme.typography.bodyMedium)
                    if (o.progress != null) LinearProgressIndicator(progress = { o.progress }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is OpState.Done -> {
                    Text(o.message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::dismissOp) { Text("OK") }
                }
                is OpState.Failed -> {
                    Text("Failed: ${o.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = vm::dismissOp) { Text("Dismiss") }
                }
                is OpState.Idle -> {}
            }
        }
    }
}

@Composable
private fun DeleteOriginalsDialog(vm: LitaViewModel) {
    val pending by vm.pendingDeletes.collectAsStateWithLifecycle()
    val locked by vm.locked.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.toast(if (r.resultCode == Activity.RESULT_OK) "Original files deleted" else "Original files were kept")
    }
    if (pending.isNotEmpty() && !locked) {
        AlertDialog(
            onDismissRequest = vm::dismissDeleteOriginals,
            title = { Text("Delete the original files?") },
            text = {
                Text(
                    "${pending.size} video(s) were encrypted into the vault and verified. You can now delete the unprotected originals. " +
                        "This works only if Android and the storage provider allow it; anything that cannot be deleted is left in place."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val uris = pending.map { it.uri }
                    // Videos from the in-app browser are MediaStore items: Android asks the user to confirm deletion itself.
                    if (Build.VERSION.SDK_INT >= 30 && uris.all { it.authority == MediaStore.AUTHORITY }) {
                        try {
                            val pi = MediaStore.createDeleteRequest(ctx.contentResolver, uris)
                            vm.dismissDeleteOriginals(); vm.expectExternalActivity()
                            launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                        } catch (e: Exception) { vm.confirmDeleteOriginals() }
                    } else vm.confirmDeleteOriginals()
                }) { Text("Delete originals") }
            },
            dismissButton = { TextButton(onClick = vm::dismissDeleteOriginals) { Text("Keep originals") } },
        )
    }
}
