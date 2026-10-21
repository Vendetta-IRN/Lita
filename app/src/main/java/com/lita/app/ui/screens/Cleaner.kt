package com.lita.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lita.app.DupUi
import com.lita.app.LitaViewModel
import com.lita.app.OpState
import com.lita.app.ui.CenterMessage
import com.lita.app.ui.ThumbImage
import com.lita.app.ui.formatBytes
import com.lita.app.ui.theme.LitaSurfaceHigh
import com.lita.app.ui.theme.LitaTextMid
import com.lita.app.vault.DuplicateGroup

@Composable
fun CleanerScreen(vm: LitaViewModel) {
    val dup by vm.dup.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val busy = op is OpState.Busy

    when (val d = dup) {
        is DupUi.Idle -> CenterMessage(
            Icons.Rounded.FileCopy, "Duplicate Cleaner",
            "Scans only the videos in your vault. Duplicates are found by re-hashing the decrypted content, never by file name. You review every group before anything is deleted, and one copy is always kept.",
        ) { Button(onClick = vm::scanDuplicates, enabled = !busy) { Text("Scan vault") } }

        is DupUi.Scanning -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Checking candidate videos…", style = MaterialTheme.typography.titleMedium)
            Box(Modifier.height(16.dp))
            if (d.total > 0) LinearProgressIndicator(progress = { d.done.toFloat() / d.total }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.height(8.dp))
            Text(if (d.total > 0) "${d.done} of ${d.total}" else "Looking for videos with identical size and hash", color = LitaTextMid)
        }

        is DupUi.Result -> if (d.groups.isEmpty()) {
            CenterMessage(Icons.Rounded.CheckCircle, "No duplicates found", buildString {
                append("Every video in your vault is unique.")
                if (d.corruptSkipped > 0) append(" ${d.corruptSkipped} candidate file(s) failed verification and were skipped; they were not changed.")
            }) { Button(onClick = vm::scanDuplicates, enabled = !busy) { Text("Scan again") } }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("${d.groups.size} duplicate group(s)", style = MaterialTheme.typography.headlineSmall)
                    Text("Choose the copy to keep in each group. Everything else in the group can be deleted.", color = LitaTextMid, style = MaterialTheme.typography.bodySmall)
                }
                items(d.groups, key = { it.sha256 }) { g -> GroupCard(vm, g, busy) }
                item { Button(onClick = vm::scanDuplicates, enabled = !busy) { Text("Scan again") } }
            }
        }
    }
}

@Composable
private fun GroupCard(vm: LitaViewModel, g: DuplicateGroup, busy: Boolean) {
    var keep by remember(g) { mutableStateOf(g.entries.first().id) }
    var confirm by remember { mutableStateOf(false) }
    val removable = g.entries.size - 1
    Card(colors = CardDefaults.cardColors(containerColor = LitaSurfaceHigh)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${g.entries.size} identical copies · ${formatBytes(g.entries.first().size)} each", style = MaterialTheme.typography.titleSmall)
            for (e in g.entries) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected = keep == e.id, onClick = { keep = e.id })
                    ThumbImage(vm, e.id, Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(6.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        Text(if (keep == e.id) "Keep" else "Will be deleted", color = if (keep == e.id) MaterialTheme.colorScheme.tertiary else LitaTextMid, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Button(onClick = { confirm = true }, enabled = !busy) { Text("Delete $removable duplicate(s)") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete $removable duplicate(s)?") },
            text = { Text("The selected copy is verified first and kept. The others are permanently deleted from the vault.") },
            confirmButton = { TextButton(onClick = { vm.removeDuplicates(g, keep); confirm = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
