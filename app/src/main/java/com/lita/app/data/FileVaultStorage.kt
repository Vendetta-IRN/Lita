package com.lita.app.data

import com.lita.app.crypto.RandomAccessSource
import com.lita.app.vault.VaultStorage
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/** Vault stored in a normal folder (needs "All files access"). Faster and simpler than SAF. */
class FileVaultStorage(private val dir: File) : VaultStorage {
    init {
        dir.mkdirs()
        if (!dir.isDirectory || !dir.canRead()) throw IOException("The vault folder is not accessible")
    }

    private fun f(name: String) = File(dir, name)

    override fun list(): List<String> =
        (dir.list() ?: throw IOException("The vault folder cannot be read")).filter { f(it).isFile }

    override fun exists(name: String): Boolean = f(name).isFile
    override fun size(name: String): Long = f(name).let { if (it.isFile) it.length() else throw IOException("Vault file not found") }
    override fun openRead(name: String): InputStream = FileInputStream(f(name))

    override fun openRandomAccess(name: String): RandomAccessSource {
        val raf = RandomAccessFile(f(name), "r")
        return object : RandomAccessSource {
            override val size: Long = raf.length()
            override fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int =
                synchronized(raf) { raf.seek(position); raf.read(dst, off, len) }
            override fun close() { runCatching { raf.close() } }
        }
    }

    override fun write(name: String, overwrite: Boolean): OutputStream {
        val file = f(name)
        if (file.exists() && !overwrite) throw IOException("Vault file already exists")
        val fos = FileOutputStream(file, false)
        return object : BufferedOutputStream(fos, 256 * 1024) {
            override fun close() {
                try { flush(); fos.fd.sync() } finally { super.close() }
            }
        }
    }

    override fun delete(name: String): Boolean = f(name).delete()
}
