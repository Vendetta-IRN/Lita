package com.lita.app

import com.lita.app.crypto.WrongPasswordException
import com.lita.app.vault.EntryStatus
import com.lita.app.vault.ImportResult
import com.lita.app.vault.VaultException
import com.lita.app.vault.VaultRepository
import com.lita.app.vault.readAll
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class VaultRepositoryTest {
    private val pw = "recovery-password".toCharArray()
    private fun newVault(storage: InMemoryVaultStorage = InMemoryVaultStorage()) = storage to VaultRepository.create(storage, pw.copyOf(), TEST_ITERATIONS)
    private fun ok(r: ImportResult) = (r as ImportResult.Success).entry
    private val hex = Regex("^[0-9a-f]{32}$")

    @Test fun createWritesRecoveryAndIndexOnly() {
        val (s, _) = newVault()
        assertTrue(s.files.containsKey("lita-recovery-a"))
        assertTrue(s.files.keys.any { it.startsWith("lita-index") })
    }

    @Test fun cannotCreateOverExistingVault() {
        val (s, _) = newVault()
        try { VaultRepository.create(s, pw.copyOf(), TEST_ITERATIONS); fail() } catch (e: VaultException) { }
    }

    @Test fun importEncryptsAndRandomizesNames() {
        val (s, repo) = newVault()
        val video = randomBytes(600_000, 3)
        val e = ok(repo.importVideo(BytesSource("Holiday 2024.mp4", video), "Trips/Italy"))
        assertEquals("Holiday 2024.mp4", e.name); assertEquals("Trips/Italy", e.folder)
        // object name is random hex without extension; no filename/folder in any stored name or content
        assertTrue(hex.matches(e.objectName))
        val joined = s.files.keys.joinToString()
        assertFalse(joined.contains("Holiday")); assertFalse(joined.contains("mp4")); assertFalse(joined.contains("Italy"))
        for ((_, data) in s.files) {
            // long markers only: a 3-byte string like "mp4" can occur by chance in 600 KB of random ciphertext
            val text = String(data, Charsets.ISO_8859_1)
            assertFalse(text.contains("Holiday 2024")); assertFalse(text.contains("Trips/Italy")); assertFalse(text.contains("Holiday 2024.mp4"))
        }
        // plaintext video content is not present in the stored object
        val stored = s.files[e.objectName]!!
        assertFalse(String(stored, Charsets.ISO_8859_1).contains(String(video, Charsets.ISO_8859_1).substring(1000, 1064)))
    }

    @Test fun importFailureKeepsSourceAndLeavesNoTraces() {
        val (s, repo) = newVault()
        val original = randomBytes(300_000, 5); val copy = original.copyOf()
        val before = s.files.keys.toSet()
        val r = repo.importVideo(BytesSource("a.mp4", original, failAt = 100_000))
        assertTrue(r is ImportResult.Failure)
        assertArrayEquals(copy, original)               // source untouched
        assertEquals(before, s.files.keys.toSet())      // partial object removed, index unchanged
        assertTrue(repo.entries.isEmpty())
    }

    @Test fun storageWriteFailureAlsoFailsSafely() {
        val (s, repo) = newVault()
        s.failWriteAfterBytes = 50_000
        val data = randomBytes(300_000, 6)
        assertTrue(repo.importVideo(BytesSource("a.mp4", data)) is ImportResult.Failure)
        assertTrue(s.files.keys.none { hex.matches(it) }); assertTrue(repo.entries.isEmpty())
    }

    @Test fun verificationCatchesSilentCorruptionAndDiscardsObject() {
        val (s, repo) = newVault()
        s.corruptNextWrite = true
        val r = repo.importVideo(BytesSource("a.mp4", randomBytes(100_000, 8)))
        assertTrue(r is ImportResult.Failure)
        assertTrue(s.files.keys.none { hex.matches(it) }); assertTrue(repo.entries.isEmpty())
    }

    @Test fun sizeUnknownIsRejected() {
        val (_, repo) = newVault()
        val src = object : com.lita.app.vault.ImportSource {
            override val name = "x.mp4"; override val mime = "video/mp4"; override val size = -1L
            override fun open() = java.io.ByteArrayInputStream(ByteArray(0))
        }
        assertTrue(repo.importVideo(src) is ImportResult.Failure)
    }

    @Test fun exportRestoresBytesAndKeepsEncryptedOriginal() {
        val (s, repo) = newVault()
        val video = randomBytes(700_000, 9)
        val e = ok(repo.importVideo(BytesSource("clip.mkv", video, "video/x-matroska")))
        val before = s.files[e.objectName]!!.copyOf()
        val out = ByteArrayOutputStream()
        repo.export(e.id, out)
        assertArrayEquals(video, out.toByteArray())
        assertArrayEquals(before, s.files[e.objectName]!!)   // encrypted original unchanged
        assertNotNull(repo.entry(e.id))                       // still in vault
    }

    @Test fun exportOfTamperedObjectFails() {
        val (s, repo) = newVault()
        val e = ok(repo.importVideo(BytesSource("clip.mp4", randomBytes(300_000, 10))))
        val obj = s.files[e.objectName]!!; obj[1000] = (obj[1000].toInt() xor 1).toByte()
        try { repo.export(e.id, ByteArrayOutputStream()); fail() } catch (ex: java.security.GeneralSecurityException) { }
        assertFalse(repo.verify(e.id))
    }

    @Test fun restoreWithPasswordOnFreshInstance() {
        val (s, repo) = newVault()
        val video = randomBytes(100_000, 11)
        val e = ok(repo.importVideo(BytesSource("x.mp4", video), "F/G"))
        val restored = VaultRepository.restore(s, pw.copyOf())
        assertEquals(1, restored.entries.size)
        assertEquals("x.mp4", restored.entries[0].name); assertEquals("F/G", restored.entries[0].folder)
        val out = ByteArrayOutputStream(); restored.export(e.id, out)
        assertArrayEquals(video, out.toByteArray())
    }

    @Test fun restoreWithWrongPasswordFails() {
        val (s, _) = newVault()
        try { VaultRepository.restore(s, "not-the-password".toCharArray()); fail() } catch (e: WrongPasswordException) { }
    }

    @Test fun openWithWrongKeyReportsAuthenticationFailure() {
        val (s, _) = newVault()
        try { VaultRepository.open(s, ByteArray(32)); fail() } catch (e: VaultException) { assertTrue(e.message!!.contains("authentication")) }
    }

    // ---- index consistency ----

    @Test fun indexAlternatesSlotsAndKeepsLastGoodOnCrash() {
        val (s, repo) = newVault()
        ok(repo.importVideo(BytesSource("a.mp4", randomBytes(20_000, 1))))
        ok(repo.importVideo(BytesSource("b.mp4", randomBytes(20_000, 2))))
        val gen = repo.generation
        // Simulate a crash that left the newest slot half written: the other slot must still open.
        val newest = listOf("lita-index-a", "lita-index-b").maxByOrNull { name ->
            runCatching { com.lita.app.vault.IndexCodec.decode(s.files[name]!!, repo.masterKeyCopy()).generation }.getOrDefault(-1)
        }!!
        s.files[newest] = s.files[newest]!!.copyOf(30)
        val reopened = VaultRepository.open(s, repo.masterKeyCopy())
        assertEquals(gen - 1, reopened.generation)
        assertEquals(1, reopened.entries.size)
    }

    @Test fun reloadPicksUpExternalChanges() {
        val (s, repo) = newVault()
        val other = VaultRepository.open(s, repo.masterKeyCopy())
        ok(other.importVideo(BytesSource("a.mp4", randomBytes(10_000, 1))))
        assertEquals(0, repo.entries.size)
        repo.reload()
        assertEquals(1, repo.entries.size)
    }

    @Test fun scanDistinguishesMissingDamagedAndOrphans() {
        val (s, repo) = newVault()
        val a = ok(repo.importVideo(BytesSource("a.mp4", randomBytes(50_000, 1))))
        val b = ok(repo.importVideo(BytesSource("b.mp4", randomBytes(50_000, 2))))
        val c = ok(repo.importVideo(BytesSource("c.mp4", randomBytes(50_000, 3))))
        s.files.remove(a.objectName)
        s.files[b.objectName] = s.files[b.objectName]!!.copyOf(1000)
        s.files["0123456789abcdef0123456789abcdef"] = ByteArray(10)
        val rep = repo.scan()
        assertEquals(EntryStatus.MISSING, rep.statuses[a.id])
        assertEquals(EntryStatus.SIZE_MISMATCH, rep.statuses[b.id])
        assertEquals(EntryStatus.OK, rep.statuses[c.id])
        assertEquals(listOf("0123456789abcdef0123456789abcdef"), rep.orphans)
        assertEquals(3, repo.entries.size)                    // nothing silently dropped
    }

    @Test fun deleteUpdatesIndexAndRemovesObjects() {
        val (s, repo) = newVault()
        val a = ok(repo.importVideo(BytesSource("a.mp4", randomBytes(10_000, 1))))
        repo.delete(setOf(a.id))
        assertTrue(repo.entries.isEmpty()); assertFalse(s.files.containsKey(a.objectName))
        assertTrue(VaultRepository.open(s, repo.masterKeyCopy()).entries.isEmpty())
    }

    @Test fun orphanRecoveryAdoptsCompleteAndRemovesBrokenObjects() {
        val (s, repo) = newVault()
        val a = ok(repo.importVideo(BytesSource("a.mp4", randomBytes(30_000, 1))))
        val obj = s.files[a.objectName]!!
        val goodOrphan = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val brokenOrphan = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        s.files[goodOrphan] = obj.copyOf()               // complete object whose index entry was lost
        s.files[brokenOrphan] = obj.copyOf(500)          // interrupted write
        assertEquals(listOf(goodOrphan, brokenOrphan), repo.scan().orphans)
        val rep = repo.recoverOrphans()
        assertEquals(1, rep.adopted); assertEquals(1, rep.removed); assertEquals(0, rep.kept)
        assertEquals(2, repo.entries.size)
        assertFalse(s.files.containsKey(brokenOrphan)); assertTrue(s.files.containsKey(goodOrphan))
        assertTrue(repo.scan().orphans.isEmpty())
        assertTrue(repo.verify(repo.entries.first { it.objectName == goodOrphan }.id))
    }

    // ---- duplicates ----

    @Test fun duplicatesAreFoundByContentNotName() {
        val (_, repo) = newVault()
        val same = randomBytes(40_000, 42)
        val d1 = ok(repo.importVideo(BytesSource("one.mp4", same)))
        val d2 = ok(repo.importVideo(BytesSource("two.mp4", same)))
        ok(repo.importVideo(BytesSource("one.mp4", randomBytes(40_000, 43))))     // same name, different content
        val (groups, corrupt) = repo.findDuplicates()
        assertEquals(0, corrupt)
        assertEquals(1, groups.size)
        assertEquals(setOf(d1.id, d2.id), groups[0].entries.map { it.id }.toSet())
    }

    @Test fun noDuplicatesYieldsEmptyResult() {
        val (_, repo) = newVault()
        ok(repo.importVideo(BytesSource("a.mp4", randomBytes(10_000, 1))))
        ok(repo.importVideo(BytesSource("b.mp4", randomBytes(10_000, 2))))
        assertTrue(repo.findDuplicates().first.isEmpty())
    }

    @Test fun duplicateRemovalAlwaysKeepsOneVerifiedCopy() {
        val (_, repo) = newVault()
        val same = randomBytes(30_000, 5)
        val a = ok(repo.importVideo(BytesSource("a.mp4", same)))
        val b = ok(repo.importVideo(BytesSource("b.mp4", same)))
        val c = ok(repo.importVideo(BytesSource("c.mp4", same)))
        try { repo.removeDuplicates(a.id, setOf(a.id, b.id)); fail() } catch (e: VaultException) { }  // cannot delete the keeper
        assertEquals(3, repo.entries.size)
        repo.removeDuplicates(a.id, setOf(b.id, c.id))
        assertEquals(listOf(a.id), repo.entries.map { it.id })
    }

    @Test fun duplicateRemovalRefusesWhenKeeperIsDamaged() {
        val (s, repo) = newVault()
        val same = randomBytes(30_000, 5)
        val a = ok(repo.importVideo(BytesSource("a.mp4", same)))
        val b = ok(repo.importVideo(BytesSource("b.mp4", same)))
        val obj = s.files[a.objectName]!!; obj[200] = (obj[200].toInt() xor 1).toByte()
        try { repo.removeDuplicates(a.id, setOf(b.id)); fail() } catch (e: VaultException) { }
        assertEquals(2, repo.entries.size)
        val (groups, corrupt) = repo.findDuplicates()
        assertTrue(groups.isEmpty()); assertEquals(1, corrupt)       // damaged file is not treated as a duplicate
    }

    @Test fun removeDuplicatesRejectsNonDuplicates() {
        val (_, repo) = newVault()
        val a = ok(repo.importVideo(BytesSource("a.mp4", randomBytes(10_000, 1))))
        val b = ok(repo.importVideo(BytesSource("b.mp4", randomBytes(10_000, 2))))
        try { repo.removeDuplicates(a.id, setOf(b.id)); fail() } catch (e: VaultException) { }
        assertEquals(2, repo.entries.size)
    }

    // ---- recovery password change ----

    @Test fun changingRecoveryPasswordInvalidatesTheOldOne() {
        val (s, repo) = newVault()
        repo.changeRecoveryPassword(pw.copyOf(), "brand-new-password".toCharArray(), TEST_ITERATIONS)
        assertEquals(1, s.files.keys.count { it.startsWith("lita-recovery") })
        try { VaultRepository.restore(s, pw.copyOf()); fail() } catch (e: WrongPasswordException) { }
        assertNotNull(VaultRepository.restore(s, "brand-new-password".toCharArray()))
        try { repo.changeRecoveryPassword("bad-current".toCharArray(), "x-new-password".toCharArray(), TEST_ITERATIONS); fail() } catch (e: WrongPasswordException) { }
    }

    @Test fun thumbnailsAreEncryptedAndRoundTrip() {
        val (s, repo) = newVault()
        val jpeg = ByteArray(2000) { 7 }
        val e = ok(repo.importVideo(BytesSource("a.mp4", randomBytes(10_000, 1)), probe = { com.lita.app.vault.MediaInfo(1234, jpeg) }))
        assertEquals(1234L, e.durationMs)
        assertArrayEquals(jpeg, repo.thumbnail(e.id))
        assertFalse(String(s.files[e.thumbObject!!]!!, Charsets.ISO_8859_1).contains("\u0007\u0007\u0007\u0007\u0007\u0007\u0007\u0007"))
        assertNull(repo.thumbnail("missing"))
        assertTrue(s.files[e.thumbObject!!] != null && s.readAll(e.thumbObject!!).isNotEmpty())
    }
}
