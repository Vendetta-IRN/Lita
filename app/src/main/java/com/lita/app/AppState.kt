package com.lita.app

import android.net.Uri
import com.lita.app.vault.DuplicateGroup

sealed interface VaultStatus {
    data object Loading : VaultStatus
    data object NoVault : VaultStatus
    data class PermissionRequired(val detail: String) : VaultStatus
    data object RecoveryRequired : VaultStatus
    data class Inaccessible(val detail: String) : VaultStatus
    data class Corrupted(val detail: String) : VaultStatus
    data object Ready : VaultStatus
}

sealed interface OpState {
    data object Idle : OpState
    data class Busy(val label: String, val progress: Float? = null) : OpState
    data class Done(val message: String) : OpState
    data class Failed(val message: String) : OpState
}

sealed interface DupUi {
    data object Idle : DupUi
    data class Scanning(val done: Int, val total: Int) : DupUi
    data class Result(val groups: List<DuplicateGroup>, val corruptSkipped: Int) : DupUi
}

sealed interface PinResult {
    data object Ok : PinResult
    data class Wrong(val failures: Int) : PinResult
    data class Locked(val remainingMs: Long) : PinResult
}

data class ImportedOriginal(val uri: Uri, val entryId: String)

data class VaultInfo(
    val folderLabel: String,
    val videos: Int,
    val totalBytes: Long,
    val missing: Int,
    val sizeMismatch: Int,
    val orphans: Int,
    val recoveryFilePresent: Boolean,
)

data class VolumeEvent(val fraction: Float, val tick: Long)

enum class Tab(val label: String) {
    Explore("Explore"), Showcase("Showcase"), Saved("Saved"), History("History"), Cleaner("Cleaner"), Settings("Settings")
}

/** Which in-app picker is open: device video folders, the file browser, or the folder chooser for the vault location. */
/** A separate full-screen player opened from Showcase / Saved / History; plays [ids] starting at [startId]. */
data class ViewerSession(val ids: List<String>, val startId: String)

enum class PickerMode { Folders, Files, VaultFolder }
