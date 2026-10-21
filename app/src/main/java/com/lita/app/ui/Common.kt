package com.lita.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lita.app.LitaViewModel
import com.lita.app.ui.theme.LitaBrush
import com.lita.app.ui.theme.LitaLogo
import com.lita.app.ui.theme.LitaSurfaceHigh
import com.lita.app.ui.theme.LitaTextMid

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.1f MB".format(b / (1024.0 * 1024))
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

fun relativeTime(t: Long): String = DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@Composable
fun ThumbImage(vm: LitaViewModel, id: String, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, placeholder: Color = LitaSurfaceHigh) {
    val bmp by produceState<ImageBitmap?>(vm.cachedThumb(id), id) { value = vm.cachedThumb(id) ?: vm.thumbnail(id) }
    Box(modifier.background(placeholder)) {
        bmp?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = scale) }
    }
}

@Composable
fun LoadingScreen(text: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        LitaLogo(72.dp)
        Box(Modifier.height(24.dp))
        CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
        Box(Modifier.height(16.dp))
        Text(text, color = LitaTextMid, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun CenterMessage(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, actions: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = LitaTextMid, modifier = Modifier.size(48.dp))
        Box(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Box(Modifier.height(8.dp))
        Text(body, color = LitaTextMid, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.height(20.dp))
        actions()
    }
}

/** System-picker launchers for importing; every launch tells the app lock not to relock on return. */
class ImportActions(val importVideos: () -> Unit, val importFolder: () -> Unit, val importSystem: () -> Unit)

private class OpenVideosContract : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

@Composable
fun rememberImportActions(vm: LitaViewModel): ImportActions {
    val videos = rememberLauncherForActivityResult(OpenVideosContract()) { uris: List<Uri> -> vm.importVideos(uris) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> if (uri != null) vm.importFolder(uri) }
    return remember(vm) {
        ImportActions(
            importVideos = { vm.openPicker() },
            importFolder = { vm.expectExternalActivity(); folder.launch(null) },
            importSystem = { vm.expectExternalActivity(); videos.launch(arrayOf("video/*")) },
        )
    }
}

@Composable
fun EmptyVaultMessage(vm: LitaViewModel) {
    val actions = rememberImportActions(vm)
    CenterMessage(
        Icons.Rounded.VideoLibrary, "Your vault is empty",
        "Videos you import are encrypted into your private vault. Nothing from your gallery is shown here unless you import it.",
    ) {
        Button(onClick = actions.importVideos) { Text("Import videos") }
        Box(Modifier.height(8.dp))
        TextButton(onClick = actions.importSystem) { Text("Use the system file picker") }
    }
}

/**
 * Recovery password entry. For [confirm] = true (creating a password) it requires 10+ characters and
 * a matching confirmation, and shows the "losing it may make recovery impossible" warning.
 */
@Composable
fun PasswordDialog(
    title: String, submitLabel: String, confirm: Boolean, warning: String?,
    onDismiss: () -> Unit, onSubmit: (CharArray) -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val tooShort = confirm && pw.length < 10
    val mismatch = confirm && pw != pw2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (warning != null) Text(warning, color = LitaTextMid, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(pw, { pw = it }, label = { Text("Recovery password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                if (confirm) {
                    OutlinedTextField(pw2, { pw2 = it }, label = { Text("Repeat password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = pw2.isNotEmpty() && mismatch)
                    if (pw.isNotEmpty() && tooShort) Text("Use at least 10 characters.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = pw.isNotEmpty() && !tooShort && !mismatch, onClick = { onSubmit(pw.toCharArray()) }) { Text(submitLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ChangePasswordDialog(onDismiss: () -> Unit, onSubmit: (CharArray, CharArray) -> Unit) {
    var old by remember { mutableStateOf("") }
    var new1 by remember { mutableStateOf("") }
    var new2 by remember { mutableStateOf("") }
    val ok = old.isNotEmpty() && new1.length >= 10 && new1 == new2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change recovery password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Losing the recovery password may make recovery on a new device impossible.", color = LitaTextMid, style = MaterialTheme.typography.bodySmall)
                for ((label, value, set) in listOf(
                    Triple("Current password", old, { s: String -> old = s }),
                    Triple("New password (10+ characters)", new1, { s: String -> new1 = s }),
                    Triple("Repeat new password", new2, { s: String -> new2 = s }),
                )) {
                    OutlinedTextField(value, set, label = { Text(label) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onSubmit(old.toCharArray(), new1.toCharArray()) }) { Text("Change") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
