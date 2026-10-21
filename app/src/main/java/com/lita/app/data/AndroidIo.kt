package com.lita.app.data

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.lita.app.vault.ImportSource
import com.lita.app.vault.VaultRepository
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class UriImportSource(
    private val resolver: ContentResolver,
    val uri: Uri,
    override val name: String,
    override val mime: String,
    override val size: Long,
) : ImportSource {
    override fun open(): InputStream = resolver.openInputStream(uri) ?: throw IOException("Cannot open the source file")
}

object AndroidIo {
    private val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "3g2", "avi", "ts", "mpg", "mpeg", "wmv", "flv")
    const val EXPORT_DIR = "Movies/Lita Export"

    fun isVideo(mime: String?, name: String): Boolean =
        (mime != null && mime.startsWith("video/")) || name.substringAfterLast('.', "").lowercase() in VIDEO_EXT

    /** Describes a document picked with the system file picker. Returns null if it cannot be read. */
    fun describe(resolver: ContentResolver, uri: Uri): UriImportSource? {
        var name = "video"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                it.getString(0)?.let { n -> name = n }
                if (!it.isNull(1)) size = it.getLong(1)
            }
        }
        if (size < 0) {
            size = try { resolver.openAssetFileDescriptor(uri, "r")?.use { a -> a.length } ?: -1L } catch (e: Exception) { -1L }
        }
        return UriImportSource(resolver, uri, name, resolver.getType(uri) ?: "", size)
    }

    class FolderFile(val source: UriImportSource, val folder: String)
    class FolderScan(val files: List<FolderFile>, val skippedNotVideo: Int, val inaccessible: Int)

    /** Walks a SAF tree and lists supported video files with their relative folder path. */
    fun walkTree(resolver: ContentResolver, treeUri: Uri, rootLabel: String): FolderScan {
        val files = ArrayList<FolderFile>()
        var skipped = 0
        var bad = 0
        fun walk(docId: String, path: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            )
            val rows = ArrayList<Array<String?>>()
            try {
                resolver.query(children, cols, null, null, null)?.use {
                    while (it.moveToNext()) rows.add(arrayOf(it.getString(0), it.getString(1), it.getString(2), if (it.isNull(3)) null else it.getLong(3).toString()))
                }
            } catch (e: Exception) { bad++; return }
            for (r in rows) {
                val id = r[0] ?: continue
                val name = r[1] ?: "video"
                val mime = r[2] ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { walk(id, if (path.isEmpty()) name else "$path/$name"); continue }
                if (!isVideo(mime, name)) { skipped++; continue }
                val size = r[3]?.toLongOrNull()
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                if (size == null) { bad++; continue }
                files.add(FolderFile(UriImportSource(resolver, uri, name, mime, size), path))
            }
        }
        walk(DocumentsContract.getTreeDocumentId(treeUri), rootLabel)
        return FolderScan(files, skipped, bad)
    }

    /** Deletes an original after a verified import. Returns false if the provider does not allow it. */
    fun deleteOriginal(resolver: ContentResolver, uri: Uri): Boolean = try {
        val path = uri.path
        when {
            uri.scheme == "file" && path != null -> File(path).delete()
            uri.authority == MediaStore.AUTHORITY -> resolver.delete(uri, null, null) > 0
            else -> DocumentsContract.deleteDocument(resolver, uri)
        }
    } catch (e: Exception) { false }

    /** Android 7–9: plain file copy into Movies/Lita Export (needs the storage permission). */
    private fun exportLegacy(repo: VaultRepository, id: String, onProgress: (Long) -> Unit): String {
        val e = repo.entry(id) ?: throw IOException("Video is not in the vault")
        val dir = File(@Suppress("DEPRECATION") Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Lita Export")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create the export folder (is storage permission granted?)")
        val safe = e.name.replace('/', '_').replace('\\', '_')
        val base = safe.substringBeforeLast('.', safe)
        val ext = if (safe.contains('.')) "." + safe.substringAfterLast('.') else ""
        var f = File(dir, safe)
        var n = 1
        while (f.exists()) { f = File(dir, "$base ($n)$ext"); n++ }
        try {
            BufferedOutputStream(CountingOut(FileOutputStream(f), onProgress), 256 * 1024).use { repo.export(id, it) }
        } catch (t: Throwable) {
            f.delete(); throw t
        }
        return f.name
    }

    private class CountingOut(private val inner: OutputStream, private val cb: (Long) -> Unit) : OutputStream() {
        private var total = 0L
        override fun write(b: Int) { inner.write(b); total++; cb(total) }
        override fun write(b: ByteArray, off: Int, len: Int) { inner.write(b, off, len); total += len; cb(total) }
        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }

    /**
     * Writes a decrypted copy to Movies/Lita Export via MediaStore (no storage permission needed on
     * Android 10+). MediaStore resolves name collisions. A failed export removes the partial copy.
     * Returns the final display name.
     */
    fun exportToMovies(resolver: ContentResolver, repo: VaultRepository, id: String, onProgress: (Long) -> Unit): String {
        if (Build.VERSION.SDK_INT < 29) return exportLegacy(repo, id, onProgress)
        val e = repo.entry(id) ?: throw IOException("Video is not in the vault")
        val safeName = e.name.replace('/', '_').replace('\\', '_')
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, safeName)
            put(MediaStore.Video.Media.MIME_TYPE, e.mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, EXPORT_DIR)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the export file")
        try {
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Could not open the export file")
            BufferedOutputStream(CountingOut(out, onProgress), 256 * 1024).use { repo.export(id, it) }
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return resolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: safeName
    }
}
