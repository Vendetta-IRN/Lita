package com.lita.app

import com.lita.app.crypto.ByteArraySource
import com.lita.app.crypto.RandomAccessSource
import com.lita.app.vault.ImportSource
import com.lita.app.vault.VaultStorage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.random.Random

/** In-memory vault folder with optional failure injection. */
class InMemoryVaultStorage : VaultStorage {
    val files = LinkedHashMap<String, ByteArray>()
    /** If set, the next write to a name starting with this prefix fails after that many bytes. */
    var failWriteAfterBytes: Int? = null
    /** If true, the next completed object write is stored with one flipped bit (simulates silent corruption). */
    var corruptNextWrite = false

    override fun list(): List<String> = files.keys.toList()
    override fun size(name: String): Long = (files[name] ?: throw IOException("missing")).size.toLong()
    override fun openRead(name: String): InputStream = ByteArrayInputStream(files[name] ?: throw IOException("missing"))
    override fun openRandomAccess(name: String): RandomAccessSource = ByteArraySource(files[name] ?: throw IOException("missing"))
    override fun write(name: String, overwrite: Boolean): OutputStream {
        if (!overwrite && files.containsKey(name)) throw IOException("exists")
        val limit = failWriteAfterBytes
        failWriteAfterBytes = null
        val corrupt = corruptNextWrite && name != "lita-index-a" && name != "lita-index-b"
        if (corrupt) corruptNextWrite = false
        return object : ByteArrayOutputStream() {
            override fun write(b: Int) { check(1); super.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { check(len); super.write(b, off, len) }
            private fun check(n: Int) { if (limit != null && size() + n > limit) throw IOException("injected write failure") }
            override fun close() {
                val data = toByteArray()
                if (corrupt && data.size > 40) data[40] = (data[40].toInt() xor 1).toByte()
                files[name] = data
            }
        }
    }
    override fun delete(name: String): Boolean = files.remove(name) != null
}

class BytesSource(override val name: String, val bytes: ByteArray, override val mime: String = "video/mp4", private val failAt: Int? = null) : ImportSource {
    override val size: Long get() = bytes.size.toLong()
    override fun open(): InputStream {
        val base = ByteArrayInputStream(bytes)
        val f = failAt ?: return base
        return object : InputStream() {
            var n = 0
            override fun read(): Int { if (n++ >= f) throw IOException("injected read failure"); return base.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n >= f) throw IOException("injected read failure")
                val r = base.read(b, off, minOf(len, f - n)); if (r > 0) n += r; return r
            }
        }
    }
}

fun randomBytes(n: Int, seed: Int = 1): ByteArray = Random(seed).nextBytes(n)

const val TEST_ITERATIONS = 10_000
