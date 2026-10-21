package com.lita.app.crypto

import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher

/** Wrong password, or the recovery data was modified (AES-GCM cannot tell these apart). */
class WrongPasswordException : GeneralSecurityException("Wrong recovery password, or the recovery data was modified")

/** The file is not a valid Lita recovery file (bad magic, version, KDF parameters or length). */
class RecoveryFormatException(message: String) : IOException(message)

/**
 * Portable recovery metadata. A random 256-bit vault master key is wrapped (AES-256-GCM) with a key
 * derived from the user's recovery password using PBKDF2-HMAC-SHA-256 and a random 16-byte salt.
 *
 * Layout (94 bytes): "LTRC" | version(1)=1 | kdf(1)=1 | generation(8) | iterations(4) | salt(16) | nonce(12) | wrappedKey(32+16)
 * The header (everything before the nonce) is authenticated as AAD.
 */
object RecoveryFile {
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_ITERATIONS = 10_000
    const val MAX_ITERATIONS = 20_000_000
    private const val AAD_LEN = 4 + 1 + 1 + 8 + 4 + 16
    const val TOTAL_LEN = AAD_LEN + VaultCrypto.NONCE_BYTES + VaultCrypto.KEY_BYTES + VaultCrypto.TAG_BYTES
    private val MAGIC = byteArrayOf('L'.code.toByte(), 'T'.code.toByte(), 'R'.code.toByte(), 'C'.code.toByte())

    class Opened(val masterKey: ByteArray, val generation: Long)

    fun create(masterKey: ByteArray, password: CharArray, generation: Long, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(masterKey.size == VaultCrypto.KEY_BYTES) { "master key must be 32 bytes" }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "iteration count out of range" }
        val salt = VaultCrypto.randomBytes(16)
        val nonce = VaultCrypto.randomBytes(VaultCrypto.NONCE_BYTES)
        val aad = ByteBuffer.allocate(AAD_LEN).put(MAGIC).put(1).put(1).putLong(generation).putInt(iterations).put(salt).array()
        val kek = VaultCrypto.pbkdf2(password, salt, iterations)
        try {
            val ct = VaultCrypto.cipher(Cipher.ENCRYPT_MODE, kek, nonce, aad).doFinal(masterKey)
            return ByteBuffer.allocate(TOTAL_LEN).put(aad).put(nonce).put(ct).array()
        } finally {
            kek.fill(0)
        }
    }

    private fun checkStructure(data: ByteArray): ByteBuffer {
        if (data.size != TOTAL_LEN) throw RecoveryFormatException("Recovery file has an unexpected size")
        val bb = ByteBuffer.wrap(data)
        val magic = ByteArray(4); bb.get(magic)
        if (!magic.contentEquals(MAGIC)) throw RecoveryFormatException("Not a Lita recovery file")
        if (bb.get().toInt() != 1) throw RecoveryFormatException("Unsupported recovery file version")
        if (bb.get().toInt() != 1) throw RecoveryFormatException("Unsupported key-derivation scheme")
        return bb
    }

    fun generationOf(data: ByteArray): Long = checkStructure(data).getLong()

    fun open(data: ByteArray, password: CharArray): Opened {
        val bb = checkStructure(data)
        val generation = bb.getLong()
        val iterations = bb.getInt()
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) throw RecoveryFormatException("Invalid iteration count")
        val salt = ByteArray(16); bb.get(salt)
        val nonce = ByteArray(VaultCrypto.NONCE_BYTES); bb.get(nonce)
        val ct = ByteArray(VaultCrypto.KEY_BYTES + VaultCrypto.TAG_BYTES); bb.get(ct)
        val kek = VaultCrypto.pbkdf2(password, salt, iterations)
        try {
            val key = VaultCrypto.cipher(Cipher.DECRYPT_MODE, kek, nonce, data.copyOfRange(0, AAD_LEN)).doFinal(ct)
            return Opened(key, generation)
        } catch (e: BadPaddingException) {
            throw WrongPasswordException()
        } finally {
            kek.fill(0)
        }
    }
}
