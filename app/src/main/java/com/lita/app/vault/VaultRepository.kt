package com.lita.app.vault

import com.lita.app.crypto.AuthenticationException
import com.lita.app.crypto.ChunkedCipher
import com.lita.app.crypto.ChunkedReader
import com.lita.app.crypto.CorruptDataException
import com.lita.app.crypto.RecoveryFile
import com.lita.app.crypto.VaultCrypto
import com.lita.app.crypto.WrongPasswordException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)
class IntegrityException(message: String) : IOException(message)

interface ImportSource {
    val name: String
    val mime: String
    val size: Long
    fun open(): InputStream
}

class MediaInfo(val durationMs: Long, val thumbnailJpeg: ByteArray?)

sealed class ImportResult {
    data class Success(val entry: VideoEntry) : ImportResult()
    data class Failure(val reason: String) : ImportResult()
}

enum class EntryStatus { OK, MISSING, SIZE_MISMATCH }

class ScanReport(
    val statuses: Map<String, EntryStatus>,
    val orphans: List<String>,
    val recoveryFilePresent: Boolean,
)

class DuplicateGroup(val sha256: String, val entries: List<VideoEntry>)
class OrphanReport(val adopted: Int, val removed: Int, val kept: Int)

/**
 * Encrypted video vault on top of a [VaultStorage].
 *
 * Files: lita-recovery-a/b (password-wrapped master key, two slots), lita-index-a/b (encrypted index,
 * two alternating slots so a crash while writing never destroys the last good index), and randomly
 * named extension-less objects (videos, encrypted thumbnails).
 */
class VaultRepository private constructor(
    val storage: VaultStorage,
    private val key: ByteArray,
    initial: IndexData,
    private var activeSlot: String,
) {
    @Volatile
    private var snapshot: IndexData = initial

    val entries: List<VideoEntry> get() = snapshot.entries
    val generation: Long get() = snapshot.generation
    fun entry(id: String): VideoEntry? = snapshot.entries.firstOrNull { it.id == id }
    fun masterKeyCopy(): ByteArray = key.copyOf()

    companion object {
        const val RECOVERY_A = "lita-recovery-a"
        const val RECOVERY_B = "lita-recovery-b"
        const val INDEX_A = "lita-index-a"
        const val INDEX_B = "lita-index-b"
        private val RECOVERY_SLOTS = listOf(RECOVERY_A, RECOVERY_B)
        private val INDEX_SLOTS = listOf(INDEX_A, INDEX_B)
        private val HEX_NAME = Regex("^[0-9a-f]{32}$")

        fun exists(storage: VaultStorage): Boolean =
            storage.list().any { it.startsWith("lita-recovery") || it.startsWith("lita-index") }

        fun create(storage: VaultStorage, password: CharArray, iterations: Int = RecoveryFile.DEFAULT_ITERATIONS): VaultRepository {
            storage.refresh()
            if (exists(storage)) throw VaultException("This folder already contains a Lita vault. Use Restore Vault instead.")
            val key = VaultCrypto.newMasterKey()
            val rec = RecoveryFile.create(key, password, 1, iterations)
            storage.write(RECOVERY_A, false).use { it.write(rec) }
            if (!storage.readAll(RECOVERY_A).contentEquals(rec)) throw VaultException("Could not verify the recovery file after writing it")
            val repo = VaultRepository(storage, key, IndexData(0, emptyList()), INDEX_B)
            repo.persist(IndexData(1, emptyList()))
            return repo
        }

        /** Opens an existing vault with the recovery password (new device, or after reinstall). */
        fun restore(storage: VaultStorage, password: CharArray): VaultRepository {
            storage.refresh()
            val opened = openRecovery(storage, password)
            val (idx, slot) = loadIndex(storage, opened.masterKey)
            return VaultRepository(storage, opened.masterKey, idx, slot)
        }

        /** Opens an existing vault with a master key already held by this device. */
        fun open(storage: VaultStorage, masterKey: ByteArray): VaultRepository {
            storage.refresh()
            val (idx, slot) = loadIndex(storage, masterKey)
            return VaultRepository(storage, masterKey.copyOf(), idx, slot)
        }

        private fun openRecovery(storage: VaultStorage, password: CharArray): RecoveryFile.Opened {
            val present = RECOVERY_SLOTS.filter { storage.exists(it) }
            if (present.isEmpty()) throw VaultException("No Lita recovery file was found in this folder")
            var best: RecoveryFile.Opened? = null
            var wrong = false
            var structural: Exception? = null
            for (slot in present) {
                try {
                    val o = RecoveryFile.open(storage.readAll(slot), password)
                    if (best == null || o.generation > best.generation) best = o
                } catch (e: WrongPasswordException) {
                    wrong = true
                } catch (e: IOException) {
                    structural = e
                }
            }
            if (best != null) return best
            if (wrong) throw WrongPasswordException()
            throw VaultException("The recovery file is damaged: ${structural?.message}", structural)
        }

        private fun loadIndex(storage: VaultStorage, key: ByteArray): Pair<IndexData, String> {
            var best: IndexData? = null
            var bestSlot = INDEX_A
            var present = 0
            var authFailed = false
            for (slot in INDEX_SLOTS) {
                if (!storage.exists(slot)) continue
                present++
                try {
                    val d = IndexCodec.decode(storage.readAll(slot), key)
                    if (best == null || d.generation > best.generation) { best = d; bestSlot = slot }
                } catch (e: AuthenticationException) {
                    authFailed = true
                } catch (e: CorruptDataException) {
                    // try the other slot
                }
            }
            if (best != null) return best to bestSlot
            if (present == 0) throw VaultException("The vault index is missing from this folder")
            throw VaultException(
                if (authFailed) "The vault index failed authentication (wrong key or modified data)" else "The vault index is damaged"
            )
        }
    }

    // ---- index persistence ------------------------------------------------------------------

    private fun persist(next: IndexData) {
        val slot = if (activeSlot == INDEX_A) INDEX_B else INDEX_A
        val blob = IndexCodec.encode(next, key)
        storage.write(slot, true).use { it.write(blob) }
        val back = IndexCodec.decode(storage.readAll(slot), key)
        if (back.generation != next.generation || back.entries.size != next.entries.size) {
            throw VaultException("Index verification failed after writing")
        }
        snapshot = next
        activeSlot = slot
    }

    private fun commit(mutator: (List<VideoEntry>) -> List<VideoEntry>) {
        persist(IndexData(snapshot.generation + 1, mutator(snapshot.entries)))
    }

    /** Re-reads the index from storage (picks up changes made outside this session). */
    @Synchronized
    fun reload() {
        storage.refresh()
        val (idx, slot) = loadIndex(storage, key)
        snapshot = idx
        activeSlot = slot
    }

    // ---- import -------------------------------------------------------------------------------

    private class CountingInput(private val inner: InputStream, private val onBytes: (Long) -> Unit) : InputStream() {
        private var total = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) { total++; onBytes(total) } }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) { total += it; onBytes(total) } }
        override fun close() = inner.close()
    }

    /**
     * Encrypts [source] into the vault, verifies the written object by decrypting it again, and only
     * then updates the index. The source is never modified or deleted here. On any failure the objects
     * created by this call are removed and the previous index stays in effect.
     */
    @Synchronized
    fun importVideo(
        source: ImportSource,
        folder: String = "",
        probe: ((ChunkedReader) -> MediaInfo?)? = null,
        onProgress: ((Long) -> Unit)? = null,
    ): ImportResult {
        val id = VaultCrypto.randomHex()
        val objectName = VaultCrypto.randomHex()
        var thumbName: String? = null
        try {
            if (source.size < 0) return ImportResult.Failure("The size of this file could not be determined")
            val enc = storage.write(objectName, false).use { out ->
                val raw = source.open()
                val input = if (onProgress != null) CountingInput(raw, onProgress) else raw
                input.use { ChunkedCipher.encrypt(key, it, source.size, out) }
            }
            val ver = storage.openRead(objectName).use { ChunkedCipher.decrypt(key, it, NullOutputStream) }
            if (ver.sha256 != enc.sha256 || ver.plainLength != source.size) {
                throw IntegrityException("Verification of the encrypted copy failed")
            }
            var info: MediaInfo? = null
            if (probe != null) {
                info = try {
                    ChunkedReader(storage.openRandomAccess(objectName), key).use { probe(it) }
                } catch (e: Exception) {
                    null // a probe failure never fails the import
                }
            }
            val jpeg = info?.thumbnailJpeg
            if (jpeg != null) {
                val tn = VaultCrypto.randomHex()
                storage.write(tn, false).use { it.write(VaultCrypto.seal(key, jpeg, thumbAad(id))) }
                thumbName = tn
            }
            val entry = VideoEntry(
                id = id, objectName = objectName, thumbObject = thumbName,
                name = source.name.ifBlank { "video" }, folder = folder,
                mime = normalizeMime(source.mime, source.name), size = source.size,
                sha256 = enc.sha256, durationMs = info?.durationMs ?: 0L, addedAt = System.currentTimeMillis(),
            )
            commit { it + entry }
            return ImportResult.Success(entry)
        } catch (e: Exception) {
            runCatching { storage.delete(objectName) }
            thumbName?.let { runCatching { storage.delete(it) } }
            return ImportResult.Failure(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun thumbAad(id: String) = "lita-thumb-v1:$id".toByteArray(Charsets.UTF_8)

    private fun normalizeMime(mime: String, name: String): String {
        if (mime.startsWith("video/")) return mime
        return when (name.substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
    }

    // ---- read access ----------------------------------------------------------------------------

    fun openReader(id: String): ChunkedReader {
        val e = entry(id) ?: throw VaultException("Video is not in the vault index")
        return ChunkedReader(storage.openRandomAccess(e.objectName), key)
    }

    fun thumbnail(id: String): ByteArray? {
        val e = entry(id) ?: return null
        val name = e.thumbObject ?: return null
        return try {
            VaultCrypto.open(key, storage.readAll(name), thumbAad(id))
        } catch (ex: Exception) {
            null
        }
    }

    /** Decrypts a vault video into [out]. The encrypted original is only read, never changed. */
    fun export(id: String, out: OutputStream): ChunkedCipher.Result {
        val e = entry(id) ?: throw VaultException("Video is not in the vault index")
        val r = storage.openRead(e.objectName).use { ChunkedCipher.decrypt(key, it, out) }
        if (r.sha256 != e.sha256 || r.plainLength != e.size) throw IntegrityException("Exported data does not match the recorded hash")
        return r
    }

    /** Full decrypt-and-hash check of one object. */
    fun verify(id: String): Boolean {
        val e = entry(id) ?: return false
        return try {
            val r = storage.openRead(e.objectName).use { ChunkedCipher.decrypt(key, it, NullOutputStream) }
            r.sha256 == e.sha256 && r.plainLength == e.size
        } catch (ex: Exception) {
            false
        }
    }

    // ---- scan -------------------------------------------------------------------------------------

    /** Cheap consistency scan: existence and exact encrypted size. No decryption or hashing. */
    @Synchronized
    fun scan(refresh: Boolean = true): ScanReport {
        if (refresh) storage.refresh()
        val names = storage.list().toHashSet()
        val statuses = HashMap<String, EntryStatus>()
        val referenced = HashSet<String>()
        for (e in snapshot.entries) {
            referenced.add(e.objectName)
            e.thumbObject?.let { referenced.add(it) }
            statuses[e.id] = when {
                e.objectName !in names -> EntryStatus.MISSING
                storage.size(e.objectName).let { it >= 0 && it != ChunkedCipher.encryptedLength(e.size) } -> EntryStatus.SIZE_MISMATCH
                else -> EntryStatus.OK
            }
        }
        val orphans = names.filter { HEX_NAME.matches(it) && it !in referenced }.sorted()
        return ScanReport(statuses, orphans, RECOVERY_SLOTS.any { it in names })
    }

    /**
     * Handles unreferenced objects (e.g. from an interrupted import): objects that verify completely
     * are adopted into the index; objects that are provably corrupt/incomplete are removed; anything
     * that could not be checked because of an I/O error is left alone.
     */
    @Synchronized
    fun recoverOrphans(): OrphanReport {
        var adopted = 0; var removed = 0; var kept = 0
        val newEntries = ArrayList<VideoEntry>()
        for (name in scan().orphans) {
            try {
                val r = storage.openRead(name).use { ChunkedCipher.decrypt(key, it, NullOutputStream) }
                newEntries.add(
                    VideoEntry(
                        id = VaultCrypto.randomHex(), objectName = name, thumbObject = null,
                        name = "Recovered ${newEntries.size + 1}.mp4", folder = "Recovered", mime = "video/mp4",
                        size = r.plainLength, sha256 = r.sha256, durationMs = 0, addedAt = System.currentTimeMillis(),
                    )
                )
            } catch (e: CorruptDataException) {
                if (storage.delete(name)) removed++ else kept++
            } catch (e: AuthenticationException) {
                if (storage.delete(name)) removed++ else kept++
            } catch (e: Exception) {
                kept++
            }
        }
        if (newEntries.isNotEmpty()) {
            commit { it + newEntries }
            adopted = newEntries.size
        }
        return OrphanReport(adopted, removed, kept)
    }

    // ---- delete & duplicates ------------------------------------------------------------------------

    /** Removes entries from the index first, then deletes their objects. Returns objects that could not be deleted. */
    @Synchronized
    fun delete(ids: Set<String>): Int {
        val victims = snapshot.entries.filter { it.id in ids }
        if (victims.isEmpty()) return 0
        commit { list -> list.filter { it.id !in ids } }
        var leftovers = 0
        for (v in victims) {
            if (!runCatching { storage.delete(v.objectName) }.getOrDefault(false)) leftovers++
            v.thumbObject?.let { runCatching { storage.delete(it) } }
        }
        return leftovers
    }

    /**
     * Finds duplicate groups by re-hashing candidate objects (grouped by recorded hash+size first).
     * Objects that fail authentication or hashing are excluded, never treated as duplicates.
     */
    fun findDuplicates(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Pair<List<DuplicateGroup>, Int> {
        val candidates = entries.groupBy { it.sha256 + ":" + it.size }.values.filter { it.size >= 2 }.flatten()
        val verified = ArrayList<VideoEntry>()
        var corrupt = 0
        candidates.forEachIndexed { i, e ->
            onProgress(i, candidates.size)
            if (verify(e.id)) verified.add(e) else corrupt++
        }
        onProgress(candidates.size, candidates.size)
        val groups = verified.groupBy { it.sha256 + ":" + it.size }.values.filter { it.size >= 2 }
            .map { g -> DuplicateGroup(g[0].sha256, g.sortedBy { it.addedAt }) }
        return groups to corrupt
    }

    /** Deletes [removeIds] from a duplicate group while guaranteeing that a verified copy ([keepId]) remains. */
    @Synchronized
    fun removeDuplicates(keepId: String, removeIds: Set<String>): Int {
        val keeper = entry(keepId) ?: throw VaultException("The copy to keep no longer exists")
        if (removeIds.isEmpty()) return 0
        if (keepId in removeIds) throw VaultException("Cannot delete the copy that is being kept")
        for (id in removeIds) {
            val e = entry(id) ?: continue
            if (e.sha256 != keeper.sha256 || e.size != keeper.size) throw VaultException("Selected video is not a duplicate of the kept copy")
        }
        if (!verify(keepId)) throw VaultException("The copy to keep failed verification; nothing was deleted")
        return delete(removeIds)
    }

    // ---- recovery password ----------------------------------------------------------------------------

    /**
     * Re-wraps the master key with a new password. Writes the new slot, verifies that it opens with the
     * new password, and only then removes the old slot.
     */
    @Synchronized
    fun changeRecoveryPassword(old: CharArray, new: CharArray, iterations: Int = RecoveryFile.DEFAULT_ITERATIONS) {
        val present = RECOVERY_SLOTS.filter { storage.exists(it) }
        var currentSlot: String? = null
        var gen = 0L
        for (slot in present) {
            try {
                val o = RecoveryFile.open(storage.readAll(slot), old)
                if (currentSlot == null || o.generation > gen) { currentSlot = slot; gen = o.generation }
            } catch (e: WrongPasswordException) {
                // try other slot
            }
        }
        if (currentSlot == null) throw WrongPasswordException()
        val target = if (currentSlot == RECOVERY_A) RECOVERY_B else RECOVERY_A
        val data = RecoveryFile.create(key, new, gen + 1, iterations)
        storage.write(target, true).use { it.write(data) }
        val check = RecoveryFile.open(storage.readAll(target), new)
        if (!check.masterKey.contentEquals(key)) throw VaultException("New recovery file did not verify; old password still valid")
        for (slot in RECOVERY_SLOTS) if (slot != target) runCatching { storage.delete(slot) }
    }
}
