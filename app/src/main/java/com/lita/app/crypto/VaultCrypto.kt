package com.lita.app.crypto

import java.io.IOException
import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Authentication tag mismatch: wrong key or modified data. */
class AuthenticationException(message: String, cause: Throwable? = null) : GeneralSecurityException(message, cause)

/** Structurally invalid or truncated data. */
class CorruptDataException(message: String) : IOException(message)

object VaultCrypto {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    private const val TAG_BITS = 128
    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
    fun randomHex(bytes: Int = 16): String = randomBytes(bytes).toHex()
    fun newMasterKey(): ByteArray = randomBytes(KEY_BYTES)

    /** PBKDF2-HMAC-SHA-256, 256-bit output. */
    fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } catch (e: NoSuchAlgorithmException) {
            return pbkdf2Manual(password, salt, iterations)   // Android 7.x only; produces identical output
        } finally {
            spec.clearPassword()
        }
    }

    /** RFC 8018 PBKDF2-HMAC-SHA-256, one 32-byte block (UTF-8 password), identical to the platform implementation. */
    internal fun pbkdf2Manual(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val pw = String(password).toByteArray(Charsets.UTF_8)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pw, "HmacSHA256"))
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            return t
        } finally {
            pw.fill(0)
        }
    }

    internal fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        c.updateAAD(aad)
        return c
    }

    /** Returns nonce(12) || ciphertext || tag(16). A fresh random nonce is used for every call. */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = randomBytes(NONCE_BYTES)
        return nonce + cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)
    }

    fun open(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        if (blob.size < NONCE_BYTES + TAG_BYTES) throw CorruptDataException("Encrypted blob is too short")
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        try {
            return cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
        } catch (e: BadPaddingException) { // includes AEADBadTagException
            throw AuthenticationException("Authentication failed", e)
        }
    }
}

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 15])
    }
    return sb.toString()
}
