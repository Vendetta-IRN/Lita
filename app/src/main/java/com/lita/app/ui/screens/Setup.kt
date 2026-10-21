package com.lita.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import com.lita.app.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lita.app.LitaViewModel
import com.lita.app.ui.CenterMessage
import com.lita.app.ui.LanguageMenu
import com.lita.app.ui.PasswordDialog
import com.lita.app.ui.theme.LitaLogo
import com.lita.app.ui.theme.LitaTextMid

private enum class SetupMode { Create, Restore }

/** Folder picker + password dialog flow shared by first-run setup, restore and recovery screens. */
@Composable
private fun rememberVaultFlow(vm: LitaViewModel): Pair<(SetupMode) -> Unit, @Composable () -> Unit> {
    var mode by remember { mutableStateOf<SetupMode?>(null) }
    var tree by remember { mutableStateOf<String?>(null) }
    val start: (SetupMode) -> Unit = { m -> mode = m; tree = null; vm.pickVaultFolder { tree = it } }
    val dialog: @Composable () -> Unit = {
        val t = tree
        val m = mode
        if (t != null && m != null) {
            if (m == SetupMode.Create) {
                PasswordDialog(
                    title = "Set a recovery password", submitLabel = "Create vault", confirm = true,
                    warning = "This password protects the key that unlocks your vault on a new device or after reinstalling Lita. " +
                        "If you lose it, your videos may be impossible to recover. Lita cannot reset it.",
                    onDismiss = { tree = null; mode = null },
                    onSubmit = { pw -> vm.createVault(t, pw); tree = null; mode = null },
                )
            } else {
                PasswordDialog(
                    title = "Enter recovery password", submitLabel = "Restore", confirm = false, warning = null,
                    onDismiss = { tree = null; mode = null },
                    onSubmit = { pw -> vm.restoreVault(t, pw); tree = null; mode = null },
                )
            }
        }
    }
    return start to dialog
}

@Composable
fun SetupScreen(vm: LitaViewModel) {
    val (start, dialog) = rememberVaultFlow(vm)
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.height(150.dp))   // leave room for the progress/result card that appears at the top
        LitaLogo(84.dp)
        Box(Modifier.height(20.dp))
        Text("Welcome to Lita", style = MaterialTheme.typography.headlineMedium)
        Box(Modifier.height(8.dp))
        Text("A private, encrypted video vault. Videos are encrypted with AES-256-GCM before they are stored.", color = LitaTextMid)
        Box(Modifier.height(20.dp))
        Text(
            "Choose where the vault lives using Lita's own file browser: open internal storage and create a folder such as LitaVault. " +
                "This needs Android's “All files access” permission, which Lita asks for in the browser. " +
                "The vault survives uninstalling Lita; you restore it with your recovery password.",
            color = LitaTextMid, style = MaterialTheme.typography.bodySmall,
        )
        Box(Modifier.height(28.dp))
        Button(onClick = { start(SetupMode.Create) }, Modifier.fillMaxWidth()) { Text("Create a new vault") }
        Box(Modifier.height(10.dp))
        OutlinedButton(onClick = { start(SetupMode.Restore) }, Modifier.fillMaxWidth()) { Text("Restore an existing vault") }
    }
    LanguageMenu(vm, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp))
    }
    dialog()
}

@Composable
fun RecoveryRequiredScreen(vm: LitaViewModel) {
    val (start, dialog) = rememberVaultFlow(vm)
    CenterMessage(
        Icons.Rounded.Lock, "Recovery required",
        "This device does not hold the key for your vault (Lita was reinstalled, or the device key was reset). " +
            "Select the vault folder and enter your recovery password to unlock it.",
    ) {
        Button(onClick = { start(SetupMode.Restore) }) { Text("Restore vault") }
    }
    dialog()
}

@Composable
fun VaultProblemScreen(vm: LitaViewModel, title: String, detail: String, permission: Boolean) {
    val (start, dialog) = rememberVaultFlow(vm)
    CenterMessage(if (permission) Icons.Rounded.FolderOpen else Icons.Rounded.ErrorOutline, title, detail) {
        Button(onClick = { vm.retryBootstrap() }) { Text("Try again") }
        Box(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.pickVaultFolder { vm.relinkVault(it) } }) { Text("Select the vault folder again") }
        Box(Modifier.height(8.dp))
        OutlinedButton(onClick = { start(SetupMode.Restore) }) { Text("Restore with recovery password") }
    }
    dialog()
}
