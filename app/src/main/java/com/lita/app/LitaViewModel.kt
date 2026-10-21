package com.lita.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lita.app.crypto.WrongPasswordException
import com.lita.app.data.AndroidIo
import com.lita.app.i18n.AppLanguages
import com.lita.app.i18n.I18n
import com.lita.app.data.DeviceVideo
import com.lita.app.data.FileVaultStorage
import com.lita.app.data.FileVideos
import com.lita.app.data.UriImportSource
import com.lita.app.data.SafVaultStorage
import com.lita.app.data.SharedPrefsStore
import com.lita.app.data.UserState
import com.lita.app.playback.FeedItem
import com.lita.app.playback.PlayerController
import com.lita.app.playback.ShuffleCycle
import com.lita.app.playback.createPreviewPlayer
import com.lita.app.playback.probeMedia
import com.lita.app.security.AppPrefs
import com.lita.app.security.AttemptLimiter
import com.lita.app.security.DeviceKeyStore
import com.lita.app.security.PinHasher
import com.lita.app.vault.EntryStatus
import com.lita.app.vault.ImportResult
import com.lita.app.vault.ScanReport
import com.lita.app.vault.VaultException
import com.lita.app.vault.VaultRepository
import com.lita.app.vault.VaultStorage
import com.lita.app.vault.VideoEntry
import com.lita.app.vault.DuplicateGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class LitaViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app
    private val resolver get() = ctx.contentResolver
    private val kv = SharedPrefsStore(app.getSharedPreferences("lita_state", Context.MODE_PRIVATE))
    val prefs = AppPrefs(kv)
    val userState = UserState(kv)
    private val limiter = AttemptLimiter(kv)
    private val shuffle = ShuffleCycle.restore(prefs.shuffleState)

    @Volatile private var repo: VaultRepository? = null
    private val scanning = AtomicBoolean(false)

    val player = PlayerController(app) { id ->
        (repo ?: throw IOException("Vault is not open")).openReader(id)
    }

    private val _status = MutableStateFlow<VaultStatus>(VaultStatus.Loading)
    val status: StateFlow<VaultStatus> = _status.asStateFlow()
    private val _entries = MutableStateFlow<List<VideoEntry>>(emptyList())
    val entries: StateFlow<List<VideoEntry>> = _entries.asStateFlow()
    private val _statuses = MutableStateFlow<Map<String, EntryStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, EntryStatus>> = _statuses.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _info = MutableStateFlow<VaultInfo?>(null)
    val info: StateFlow<VaultInfo?> = _info.asStateFlow()
    private val _feed = MutableStateFlow<List<FeedItem>>(emptyList())
    val feed: StateFlow<List<FeedItem>> = _feed.asStateFlow()
    private val _feedPosition = MutableStateFlow(shuffle.position)
    val feedPosition: StateFlow<Int> = _feedPosition.asStateFlow()

    val op = MutableStateFlow<OpState>(OpState.Idle)
    val pendingDeletes = MutableStateFlow<List<ImportedOriginal>>(emptyList())
    val dup = MutableStateFlow<DupUi>(DupUi.Idle)
    val tab = MutableStateFlow(Tab.Explore)
    private val tabStack = ArrayList<Tab>()
    private val _canGoBack = MutableStateFlow(false)
    /** True when Back should return to the screen the user came from (e.g. Explore opened from Showcase). */
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()
    val reshuffleTick = MutableStateFlow(0)

    /** Bottom-bar navigation: top-level switch, clears the back stack. */
    fun selectTab(t: Tab) { tabStack.clear(); _canGoBack.value = false; tab.value = t }

    fun backPressed(): Boolean {
        if (tabStack.isEmpty()) return false
        tab.value = tabStack.removeAt(tabStack.lastIndex)
        _canGoBack.value = tabStack.isNotEmpty()
        return true
    }
    val locked = MutableStateFlow(prefs.pin() != null)
    val messages: SharedFlow<String> get() = _messages
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val language = MutableStateFlow(prefs.language.takeIf { AppLanguages.isKnown(it) } ?: "en")
    fun setLanguage(code: String) {
        if (!AppLanguages.isKnown(code)) return
        prefs.language = code
        I18n.language = code
        language.value = code
    }
    val pinEnabled = MutableStateFlow(prefs.pin() != null)
    val pinLength = MutableStateFlow(prefs.pinLength)
    val pickerMode = MutableStateFlow<PickerMode?>(null)
    val biometricEnabled = MutableStateFlow(prefs.biometricEnabled)
    val relockSeconds = MutableStateFlow(prefs.relockSeconds)
    val previews = MutableStateFlow(prefs.showcasePreviews)
    val blockScreenshots = MutableStateFlow(prefs.blockScreenshots)
    val volume = MutableStateFlow(VolumeEvent(0f, 0))

    /** Videos eligible for Explore/Showcase: intact, and not hidden by a filter. */
    val visibleEntries: StateFlow<List<VideoEntry>>
    private val visibleState = MutableStateFlow<List<VideoEntry>>(emptyList())
    private val showcaseSeed = MutableStateFlow(System.nanoTime())
    /** Showcase order: stable per seed (adding/hiding videos does not reorder the rest); pull-to-refresh draws a new seed. */
    val showcaseEntries: StateFlow<List<VideoEntry>> = combine(visibleState, showcaseSeed) { list, seed ->
        list.sortedBy { kotlin.random.Random(it.id.hashCode().toLong() xor seed).nextLong() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    var showcaseIndex = 0
    var showcaseOffset = 0

    private var opJob: Job? = null
    private var backgroundedAt = 0L
    private var externalLaunch = false
    private var volumeTick = 0L

    init {
        visibleEntries = visibleState.asStateFlow()
        I18n.language = language.value
        player.setMuted(prefs.muted)
        viewModelScope.launch {
            combine(_entries, _statuses, userState.hiddenVideos, userState.hiddenFolders) { _, _, _, _ -> visibleNow() }
                .collect { v ->
                    visibleState.value = v
                    if (_status.value is VaultStatus.Ready) syncFeed()
                }
        }
        viewModelScope.launch {
            player.error.collect { err -> if (err != null) { shuffle.markFailed(err.videoId); publishFeed() } }
        }
        viewModelScope.launch { bootstrap() }
    }

    private fun visibleNow(): List<VideoEntry> =
        _entries.value.filter { _statuses.value[it.id] == EntryStatus.OK && userState.isVisible(it) }

    // ---- vault lifecycle ------------------------------------------------------------------------

    private suspend fun bootstrap() {
        _status.value = VaultStatus.Loading
        val loc = prefs.vaultUri
        if (loc == null) { _status.value = VaultStatus.NoVault; return }
        if (loc.startsWith("/")) {
            if (!com.lita.app.data.AllFiles.granted(ctx)) {
                _status.value = VaultStatus.PermissionRequired("“All files access” is turned off for Lita. Turn it on in Android settings, then try again.")
                return
            }
        } else {
            val uri = Uri.parse(loc)
            val hasPerm = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
            if (!hasPerm) {
                _status.value = VaultStatus.PermissionRequired("Access to the vault folder was revoked or lost.")
                return
            }
        }
        val wrapped = prefs.wrappedKey
        // Keystore access can be slow: keep it off the main thread so the first frame is not delayed.
        val key = try { wrapped?.let { w -> withContext(Dispatchers.Default) { DeviceKeyStore.unwrap(w) } } } catch (e: Exception) { null }
        if (key == null) { _status.value = VaultStatus.RecoveryRequired; return }
        try {
            val r = withContext(Dispatchers.IO) { VaultRepository.open(storageFor(loc), key) }
            repo = r
        } catch (e: SecurityException) {
            _status.value = VaultStatus.PermissionRequired("Android denied access to the vault folder."); return
        } catch (e: VaultException) {
            _status.value = VaultStatus.Corrupted(e.message ?: "The vault could not be opened"); return
        } catch (e: Exception) {
            _status.value = VaultStatus.Inaccessible(e.message ?: "The vault folder could not be read"); return
        }
        refreshInternal(initial = true)
    }

    fun retryBootstrap() { viewModelScope.launch { bootstrap() } }

    /** Points the app at a re-selected vault folder when the device key is still available. */
    fun relinkVault(loc: String) {
        persistLocation(loc)
        prefs.vaultUri = loc
        viewModelScope.launch { bootstrap() }
    }

    private fun buildInfo(r: VaultRepository, report: ScanReport): VaultInfo {
        val loc = prefs.vaultUri
        val label = when {
            loc == null -> "?"
            loc.startsWith("/") -> loc
            else -> runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(loc)) }.getOrNull()?.replace(':', '/') ?: "?"
        }
        return VaultInfo(
            folderLabel = label, videos = r.entries.size, totalBytes = r.entries.sumOf { it.size },
            missing = report.statuses.count { it.value == EntryStatus.MISSING },
            sizeMismatch = report.statuses.count { it.value == EntryStatus.SIZE_MISMATCH },
            orphans = report.orphans.size, recoveryFilePresent = report.recoveryFilePresent,
        )
    }

    /** Re-reads the index and runs the cheap consistency scan. Never overlaps; never touches device media. */
    private suspend fun refreshInternal(initial: Boolean = false, pull: Boolean = false) {
        if (!scanning.compareAndSet(false, true)) return
        try {
            val r = repo ?: return
            val result = withContext(Dispatchers.IO) {
                if (!initial) r.reload()
                val report = r.scan(refresh = !initial)
                Triple(r.entries, report, buildInfo(r, report))
            }
            _entries.value = result.first
            _statuses.value = result.second.statuses
            _info.value = result.third
            if (_status.value !is VaultStatus.Ready) _status.value = VaultStatus.Ready
            if (initial) {
                // App (re)started: begin with a fresh random queue; recently watched videos go last.
                shuffle.reshuffle(visibleNow().map { it.id }, userState.history.value.map { it.videoId })
                publishFeed()
            } else syncFeed()
        } catch (e: SecurityException) {
            _status.value = VaultStatus.PermissionRequired("Android denied access to the vault folder.")
        } catch (e: VaultException) {
            _status.value = VaultStatus.Corrupted(e.message ?: "The vault could not be read")
        } catch (e: Exception) {
            _status.value = VaultStatus.Inaccessible(e.message ?: "The vault folder could not be read")
        } finally {
            scanning.set(false)
        }
    }

    fun refresh() { viewModelScope.launch { refreshInternal() } }

    /** Settings → Rescan: runs the consistency scan with a visible progress card and a result message. */
    fun rescan() {
        launchOp("Scanning vault…") {
            val t0 = SystemClock.elapsedRealtime()
            refreshInternal()
            val rem = 900L - (SystemClock.elapsedRealtime() - t0)
            if (rem > 0) delay(rem)
            val i = _info.value
            "Scan complete: ${i?.videos ?: 0} videos checked, ${(i?.missing ?: 0) + (i?.sizeMismatch ?: 0)} problem(s)."
        }
    }
    /** Pull-to-refresh: rescans the vault, then really changes the videos (new Explore queue, new Showcase order). */
    fun pullRefresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                refreshInternal()
                if (_status.value is VaultStatus.Ready) {
                    shuffle.reshuffle(visibleNow().map { it.id }, userState.history.value.map { it.videoId })
                    publishFeed()
                    showcaseSeed.value = System.nanoTime()
                    showcaseIndex = 0; showcaseOffset = 0
                    reshuffleTick.value = reshuffleTick.value + 1
                }
                delay(500)
            } finally {
                _refreshing.value = false
            }
        }
    }

    private fun persistLocation(loc: String) {
        if (loc.startsWith("/")) return
        runCatching {
            resolver.takePersistableUriPermission(Uri.parse(loc), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    /** A vault location is either an absolute folder path (in-app browser) or a SAF tree URI string. */
    fun storageFor(loc: String): VaultStorage =
        if (loc.startsWith("/")) FileVaultStorage(File(loc)) else SafVaultStorage(ctx, Uri.parse(loc))

    private suspend fun adopt(loc: String, r: VaultRepository) {
        prefs.wrappedKey = DeviceKeyStore.wrap(r.masterKeyCopy())
        prefs.vaultUri = loc
        repo = r
        player.stop()
        _entries.value = emptyList(); _statuses.value = emptyMap()
        refreshInternal(initial = true)
    }

    fun createVault(loc: String, password: CharArray) {
        launchOp("Creating vault…") {
            persistLocation(loc)
            val r = try { withContext(Dispatchers.IO) { VaultRepository.create(storageFor(loc), password) } } finally { password.fill('\u0000') }
            adopt(loc, r)
            "Vault created. Store your recovery password safely: without it a vault cannot be recovered on a new device."
        }
    }

    fun restoreVault(loc: String, password: CharArray) {
        launchOp("Restoring vault…") {
            persistLocation(loc)
            val r = try { withContext(Dispatchers.IO) { VaultRepository.restore(storageFor(loc), password) } } finally { password.fill('\u0000') }
            adopt(loc, r)
            "Vault restored (${r.entries.size} videos). Your existing vault files were not modified."
        }
    }

    fun moveVaultTo(loc: String) {
        launchOp("Copying vault…") {
            val src = repo ?: throw VaultException("Vault is not open")
            persistLocation(loc)
            val dst = storageFor(loc)
            val newRepo = withContext(Dispatchers.IO) {
                dst.refresh()
                if (VaultRepository.exists(dst)) throw VaultException("That folder already contains a vault. Use Restore Vault to switch to it.")
                val names = src.storage.list()
                val ordered = names.filter { !it.startsWith("lita-") } + names.filter { it.startsWith("lita-index") } + names.filter { it.startsWith("lita-recovery") }
                ordered.forEachIndexed { i, n ->
                    op.value = OpState.Busy("Copying ${i + 1}/${ordered.size}", (i + 1).toFloat() / ordered.size)
                    src.storage.openRead(n).use { input -> dst.write(n, false).use { out -> input.copyTo(out) } }
                    if (dst.size(n) != src.storage.size(n)) throw IOException("Copy of a vault file has the wrong size")
                }
                val nr = VaultRepository.open(dst, src.masterKeyCopy())
                if (nr.scan().statuses.any { it.value != EntryStatus.OK }) throw VaultException("Copy verification failed; the original vault is unchanged")
                nr
            }
            adopt(loc, newRepo)
            "Vault copied and verified. The old copy was left untouched; remove it yourself once you are sure."
        }
    }

    fun changeRecoveryPassword(old: CharArray, new: CharArray) {
        launchOp("Updating recovery password…") {
            val r = repo ?: throw VaultException("Vault is not open")
            try { withContext(Dispatchers.IO) { r.changeRecoveryPassword(old, new) } } finally { old.fill('\u0000'); new.fill('\u0000') }
            "Recovery password changed. Losing it can make recovery impossible."
        }
    }

    fun recoverIncomplete() {
        launchOp("Checking unreferenced files…") {
            val r = repo ?: throw VaultException("Vault is not open")
            val rep = withContext(Dispatchers.IO) { r.recoverOrphans() }
            refreshInternal()
            "Recovered ${rep.adopted}, removed ${rep.removed} incomplete, left ${rep.kept} untouched."
        }
    }

    // ---- operations -----------------------------------------------------------------------------

    private fun userMessage(e: Throwable): String = when (e) {
        is WrongPasswordException -> "Wrong recovery password, or the recovery data was modified."
        is SecurityException -> "Android denied access to this location."
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun launchOp(label: String, block: suspend () -> String?) {
        if (op.value is OpState.Busy) { _messages.tryEmit("Another operation is still running"); return }
        op.value = OpState.Busy(label, null)
        opJob = viewModelScope.launch {
            try {
                val msg = block()
                if (msg != null) {
                    val done = OpState.Done(msg)
                    op.value = done
                    autoDismiss(done, minOf(8000L, 3000L + msg.length * 30L))
                } else op.value = OpState.Idle
            } catch (e: CancellationException) {
                op.value = OpState.Idle; throw e
            } catch (e: Exception) {
                val failed = OpState.Failed(userMessage(e))
                op.value = failed
                autoDismiss(failed, 9000L)
            }
        }
    }

    private fun autoDismiss(state: OpState, ms: Long) {
        viewModelScope.launch { delay(ms); if (op.value == state) op.value = OpState.Idle }
    }

    fun dismissOp() { if (op.value !is OpState.Busy) op.value = OpState.Idle }

    private class PendingImport(val source: com.lita.app.data.UriImportSource, val folder: String)

    private suspend fun runImports(items: List<PendingImport>, extra: String): String {
        val r = repo ?: throw VaultException("Vault is not open")
        val ok = ArrayList<ImportedOriginal>()
        val failures = ArrayList<String>()
        withContext(Dispatchers.IO) {
            items.forEachIndexed { i, item ->
                val label = "Importing ${i + 1}/${items.size}"
                var lastPct = -1
                op.value = OpState.Busy(label, 0f)
                val res = r.importVideo(item.source, item.folder, ::probeMedia) { done ->
                    val pct = if (item.source.size > 0) (done * 100 / item.source.size).toInt() else 0
                    if (pct != lastPct) { lastPct = pct; op.value = OpState.Busy(label, pct / 100f) }
                }
                when (res) {
                    is ImportResult.Success -> ok.add(ImportedOriginal(item.source.uri, res.entry.id))
                    is ImportResult.Failure -> failures.add(res.reason)
                }
            }
        }
        refreshInternal()
        if (ok.isNotEmpty()) pendingDeletes.value = ok
        val sb = StringBuilder("Imported ${ok.size} of ${items.size} videos.")
        if (failures.isNotEmpty()) sb.append(" ${failures.size} failed (first error: ${failures.first()}). Originals of failed files were not touched.")
        sb.append(extra)
        return sb.toString()
    }

    fun importVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        launchOp("Importing…") {
            val items = ArrayList<PendingImport>()
            var unreadable = 0
            for (u in uris) {
                val d = withContext(Dispatchers.IO) { AndroidIo.describe(resolver, u) }
                if (d == null || d.size < 0) unreadable++ else items.add(PendingImport(d, ""))
            }
            runImports(items, if (unreadable > 0) " $unreadable file(s) could not be read." else "")
        }
    }

    fun importFolder(tree: Uri) {
        launchOp("Scanning folder…") {
            val label = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrDefault("").substringAfterLast(':').substringAfterLast('/').ifBlank { "Imported" }
            val scan = withContext(Dispatchers.IO) { AndroidIo.walkTree(resolver, tree, label) }
            val extra = " Skipped ${scan.skippedNotVideo} non-video file(s); ${scan.inaccessible} item(s) were inaccessible."
            if (scan.files.isEmpty()) return@launchOp "No supported videos found.$extra"
            runImports(scan.files.map { PendingImport(it.source, it.folder) }, extra)
        }
    }

    fun confirmDeleteOriginals() {
        val list = pendingDeletes.value
        pendingDeletes.value = emptyList()
        if (list.isEmpty()) return
        launchOp("Deleting originals…") {
            val r = repo
            var deleted = 0
            withContext(Dispatchers.IO) {
                for (o in list) if (r?.entry(o.entryId) != null && AndroidIo.deleteOriginal(resolver, o.uri)) deleted++
            }
            val failed = list.size - deleted
            "Deleted $deleted original(s)." + if (failed > 0) " $failed could not be deleted (the provider or permission did not allow it); they were left in place." else ""
        }
    }

    fun openPicker() { pickerMode.value = PickerMode.Folders }

    private var folderCallback: ((String) -> Unit)? = null

    /** Opens Lita's own folder chooser (file browser) for the vault location. */
    fun pickVaultFolder(onPicked: (String) -> Unit) { folderCallback = onPicked; pickerMode.value = PickerMode.VaultFolder }

    fun folderPicked(path: String) {
        val cb = folderCallback
        folderCallback = null
        pickerMode.value = null
        cb?.invoke(path)
    }

    /** Imports files and folders chosen in the in-app file browser (folders are scanned recursively). */
    fun importFiles(files: List<File>) {
        pickerMode.value = null
        if (files.isEmpty()) return
        launchOp("Scanning selection…") {
            val found = withContext(Dispatchers.IO) { FileVideos.collect(files) }
            if (found.isEmpty()) return@launchOp "No supported videos in the selection."
            runImports(found.map { (f, folder) ->
                PendingImport(UriImportSource(resolver, Uri.fromFile(f), f.name, FileVideos.mimeOf(f.name), f.length()), folder)
            }, "")
        }
    }

    /** Imports videos chosen in the in-app browser (MediaStore); the device folder name becomes the encrypted vault folder. */
    fun importDeviceVideos(list: List<DeviceVideo>) {
        pickerMode.value = null
        if (list.isEmpty()) return
        launchOp("Importing…") {
            runImports(list.map { PendingImport(UriImportSource(resolver, it.uri, it.name, it.mime, it.size), it.bucketName) }, "")
        }
    }

    fun cachedThumb(id: String): ImageBitmap? = thumbCache.get(id)?.asImageBitmap()

    fun dismissDeleteOriginals() { pendingDeletes.value = emptyList() }

    fun exportVideo(id: String) {
        launchOp("Exporting…") {
            val r = repo ?: throw VaultException("Vault is not open")
            val size = r.entry(id)?.size ?: 1L
            var lastPct = -1
            val name = withContext(Dispatchers.IO) {
                AndroidIo.exportToMovies(resolver, r, id) { done ->
                    val pct = if (size > 0) (done * 100 / size).toInt() else 0
                    if (pct != lastPct) { lastPct = pct; op.value = OpState.Busy("Exporting…", pct / 100f) }
                }
            }
            "Exported to ${AndroidIo.EXPORT_DIR}/$name. The encrypted original is unchanged."
        }
    }

    fun deleteVideo(id: String) {
        launchOp("Deleting…") {
            val r = repo ?: throw VaultException("Vault is not open")
            if (player.currentId == id) player.stop()
            val left = withContext(Dispatchers.IO) { r.delete(setOf(id)) }
            userState.onVideosDeleted(setOf(id))
            refreshInternal()
            if (left > 0) "Removed from the vault index; the storage provider kept an unreferenced file." else "Video deleted from the vault."
        }
    }

    // ---- duplicates --------------------------------------------------------------------------------

    fun scanDuplicates() {
        launchOp("Scanning for duplicates…") {
            val r = repo ?: throw VaultException("Vault is not open")
            dup.value = DupUi.Scanning(0, 0)
            val t0 = SystemClock.elapsedRealtime()
            val (groups, corrupt) = withContext(Dispatchers.IO) { r.findDuplicates { d, t -> dup.value = DupUi.Scanning(d, t) } }
            val rem = 900L - (SystemClock.elapsedRealtime() - t0)
            if (rem > 0) delay(rem)   // keep the progress view long enough to notice
            dup.value = DupUi.Result(groups, corrupt)
            "Duplicate scan finished: ${groups.size} group(s) found."
        }
    }

    fun removeDuplicates(group: DuplicateGroup, keepId: String) {
        launchOp("Removing duplicates…") {
            val r = repo ?: throw VaultException("Vault is not open")
            val ids = group.entries.map { it.id }.toSet() - keepId
            withContext(Dispatchers.IO) { r.removeDuplicates(keepId, ids) }
            userState.onVideosDeleted(ids)
            val cur = dup.value
            if (cur is DupUi.Result) dup.value = DupUi.Result(cur.groups.filter { it !== group }, cur.corruptSkipped)
            refreshInternal()
            "Removed ${ids.size} duplicate(s); one verified copy was kept."
        }
    }

    // ---- playback -----------------------------------------------------------------------------------

    private fun syncFeed() {
        shuffle.sync(visibleNow().map { it.id })
        publishFeed()
    }

    private fun publishFeed() {
        _feed.value = shuffle.items.toList()
        _feedPosition.value = shuffle.position
        prefs.shuffleState = shuffle.serialize()
    }

    fun entryById(id: String): VideoEntry? = _entries.value.firstOrNull { it.id == id }

    /** The pager settled on [page]: record progress, log history, start playback. */
    fun onPageSettled(page: Int) {
        shuffle.moveTo(page)
        publishFeed()
        val item = shuffle.current() ?: return
        val e = entryById(item.videoId) ?: return
        userState.addHistory(e.id)
        player.play(e)
    }

    fun retryPlayback(id: String) {
        shuffle.clearFailure(id)
        entryById(id)?.let { player.play(it) }
    }

    fun openInExplore(id: String) {
        val e = entryById(id) ?: return
        if (_statuses.value[id] != EntryStatus.OK) { _messages.tryEmit("This video is unavailable (missing or damaged file)"); return }
        if (!userState.isVisible(e)) { _messages.tryEmit("This video is hidden by a filter"); return }
        shuffle.clearFailure(id)
        shuffle.sync(visibleNow().map { it.id })
        shuffle.pin(id)
        publishFeed()
        if (tab.value != Tab.Explore) { tabStack.add(tab.value); _canGoBack.value = true }
        tab.value = Tab.Explore
    }

    fun toggleBookmark(id: String) { userState.toggleBookmark(id) }
    fun setMuted(m: Boolean) { prefs.muted = m; player.setMuted(m) }

    fun createPreview() = createPreviewPlayer(ctx) { id -> (repo ?: throw IOException("Vault is not open")).openReader(id) }

    private val thumbCache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun thumbnail(id: String): ImageBitmap? {
        thumbCache.get(id)?.let { return it.asImageBitmap() }
        val r = repo ?: return null
        val bmp = withContext(Dispatchers.IO) {
            r.thumbnail(id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } ?: return null
        thumbCache.put(id, bmp)
        return bmp.asImageBitmap()
    }

    // ---- app lock -----------------------------------------------------------------------------------

    suspend fun setPin(pin: CharArray): Boolean {
        if (!PinHasher.isValidFormat(pin)) return false
        val stored = withContext(Dispatchers.Default) { PinHasher.create(pin) }
        val len = pin.size
        pin.fill('0')
        prefs.setPin(stored); prefs.pinLength = len; pinLength.value = len; limiter.recordSuccess()
        pinEnabled.value = true
        return true
    }

    suspend fun unlockWithPin(pin: CharArray): PinResult {
        val lockMs = limiter.remainingLockMs()
        if (lockMs > 0) return PinResult.Locked(lockMs)
        val stored = prefs.pin() ?: run { locked.value = false; return PinResult.Ok }
        val ok = withContext(Dispatchers.Default) { PinHasher.verify(pin, stored) }
        pin.fill('0')
        return if (ok) {
            limiter.recordSuccess(); locked.value = false; PinResult.Ok
        } else {
            limiter.recordFailure()
            val rem = limiter.remainingLockMs()
            if (rem > 0) PinResult.Locked(rem) else PinResult.Wrong(limiter.failureCount)
        }
    }

    /** Verifies the current PIN before removing the lock. */
    suspend fun disablePin(pin: CharArray): PinResult {
        val lockMs = limiter.remainingLockMs()
        if (lockMs > 0) return PinResult.Locked(lockMs)
        val stored = prefs.pin() ?: return PinResult.Ok
        val ok = withContext(Dispatchers.Default) { PinHasher.verify(pin, stored) }
        pin.fill('0')
        if (!ok) { limiter.recordFailure(); return PinResult.Wrong(limiter.failureCount) }
        limiter.recordSuccess()
        prefs.setPin(null); prefs.pinLength = 0; pinLength.value = 0; prefs.biometricEnabled = false
        pinEnabled.value = false; biometricEnabled.value = false; locked.value = false
        return PinResult.Ok
    }

    fun lockRemainingMs(): Long = limiter.remainingLockMs()
    fun unlockedByBiometric() { limiter.recordSuccess(); locked.value = false }
    fun setBiometricEnabled(b: Boolean) { prefs.biometricEnabled = b; biometricEnabled.value = b }
    fun setRelockSeconds(s: Int) { prefs.relockSeconds = s; relockSeconds.value = s }
    fun setPreviews(b: Boolean) { prefs.showcasePreviews = b; previews.value = b }
    fun setBlockScreenshots(b: Boolean) { prefs.blockScreenshots = b; blockScreenshots.value = b }

    fun expectExternalActivity() { externalLaunch = true }

    fun onAppStopped() {
        backgroundedAt = SystemClock.elapsedRealtime()
        player.pauseForBackground()
    }

    fun onAppStarted() {
        if (externalLaunch) { externalLaunch = false; if (!locked.value) player.resumeFromBackground(); return }
        if (pinEnabled.value && !locked.value) {
            val s = relockSeconds.value
            if (s >= 0 && SystemClock.elapsedRealtime() - backgroundedAt >= s * 1000L) locked.value = true
        }
        if (!locked.value) player.resumeFromBackground()
    }

    private val audio by lazy { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private fun volMin(): Int = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
    private fun volMax(): Int = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    /** The phone's real media volume as 0..1 (always read fresh, so the panel matches Android's own). */
    fun systemVolumeFraction(): Float {
        val min = volMin(); val max = volMax()
        return if (max <= min) 0f else ((audio.getStreamVolume(AudioManager.STREAM_MUSIC) - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    }

    private fun publishVolume() { volume.value = VolumeEvent(systemVolumeFraction(), ++volumeTick) }

    /**
     * Volume keys change the phone's media volume by one system step and show Lita's panel (on every screen).
     * The step size is the phone's own; Android does not allow finer steps for the media stream.
     */
    fun onVolumeKey(up: Boolean): Boolean {
        if (up && player.muted.value) setMuted(false)
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        publishVolume()
        return true
    }

    /** Swiping on the panel: 0f = silent, 1f = maximum (rounded to the nearest system step). */
    fun setVolumeFraction(f: Float) {
        val min = volMin(); val max = volMax()
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, min + Math.round(f.coerceIn(0f, 1f) * (max - min)), 0)
        publishVolume()
    }

    // ---- separate viewer (opened from Showcase / Saved / History) ----
    val viewer = MutableStateFlow<ViewerSession?>(null)

    fun openViewer(ids: List<String>, startId: String) {
        if (entryById(startId) == null || _statuses.value[startId] != EntryStatus.OK) { _messages.tryEmit("This video is unavailable (missing or damaged file)"); return }
        val playable = ids.distinct().filter { _statuses.value[it] == EntryStatus.OK && entryById(it) != null }
        viewer.value = ViewerSession(playable, startId)
    }

    fun closeViewer() { player.stop(); viewer.value = null }

    fun onViewerSettled(id: String) {
        val e = entryById(id) ?: return
        userState.addHistory(e.id)
        player.play(e)
    }

    fun toast(msg: String) { _messages.tryEmit(msg) }

    override fun onCleared() {
        prefs.shuffleState = shuffle.serialize()
        player.release()
        super.onCleared()
    }
}
