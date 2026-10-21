package com.lita.app.vault

import com.lita.app.crypto.RandomAccessSource
import java.io.InputStream
import java.io.OutputStream

/**
 * Flat file store for the vault folder. All files live in one directory so folder structure never
 * appears on disk; it exists only inside the encrypted index.
 */
interface VaultStorage {
    /** Re-reads the directory listing from the provider. */
    fun refresh() {}
    fun list(): List<String>
    fun exists(name: String): Boolean = name in list()
    fun size(name: String): Long
    fun openRead(name: String): InputStream
    fun openRandomAccess(name: String): RandomAccessSource
    /** Opens [name] for writing. Without [overwrite] an existing file is an error. */
    fun write(name: String, overwrite: Boolean): OutputStream
    fun delete(name: String): Boolean
}

fun VaultStorage.readAll(name: String): ByteArray = openRead(name).use { it.readBytes() }

object NullOutputStream : OutputStream() {
    override fun write(b: Int) {}
    override fun write(b: ByteArray, off: Int, len: Int) {}
}
