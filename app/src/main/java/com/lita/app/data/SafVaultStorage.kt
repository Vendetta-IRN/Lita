package com.lita.app.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.lita.app.crypto.RandomAccessSource
import com.lita.app.vault.VaultStorage
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Vault storage backed by a user-selected Storage Access Framework tree (flat directory). */
class SafVaultStorage(context: Context, private val treeUri: Uri) : VaultStorage {
    private val resolver = context.applicationContext.contentResolver
    private val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
    private val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)

    private class Child(var docId: String, var size: Long)

    private var cache: MutableMap<String, Child>? = null

    @Synchronized override fun refresh() { cache = load() }
    @Synchronized private fun table(): MutableMap<String, Child> = cache ?: load().also { cache = it }

    private fun load(): MutableMap<String, Child> {
        val out = HashMap<String, Child>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val c = resolver.query(children, cols, null, null, null) ?: throw IOException("The vault folder is not accessible")
        c.use {
            while (it.moveToNext()) {
                if (it.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                out[it.getString(1)] = Child(it.getString(0), if (it.isNull(2)) -1L else it.getLong(2))
            }
        }
        return out
    }

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
    private fun child(name: String): Child = table()[name] ?: throw IOException("Vault file not found")

    private fun queryString(uri: Uri, col: String): String? =
        resolver.query(uri, arrayOf(col), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun queryLong(uri: Uri, col: String): Long =
        resolver.query(uri, arrayOf(col), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L

    @Synchronized override fun list(): List<String> = table().keys.toList()
    @Synchronized override fun exists(name: String): Boolean = table().containsKey(name)
    @Synchronized override fun size(name: String): Long = child(name).size

    override fun openRead(name: String): InputStream {
        val uri = synchronized(this) { docUri(child(name).docId) }
        return resolver.openInputStream(uri) ?: throw IOException("Cannot open vault file")
    }

    override fun openRandomAccess(name: String): RandomAccessSource {
        val uri = synchronized(this) { docUri(child(name).docId) }
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open vault file")
        return try {
            ChannelSource(pfd)
        } catch (e: Exception) {
            runCatching { pfd.close() }
            throw IOException("This storage provider does not support random access", e)
        }
    }

    @Synchronized
    override fun write(name: String, overwrite: Boolean): OutputStream {
        val t = table()
        val existing = t[name]
        val uri: Uri
        if (existing != null) {
            if (!overwrite) throw IOException("Vault file already exists")
            uri = docUri(existing.docId)
        } else {
            val created = DocumentsContract.createDocument(resolver, rootUri, "application/octet-stream", name)
                ?: throw IOException("Could not create a file in the vault folder")
            var finalUri = created
            if (queryString(created, DocumentsContract.Document.COLUMN_DISPLAY_NAME) != name) {
                val renamed = try { DocumentsContract.renameDocument(resolver, created, name) } catch (e: Exception) { null }
                if (renamed == null || queryString(renamed, DocumentsContract.Document.COLUMN_DISPLAY_NAME) != name) {
                    runCatching { DocumentsContract.deleteDocument(resolver, renamed ?: created) }
                    throw IOException("The storage provider changed the file name; this folder cannot hold a vault")
                }
                finalUri = renamed
            }
            t[name] = Child(DocumentsContract.getDocumentId(finalUri), 0L)
            uri = finalUri
        }
        val raw = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open vault file for writing")
        return object : BufferedOutputStream(raw, 256 * 1024) {
            override fun close() {
                try { super.close() } finally {
                    val sz = queryLong(uri, DocumentsContract.Document.COLUMN_SIZE)
                    synchronized(this@SafVaultStorage) { table()[name]?.size = sz }
                }
            }
        }
    }

    @Synchronized
    override fun delete(name: String): Boolean {
        val c = table()[name] ?: return false
        return try {
            val ok = DocumentsContract.deleteDocument(resolver, docUri(c.docId))
            if (ok) table().remove(name)
            ok
        } catch (e: java.io.FileNotFoundException) {
            table().remove(name); false
        }
    }

    private class ChannelSource(private val pfd: ParcelFileDescriptor) : RandomAccessSource {
        private val input = FileInputStream(pfd.fileDescriptor)
        private val channel: FileChannel = input.channel
        override val size: Long = channel.size()
        init { channel.position() } // throws on non-seekable descriptors (pipes)
        override fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int =
            channel.read(ByteBuffer.wrap(dst, off, len), position)
        override fun close() { runCatching { input.close() }; runCatching { pfd.close() } }
    }
}
