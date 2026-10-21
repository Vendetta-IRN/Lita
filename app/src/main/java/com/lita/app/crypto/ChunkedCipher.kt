package com.lita.app.crypto

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.BadPaddingException
import javax.crypto.Cipher

/** Positional, thread-compatible byte source (a vault object on storage, or an in-memory array in tests). */
interface RandomAccessSource : Closeable {
    val size: Long
    /** Reads up to [len] bytes at [position]; returns the count, or -1 at end of data. */
    fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int
}

class ByteArraySource(private val data: ByteArray) : RandomAccessSource {
    override val size: Long get() = data.size.toLong()
    override fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(len.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), dst, off, n)
        return n
    }
    override fun close() {}
}

/**
 * Streaming authenticated encryption (AES-256-GCM, STREAM-style).
 *
 * Object layout:
 *   header(25) = "LTV1" | version(1) | chunkSize(4) | noncePrefix(8) | plaintextLength(8)
 *   then N chunks, each ciphertext(chunkLen) || tag(16)
 * Chunk nonce = noncePrefix(8) || chunkIndex(4). AAD = header || chunkIndex(8) || finalFlag(1).
 * The header is authenticated by every chunk, the index prevents re-ordering and the final flag
 * prevents silent truncation. Chunks allow random access, so playback never needs plaintext files.
 */
object ChunkedCipher {
    const val DEFAULT_CHUNK = 256 * 1024
    const val HEADER_LEN = 25
    private const val MAX_CHUNK = 16 * 1024 * 1024
    private const val MIN_CHUNK = 4 * 1024
    private val MAGIC = byteArrayOf('L'.code.toByte(), 'T'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    private const val VERSION: Byte = 1

    data class Result(val sha256: String, val plainLength: Long)

    class Header(val raw: ByteArray, val chunkSize: Int, val prefix: ByteArray, val plainLength: Long) {
        val chunks: Long get() = chunkCount(plainLength, chunkSize)
    }

    fun chunkCount(plainLen: Long, chunkSize: Int): Long =
        if (plainLen == 0L) 1L else (plainLen + chunkSize - 1) / chunkSize

    fun encryptedLength(plainLen: Long, chunkSize: Int = DEFAULT_CHUNK): Long =
        HEADER_LEN + plainLen + chunkCount(plainLen, chunkSize) * VaultCrypto.TAG_BYTES

    private fun buildHeader(chunkSize: Int, prefix: ByteArray, plainLen: Long): ByteArray {
        val bb = ByteBuffer.allocate(HEADER_LEN)
        bb.put(MAGIC).put(VERSION).putInt(chunkSize).put(prefix).putLong(plainLen)
        return bb.array()
    }

    fun parseHeader(raw: ByteArray): Header {
        if (raw.size != HEADER_LEN) throw CorruptDataException("Bad header length")
        val bb = ByteBuffer.wrap(raw)
        val magic = ByteArray(4); bb.get(magic)
        if (!magic.contentEquals(MAGIC)) throw CorruptDataException("Not a Lita encrypted object")
        if (bb.get() != VERSION) throw CorruptDataException("Unsupported object version")
        val chunkSize = bb.getInt()
        if (chunkSize < MIN_CHUNK || chunkSize > MAX_CHUNK) throw CorruptDataException("Invalid chunk size")
        val prefix = ByteArray(8); bb.get(prefix)
        val plainLen = bb.getLong()
        if (plainLen < 0) throw CorruptDataException("Invalid length")
        return Header(raw, chunkSize, prefix, plainLen)
    }

    internal fun chunkNonce(prefix: ByteArray, index: Long): ByteArray =
        ByteBuffer.allocate(VaultCrypto.NONCE_BYTES).put(prefix).putInt(index.toInt()).array()

    internal fun chunkAad(header: ByteArray, index: Long, final: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 9).put(header).putLong(index).put(if (final) 1 else 0).array()

    private fun readFully(input: InputStream, buf: ByteArray, len: Int) {
        var done = 0
        while (done < len) {
            val n = input.read(buf, done, len - done)
            if (n < 0) throw EOFException()
            done += n
        }
    }

    /** Encrypts exactly [plainLen] bytes from [input]. Fails if the source is shorter or longer. */
    fun encrypt(key: ByteArray, input: InputStream, plainLen: Long, out: OutputStream, chunkSize: Int = DEFAULT_CHUNK): Result {
        require(plainLen >= 0) { "negative length" }
        val prefix = VaultCrypto.randomBytes(8)
        val header = buildHeader(chunkSize, prefix, plainLen)
        out.write(header)
        val md = MessageDigest.getInstance("SHA-256")
        val chunks = chunkCount(plainLen, chunkSize)
        val buf = ByteArray(chunkSize)
        var remaining = plainLen
        var i = 0L
        while (i < chunks) {
            val len = minOf(remaining, chunkSize.toLong()).toInt()
            try {
                readFully(input, buf, len)
            } catch (e: EOFException) {
                throw IOException("Source ended before the declared size was read")
            }
            md.update(buf, 0, len)
            val c = VaultCrypto.cipher(Cipher.ENCRYPT_MODE, key, chunkNonce(prefix, i), chunkAad(header, i, i == chunks - 1))
            out.write(c.doFinal(buf, 0, len))
            remaining -= len
            i++
        }
        if (input.read() != -1) throw IOException("Source is longer than its declared size")
        out.flush()
        return Result(md.digest().toHex(), plainLen)
    }

    /** Decrypts and authenticates every chunk of [input] into [out]; returns plaintext hash and length. */
    fun decrypt(key: ByteArray, input: InputStream, out: OutputStream): Result {
        try {
            val raw = ByteArray(HEADER_LEN)
            readFully(input, raw, HEADER_LEN)
            val h = parseHeader(raw)
            val md = MessageDigest.getInstance("SHA-256")
            val enc = ByteArray(h.chunkSize + VaultCrypto.TAG_BYTES)
            var remaining = h.plainLength
            val chunks = h.chunks
            var i = 0L
            while (i < chunks) {
                val plainThis = minOf(remaining, h.chunkSize.toLong()).toInt()
                readFully(input, enc, plainThis + VaultCrypto.TAG_BYTES)
                val pt: ByteArray = try {
                    VaultCrypto.cipher(Cipher.DECRYPT_MODE, key, chunkNonce(h.prefix, i), chunkAad(raw, i, i == chunks - 1))
                        .doFinal(enc, 0, plainThis + VaultCrypto.TAG_BYTES)
                } catch (e: BadPaddingException) {
                    throw AuthenticationException("Chunk $i failed authentication", e)
                }
                md.update(pt)
                out.write(pt)
                remaining -= plainThis
                i++
            }
            if (input.read() != -1) throw CorruptDataException("Unexpected trailing data")
            out.flush()
            return Result(md.digest().toHex(), h.plainLength)
        } catch (e: EOFException) {
            throw CorruptDataException("Encrypted object is truncated")
        }
    }
}

/** Random-access decrypting reader used by playback and thumbnail extraction. Closing closes the source. */
class ChunkedReader(private val source: RandomAccessSource, private val key: ByteArray) : Closeable {
    private val header: ChunkedCipher.Header
    val plainLength: Long
    private var cachedIndex = -1L
    private var cachedPlain: ByteArray? = null

    init {
        try {
            val raw = ByteArray(ChunkedCipher.HEADER_LEN)
            readFullyAt(0, raw)
            header = ChunkedCipher.parseHeader(raw)
            plainLength = header.plainLength
            val expected = ChunkedCipher.encryptedLength(plainLength, header.chunkSize)
            if (source.size != expected) throw CorruptDataException("Encrypted object has an unexpected size")
        } catch (e: Exception) {
            source.close()
            throw e
        }
    }

    private fun readFullyAt(position: Long, dst: ByteArray) {
        var done = 0
        while (done < dst.size) {
            val n = source.read(position + done, dst, done, dst.size - done)
            if (n <= 0) throw CorruptDataException("Encrypted object is truncated")
            done += n
        }
    }

    private fun loadChunk(idx: Long): ByteArray {
        if (idx == cachedIndex) return cachedPlain!!
        val cs = header.chunkSize
        val last = idx == header.chunks - 1
        val plainThis = if (last) (plainLength - idx * cs).toInt() else cs
        val enc = ByteArray(plainThis + VaultCrypto.TAG_BYTES)
        readFullyAt(ChunkedCipher.HEADER_LEN + idx * (cs + VaultCrypto.TAG_BYTES).toLong(), enc)
        val pt = try {
            VaultCrypto.cipher(Cipher.DECRYPT_MODE, key, ChunkedCipher.chunkNonce(header.prefix, idx), ChunkedCipher.chunkAad(header.raw, idx, last))
                .doFinal(enc)
        } catch (e: BadPaddingException) {
            throw AuthenticationException("Chunk $idx failed authentication", e)
        }
        cachedIndex = idx
        cachedPlain = pt
        return pt
    }

    /** Returns bytes read, or -1 if [position] is at or beyond the end. */
    @Synchronized
    fun readAt(position: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (position < 0 || position >= plainLength) return -1
        val end = minOf(plainLength, position + len)
        var pos = position
        var written = 0
        while (pos < end) {
            val idx = pos / header.chunkSize
            val plain = loadChunk(idx)
            val inChunk = (pos - idx * header.chunkSize).toInt()
            val n = minOf(plain.size - inChunk, (end - pos).toInt())
            System.arraycopy(plain, inChunk, dst, off + written, n)
            pos += n
            written += n
        }
        return written
    }

    override fun close() = source.close()
}
