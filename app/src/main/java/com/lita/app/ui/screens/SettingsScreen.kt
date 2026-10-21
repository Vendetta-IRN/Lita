package com.lita.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import com.lita.app.ui.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lita.app.LitaViewModel
import com.lita.app.PickerMode
import com.lita.app.i18n.AppLanguages
import com.lita.app.PinResult
import com.lita.app.ui.ChangePasswordDialog
import com.lita.app.ui.PasswordDialog
import com.lita.app.ui.formatBytes
import com.lita.app.ui.rememberImportActions
import com.lita.app.ui.theme.litaSwitchColors
import com.lita.app.ui.theme.LitaTextMid
import kotlinx.coroutines.launch

private val RELOCK_OPTIONS = listOf(0 to "Immediately", 60 to "After 1 minute", 300 to "After 5 minutes", -1 to "Only when the app is closed")

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp))
}

@Composable
private fun ActionRow(label: String, caption: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else LitaTextMid)
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = LitaTextMid)
    }
}

@Composable
private fun SwitchRow(label: String, caption: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = LitaTextMid)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled, colors = litaSwitchColors())
    }
}

private sealed interface PinDialog {
    data object Set : PinDialog
    data object Disable : PinDialog
}

@Composable
fun SettingsScreen(vm: LitaViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info by vm.info.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val pinEnabled by vm.pinEnabled.collectAsStateWithLifecycle()
    val bio by vm.biometricEnabled.collectAsStateWithLifecycle()
    val relock by vm.relockSeconds.collectAsStateWithLifecycle()
    val previews by vm.previews.collectAsStateWithLifecycle()
    val blockShots by vm.blockScreenshots.collectAsStateWithLifecycle()
    val hiddenVideos by vm.userState.hiddenVideos.collectAsStateWithLifecycle()
    val hiddenFolders by vm.userState.hiddenFolders.collectAsStateWithLifecycle()
    val imports = rememberImportActions(vm)
    val lang by vm.language.collectAsStateWithLifecycle()

    var restoreTree by remember { mutableStateOf<String?>(null) }
    var moveTree by remember { mutableStateOf<String?>(null) }
    var showChangePw by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf<PinDialog?>(null) }
    var relockMenu by remember { mutableStateOf(false) }
    var showFolders by remember { mutableStateOf(false) }
    var showVideos by remember { mutableStateOf(false) }


    val folders = remember(entries) {
        entries.map { it.folder }.filter { it.isNotEmpty() }.flatMap { f ->
            val parts = f.split('/'); (1..parts.size).map { parts.take(it).joinToString("/") }
        }.distinct().sorted()
    }
    val bioAvailable = remember { Biometrics.available(ctx) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }

    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp)) }

        item { Section("Language") }
        items(AppLanguages.all, key = { "lang:" + it.code }) { l ->
            Row(
                Modifier.fillMaxWidth().clickable { vm.setLanguage(l.code) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = lang == l.code, onClick = { vm.setLanguage(l.code) })
                Text(l.nativeName, style = MaterialTheme.typography.bodyLarge)
            }
        }

        item { Section("Vault") }
        item {
            val i = info
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Location: ${i?.folderLabel ?: "—"}", style = MaterialTheme.typography.bodyMedium)
                Text("${i?.videos ?: 0} videos · ${formatBytes(i?.totalBytes ?: 0)} (decrypted size)", color = LitaTextMid, style = MaterialTheme.typography.bodySmall)
                if (i != null && (i.missing > 0 || i.sizeMismatch > 0)) {
                    Text("Problems: ${i.missing} missing, ${i.sizeMismatch} damaged. Entries are kept and never deleted automatically.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (i != null && !i.recoveryFilePresent) {
                    Text("The recovery file is missing from the vault folder.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { ActionRow("Import videos and folders", "Browse the videos on this device by folder and pick what to import", onClick = imports.importVideos) }
        item { ActionRow("Browse all files to import", "Lita's own file browser (needs All files access)", onClick = { vm.pickerMode.value = PickerMode.Files }) }
        item { ActionRow("Import with the system file picker", "For files the in-app browser cannot see", onClick = imports.importSystem) }
        item { ActionRow("Import a folder (system picker)", "Imports supported videos and keeps the folder layout (encrypted)", onClick = imports.importFolder) }
        item { ActionRow("Restore vault", "Switch to an existing vault folder using its recovery password", onClick = { vm.pickVaultFolder { restoreTree = it } }) }
        item { ActionRow("Change vault location", "Copies the whole vault to a new empty folder and verifies it; the old copy is not deleted", onClick = { vm.pickVaultFolder { moveTree = it } }) }
        item { ActionRow("Change recovery password", "You need the current password", onClick = { showChangePw = true }) }
        if ((info?.orphans ?: 0) > 0) {
            item { ActionRow("Check ${info?.orphans} unreferenced file(s)", "Left over from interrupted imports: complete ones are recovered, broken ones removed", onClick = vm::recoverIncomplete) }
        }
        item { ActionRow("Rescan vault", "Re-reads the vault index and checks files (no decryption)", onClick = { vm.rescan() }) }

        item { Section("App lock") }
        item {
            SwitchRow("PIN lock", if (pinEnabled) "A 4–8 digit PIN is required to open Lita" else "Off", pinEnabled) { on ->
                pinDialog = if (on) PinDialog.Set else PinDialog.Disable
            }
        }
        if (pinEnabled) {
            item { ActionRow("Change PIN", onClick = { pinDialog = PinDialog.Set }) }
            item {
                SwitchRow(
                    "Fingerprint / face", if (bioAvailable) "Uses the Android system biometric prompt; PIN stays available as fallback"
                    else "Not available: no enrolled biometrics or not supported on this device", bio, enabled = bioAvailable,
                ) { vm.setBiometricEnabled(it) }
            }
            item {
                Box {
                    ActionRow("Lock again", RELOCK_OPTIONS.firstOrNull { it.first == relock }?.second ?: "Immediately", onClick = { relockMenu = true })
                    DropdownMenu(expanded = relockMenu, onDismissRequest = { relockMenu = false }) {
                        for ((s, label) in RELOCK_OPTIONS) DropdownMenuItem(text = { Text(label) }, onClick = { vm.setRelockSeconds(s); relockMenu = false })
                    }
                }
            }
        }
        item { SwitchRow("Block screenshots", "Also hides Lita in the recent-apps preview", blockShots) { vm.setBlockScreenshots(it) } }

        item { Section("Playback") }
        item { SwitchRow("Showcase previews", "Plays one muted preview at a time in Showcase", previews) { vm.setPreviews(it) } }

        item { Section("Filters") }
        item {
            Text("Switch ON = hidden, OFF = visible. Hiding never deletes a video.", color = LitaTextMid, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        }
        item { ActionRow("Folder filters", "${hiddenFolders.size} hidden of ${folders.size}", onClick = { showFolders = true }) }
        item { ActionRow("Video filters", "${hiddenVideos.size} hidden of ${entries.size}", onClick = { showVideos = true }) }

        item { Section("About") }
        item {
            Text(
                "Lita $version. Videos and their metadata are encrypted with AES-256-GCM. The app lock is an interface gate; " +
                    "the vault key is protected by this device's Android Keystore and by your recovery password. " +
                    "Exported copies and the original files you imported are not protected by the vault. " +
                    "This app has not undergone a security audit.",
                color = LitaTextMid, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
            )
        }
        item { Text("Created by Vendetta IRN", color = LitaTextMid, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp)) }
        item { Box(Modifier.height(24.dp)) }
    }

    if (showFolders) FolderFiltersDialog(vm) { showFolders = false }
    if (showVideos) VideoFiltersDialog(vm) { showVideos = false }
    restoreTree?.let { t ->
        PasswordDialog(
            title = "Switch to this vault", submitLabel = "Restore", confirm = false,
            warning = "Lita will use the vault in the selected folder. Your current vault's files are not modified or deleted.",
            onDismiss = { restoreTree = null }, onSubmit = { pw -> vm.restoreVault(t, pw); restoreTree = null },
        )
    }
    moveTree?.let { t ->
        AlertDialog(
            onDismissRequest = { moveTree = null },
            title = { Text("Copy vault here?") },
            text = { Text("The whole encrypted vault is copied into the selected empty folder and verified. Lita then uses the new copy. The old folder is left untouched.") },
            confirmButton = { TextButton(onClick = { vm.moveVaultTo(t); moveTree = null }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { moveTree = null }) { Text("Cancel") } },
        )
    }
    if (showChangePw) {
        ChangePasswordDialog(onDismiss = { showChangePw = false }, onSubmit = { o, n -> vm.changeRecoveryPassword(o, n); showChangePw = false })
    }
    when (pinDialog) {
        PinDialog.Set -> PinEntryDialog(
            title = if (pinEnabled) "New PIN" else "Set a PIN", confirmPin = true,
            onDismiss = { pinDialog = null },
            onSubmit = { _, pin ->
                pinDialog = null
                scope.launch { if (vm.setPin(pin)) vm.toast("PIN saved") else vm.toast("The PIN must be 4–8 digits") }
            },
        )
        PinDialog.Disable -> PinEntryDialog(
            title = "Enter current PIN to turn off lock", confirmPin = false,
            onDismiss = { pinDialog = null },
            onSubmit = { _, pin ->
                pinDialog = null
                scope.launch {
                    when (val r = vm.disablePin(pin)) {
                        is PinResult.Ok -> vm.toast("PIN lock turned off")
                        is PinResult.Wrong -> vm.toast("Incorrect PIN")
                        is PinResult.Locked -> vm.toast("Too many attempts. Try again in ${(r.remainingMs + 999) / 1000} s")
                    }
                }
            },
        )
        null -> {}
    }
}

@Composable
private fun PinEntryDialog(title: String, confirmPin: Boolean, onDismiss: () -> Unit, onSubmit: (CharArray?, CharArray) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    val confirmMode = confirmPin
    val valid = pin.length in 4..8 && pin.all { it.isDigit() } && (!confirmMode || pin == pin2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pin, { if (it.length <= 8) pin = it.filter { c -> c.isDigit() } }, label = { Text("PIN (4–8 digits)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                if (confirmMode) {
                    OutlinedTextField(pin2, { if (it.length <= 8) pin2 = it.filter { c -> c.isDigit() } }, label = { Text("Repeat PIN") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onSubmit(null, pin.toCharArray()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
