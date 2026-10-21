package com.lita.app

import com.lita.app.crypto.AuthenticationException
import com.lita.app.crypto.ByteArraySource
import com.lita.app.crypto.ChunkedCipher
import com.lita.app.crypto.ChunkedReader
import com.lita.app.crypto.CorruptDataException
import com.lita.app.crypto.RecoveryFile
import com.lita.app.crypto.RecoveryFormatException
import com.lita.app.crypto.VaultCrypto
import com.lita.app.crypto.WrongPasswordException
import com.lita.app.vault.NullOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

class CryptoTest {
    private val key = VaultCrypto.newMasterKey()

    private fun encrypt(data: ByteArray, chunk: Int = 4096): ByteArray {
        val out = ByteArrayOutputStream()
        ChunkedCipher.encrypt(key, ByteArrayInputStream(data), data.size.toLong(), out, chunk)
        return out.toByteArray()
    }

    private fun decrypt(blob: ByteArray, k: ByteArray = key): ByteArray {
        val out = ByteArrayOutputStream()
        ChunkedCipher.decrypt(k, ByteArrayInputStream(blob), out)
        return out.toByteArray()
    }

    @Test fun sealOpenRoundTrip() {
        val msg = "hello vault".toByteArray()
        val blob = VaultCrypto.seal(key, msg, "aad".toByteArray())
        assertArrayEquals(msg, VaultCrypto.open(key, blob, "aad".toByteArray()))
    }

    @Test fun sealUsesFreshNonce() {
        val a = VaultCrypto.seal(key, ByteArray(16), ByteArray(0))
        val b = VaultCrypto.seal(key, ByteArray(16), ByteArray(0))
        assertNotEquals(a.copyOf(12).toList(), b.copyOf(12).toList())
    }

    @Test fun sealAuthenticationTagFailure() {
        val blob = VaultCrypto.seal(key, ByteArray(64) { it.toByte() }, ByteArray(0))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        try { VaultCrypto.open(key, blob, ByteArray(0)); fail() } catch (e: AuthenticationException) { }
    }

    @Test fun sealWrongAadFails() {
        val blob = VaultCrypto.seal(key, ByteArray(8), "a".toByteArray())
        try { VaultCrypto.open(key, blob, "b".toByteArray()); fail() } catch (e: AuthenticationException) { }
    }

    @Test fun chunkedRoundTripVariousSizes() {
        for (n in listOf(0, 1, 4095, 4096, 4097, 3 * 4096, 20_000)) {
            val data = randomBytes(n, n)
            val enc = encrypt(data)
            assertEquals(ChunkedCipher.encryptedLength(n.toLong(), 4096), enc.size.toLong())
            assertArrayEquals("size $n", data, decrypt(enc))
        }
    }

    @Test fun chunkedReportsHashAndLength() {
        val data = randomBytes(10_000)
        val out = ByteArrayOutputStream()
        val r = ChunkedCipher.encrypt(key, ByteArrayInputStream(data), data.size.toLong(), out, 4096)
        val expected = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        assertEquals(expected, r.sha256)
        assertEquals(10_000L, r.plainLength)
    }

    @Test fun ciphertextDoesNotContainPlaintext() {
        val data = ByteArray(8192) { 'A'.code.toByte() }
        val enc = encrypt(data)
        assertFalse(String(enc, Charsets.ISO_8859_1).contains("A".repeat(64)))
    }

    @Test fun flippedBitInAnyChunkFailsAuthentication() {
        val enc = encrypt(randomBytes(12_000))
        for (pos in listOf(ChunkedCipher.HEADER_LEN + 5, enc.size / 2, enc.size - 1)) {
            val bad = enc.copyOf(); bad[pos] = (bad[pos].toInt() xor 1).toByte()
            try { decrypt(bad); fail("pos $pos") } catch (e: AuthenticationException) { }
        }
    }

    @Test fun headerTamperingIsDetected() {
        val enc = encrypt(randomBytes(9_000))
        val bad = enc.copyOf(); bad[ChunkedCipher.HEADER_LEN - 1] = (bad[ChunkedCipher.HEADER_LEN - 1].toInt() xor 1).toByte() // plaintext length
        try { decrypt(bad); fail() } catch (e: java.io.IOException) { } catch (e: AuthenticationException) { }
    }

    @Test fun truncationIsDetected() {
        val enc = encrypt(randomBytes(12_000))
        try { decrypt(enc.copyOf(enc.size - 20)); fail() } catch (e: CorruptDataException) { }
        try { decrypt(enc.copyOf(ChunkedCipher.HEADER_LEN + 10)); fail() } catch (e: CorruptDataException) { }
    }

    @Test fun trailingDataIsRejected() {
        val enc = encrypt(randomBytes(5_000)) + byteArrayOf(1, 2, 3)
        try { decrypt(enc); fail() } catch (e: CorruptDataException) { }
    }

    @Test fun reorderedChunksAreDetected() {
        val enc = encrypt(randomBytes(3 * 4096))
        val cs = 4096 + 16
        val a = ChunkedCipher.HEADER_LEN
        val swapped = enc.copyOf()
        System.arraycopy(enc, a + cs, swapped, a, cs)
        System.arraycopy(enc, a, swapped, a + cs, cs)
        try { decrypt(swapped); fail() } catch (e: AuthenticationException) { }
    }

    @Test fun wrongKeyFails() {
        val enc = encrypt(randomBytes(5_000))
        try { decrypt(enc, VaultCrypto.newMasterKey()); fail() } catch (e: AuthenticationException) { }
    }

    @Test fun sourceLongerOrShorterThanDeclaredFails() {
        val data = randomBytes(5_000)
        try { ChunkedCipher.encrypt(key, ByteArrayInputStream(data), 6_000, ByteArrayOutputStream(), 4096); fail() } catch (e: IOException) { }
        try { ChunkedCipher.encrypt(key, ByteArrayInputStream(data), 4_000, ByteArrayOutputStream(), 4096); fail() } catch (e: IOException) { }
    }

    @Test fun randomAccessReaderMatchesPlaintext() {
        val data = randomBytes(25_000, 7)
        val reader = ChunkedReader(ByteArraySource(encrypt(data)), key)
        assertEquals(25_000L, reader.plainLength)
        for ((pos, len) in listOf(0 to 10, 4090 to 20, 4096 to 4096, 24_990 to 100, 12_345 to 9_000)) {
            val buf = ByteArray(len)
            val n = reader.readAt(pos.toLong(), buf, 0, len)
            val expected = minOf(len, 25_000 - pos)
            assertEquals(expected, n)
            assertArrayEquals(data.copyOfRange(pos, pos + expected), buf.copyOf(expected))
        }
        assertEquals(-1, reader.readAt(25_000, ByteArray(4), 0, 4))
    }

    @Test fun randomAccessReaderRejectsTamperedChunk() {
        val enc = encrypt(randomBytes(9_000)); enc[ChunkedCipher.HEADER_LEN + 100] = (enc[ChunkedCipher.HEADER_LEN + 100].toInt() xor 1).toByte()
        val reader = ChunkedReader(ByteArraySource(enc), key)
        try { reader.readAt(0, ByteArray(10), 0, 10); fail() } catch (e: AuthenticationException) { }
    }

    @Test fun randomAccessReaderRejectsWrongSize() {
        val enc = encrypt(randomBytes(9_000))
        try { ChunkedReader(ByteArraySource(enc.copyOf(enc.size - 1)), key); fail() } catch (e: CorruptDataException) { }
    }

    // ---- recovery metadata ----

    @Test fun recoveryRoundTrip() {
        val mk = VaultCrypto.newMasterKey()
        val data = RecoveryFile.create(mk, "correct horse".toCharArray(), 3, TEST_ITERATIONS)
        assertEquals(RecoveryFile.TOTAL_LEN, data.size)
        val o = RecoveryFile.open(data, "correct horse".toCharArray())
        assertArrayEquals(mk, o.masterKey)
        assertEquals(3L, o.generation)
        assertEquals(3L, RecoveryFile.generationOf(data))
    }

    @Test fun recoveryWrongPassword() {
        val data = RecoveryFile.create(VaultCrypto.newMasterKey(), "right-password".toCharArray(), 1, TEST_ITERATIONS)
        try { RecoveryFile.open(data, "wrong-password".toCharArray()); fail() } catch (e: WrongPasswordException) { }
    }

    @Test fun recoveryHeaderTamperingFailsAuthentication() {
        val data = RecoveryFile.create(VaultCrypto.newMasterKey(), "pw-pw-pw-pw".toCharArray(), 1, TEST_ITERATIONS)
        val bad = data.copyOf(); bad[8] = (bad[8].toInt() xor 1).toByte() // generation field is authenticated
        try { RecoveryFile.open(bad, "pw-pw-pw-pw".toCharArray()); fail() } catch (e: WrongPasswordException) { }
        val bad2 = data.copyOf(); bad2[bad2.size - 1] = (bad2[bad2.size - 1].toInt() xor 1).toByte()
        try { RecoveryFile.open(bad2, "pw-pw-pw-pw".toCharArray()); fail() } catch (e: WrongPasswordException) { }
    }

    @Test fun recoveryMetadataValidation() {
        val data = RecoveryFile.create(VaultCrypto.newMasterKey(), "pw-pw-pw-pw".toCharArray(), 1, TEST_ITERATIONS)
        val pw = "pw-pw-pw-pw".toCharArray()
        try { RecoveryFile.open(data.copyOf(50), pw); fail() } catch (e: RecoveryFormatException) { }
        val badMagic = data.copyOf(); badMagic[0] = 'X'.code.toByte()
        try { RecoveryFile.open(badMagic, pw); fail() } catch (e: RecoveryFormatException) { }
        val badVersion = data.copyOf(); badVersion[4] = 9
        try { RecoveryFile.open(badVersion, pw); fail() } catch (e: RecoveryFormatException) { }
        val badKdf = data.copyOf(); badKdf[5] = 7
        try { RecoveryFile.open(badKdf, pw); fail() } catch (e: RecoveryFormatException) { }
        val lowIter = data.copyOf(); java.nio.ByteBuffer.wrap(lowIter).putInt(14, 5)
        try { RecoveryFile.open(lowIter, pw); fail() } catch (e: RecoveryFormatException) { }
    }

    @Test fun recoveryFilesUseFreshSaltAndNonce() {
        val mk = VaultCrypto.newMasterKey()
        val a = RecoveryFile.create(mk, "same-password".toCharArray(), 1, TEST_ITERATIONS)
        val b = RecoveryFile.create(mk, "same-password".toCharArray(), 1, TEST_ITERATIONS)
        assertFalse(a.contentEquals(b))
    }

    @Test fun nullOutputStreamAcceptsData() {
        val enc = encrypt(randomBytes(5_000))
        val r = ChunkedCipher.decrypt(key, ByteArrayInputStream(enc), NullOutputStream)
        assertTrue(r.plainLength == 5_000L)
    }

    @Test fun manualPbkdf2MatchesPlatformImplementation() {
        for (pw in listOf("a", "correct horse battery", "pässwörd ✓")) {
            val salt = VaultCrypto.randomBytes(16)
            val expected = VaultCrypto.pbkdf2(pw.toCharArray(), salt, 10_000)
            assertArrayEquals(pw, expected, VaultCrypto.pbkdf2Manual(pw.toCharArray(), salt, 10_000))
        }
    }
}
