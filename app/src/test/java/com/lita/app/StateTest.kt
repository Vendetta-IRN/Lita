package com.lita.app

import com.lita.app.data.MemoryStore
import com.lita.app.data.UserState
import com.lita.app.playback.ShuffleCycle
import com.lita.app.security.AttemptLimiter
import com.lita.app.security.PinHasher
import com.lita.app.vault.VideoEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ShuffleCycleTest {
    private val ids = (1..10).map { "v$it" }

    private fun advance(c: ShuffleCycle, n: Int) { repeat(n) { c.moveTo(c.position + 1) } }

    @Test fun everyEligibleVideoPlaysOncePerCycle() {
        val c = ShuffleCycle(Random(1)); c.sync(ids)
        advance(c, 99)
        val items = c.items.map { it.videoId }
        assertTrue(items.size >= 100)
        for (k in 0 until items.size / 10) assertEquals("cycle $k", ids.toSet(), items.subList(k * 10, k * 10 + 10).toSet())
    }

    @Test fun nextCycleDoesNotStartWithPreviousLast() {
        for (seed in 1..200) {
            val c = ShuffleCycle(Random(seed)); c.sync(ids); advance(c, 35)
            val items = c.items.map { it.videoId }
            for (k in 1 until items.size / 10) assertNotEquals("seed $seed", items[k * 10 - 1], items[k * 10])
        }
    }

    @Test fun singleVideoRepeatsAndTwoVideosAlternateAcrossBoundary() {
        val one = ShuffleCycle(Random(1)); one.sync(listOf("a")); advance(one, 5)
        assertTrue(one.items.all { it.videoId == "a" })
        for (seed in 1..50) {
            val two = ShuffleCycle(Random(seed)); two.sync(listOf("a", "b")); advance(two, 9)
            val it = two.items.map { it.videoId }
            for (i in 1 until it.size) assertNotEquals(it[i - 1], it[i])
        }
    }

    @Test fun emptyEligibleSetClearsFeed() {
        val c = ShuffleCycle(Random(1)); c.sync(ids); c.sync(emptyList())
        assertTrue(c.items.isEmpty()); assertEquals(null, c.current())
    }

    @Test fun persistenceRestoresQueueAndPosition() {
        val c = ShuffleCycle(Random(3)); c.sync(ids); advance(c, 4)
        val cur = c.current()
        val r = ShuffleCycle.restore(c.serialize(), Random(9))
        assertEquals(cur!!.videoId, r.current()!!.videoId)
        assertEquals(c.items.drop(maxOf(0, c.position - 20)).map { it.videoId }, r.items.map { it.videoId })
        r.sync(ids)
        assertEquals(cur.videoId, r.current()!!.videoId)
    }

    @Test fun corruptStateFallsBackToEmpty() {
        assertTrue(ShuffleCycle.restore("garbage").items.isEmpty())
        assertTrue(ShuffleCycle.restore(null).items.isEmpty())
        assertTrue(ShuffleCycle.restore("v1\nx\n0\n1\n\n1:a").items.isEmpty())
    }

    @Test fun importedVideosJoinCurrentCycleAheadOfPosition() {
        val c = ShuffleCycle(Random(5)); c.sync(ids); advance(c, 2)
        val pos = c.position
        c.sync(ids + "new")
        assertEquals(pos, c.position)
        val idx = c.items.indexOfFirst { it.videoId == "new" }
        assertTrue(idx > pos)
        assertEquals(1, c.items.count { it.videoId == "new" })
    }

    @Test fun deletedVideosLeaveFeedAndPositionStaysConsistent() {
        val c = ShuffleCycle(Random(5)); c.sync(ids); advance(c, 4)
        val before = c.current()!!.videoId
        val removed = c.items.take(2).map { it.videoId }.filter { it != before }.toSet()
        c.sync(ids.filter { it !in removed })
        assertEquals(before, c.current()!!.videoId)
        assertTrue(c.items.none { it.videoId in removed })
    }

    @Test fun failedVideoIsIsolatedAndNeverRetriedAutomatically() {
        val c = ShuffleCycle(Random(7)); c.sync(ids)
        val bad = c.current()!!.videoId
        c.markFailed(bad)
        assertEquals(bad, c.current()!!.videoId)               // current page is kept so the UI can show the error
        assertTrue(c.failedIds.contains(bad))
        c.sync(ids)                                            // failed ids are excluded from eligibility
        advance(c, 60)
        assertTrue(c.items.drop(1).none { it.videoId == bad }) // never re-queued, no retry loop
    }

    @Test fun explicitRetryAndClearFailuresRequeue() {
        val c = ShuffleCycle(Random(7)); c.sync(ids)
        val bad = c.current()!!.videoId; c.markFailed(bad)
        c.clearFailure(bad); c.sync(ids)
        assertTrue(c.items.count { it.videoId == bad } >= 1)
        assertFalse(c.failedIds.contains(bad))
    }

    @Test fun pinPlaysSelectedNextWithoutDuplicatingInCycle() {
        val c = ShuffleCycle(Random(2)); c.sync(ids)
        c.pin("v7")
        assertEquals("v7", c.current()!!.videoId)
        assertEquals(1, c.items.drop(c.position).count { it.videoId == "v7" })
    }

    @Test fun shuffleIsUnbiasedEnough() {
        val first = HashMap<String, Int>()
        for (seed in 1..2000) { val c = ShuffleCycle(Random(seed)); c.sync(ids); first.merge(c.items[0].videoId, 1, Int::plus) }
        assertTrue(first.values.all { it in 120..290 })          // expected 200 each
    }
}

class UserStateTest {
    private fun entry(id: String, folder: String = "") = VideoEntry(id, "o$id", null, "$id.mp4", folder, "video/mp4", 1, "h$id", 0, 0)

    @Test fun hideSwitchSemanticsOnIsHiddenOffIsVisible() {
        val u = UserState(MemoryStore())
        val e = entry("a", "Trips/Italy")
        assertTrue(u.isVisible(e))                      // default: switch OFF = visible
        u.setVideoHidden("a", true)                     // switch ON = hidden
        assertFalse(u.isVisible(e))
        u.setVideoHidden("a", false)
        assertTrue(u.isVisible(e))
        u.setFolderHidden("Trips", true)                // hiding a parent folder hides descendants
        assertFalse(u.isVisible(e)); assertTrue(u.isVisible(entry("b", "Other")))
        u.setFolderHidden("Trips", false)
        u.setFolderHidden("Trips/Italy", true)
        assertFalse(u.isVisible(e)); assertTrue(u.isVisible(entry("c", "Trips")))
    }

    @Test fun filtersPersistAcrossInstances() {
        val store = MemoryStore()
        UserState(store).apply { setVideoHidden("a", true); setFolderHidden("F", true); toggleBookmark("b"); addHistory("c") }
        val u = UserState(store)
        assertEquals(setOf("a"), u.hiddenVideos.value); assertEquals(setOf("F"), u.hiddenFolders.value)
        assertTrue(u.isBookmarked("b")); assertEquals("c", u.history.value.first().videoId)
    }

    @Test fun clearingHistoryAndRemovingBookmarksOnlyTouchesThoseRecords() {
        val u = UserState(MemoryStore()); u.toggleBookmark("a"); u.addHistory("a"); u.setVideoHidden("a", true)
        u.clearHistory(); u.removeBookmark("a")
        assertTrue(u.history.value.isEmpty()); assertFalse(u.isBookmarked("a")); assertEquals(setOf("a"), u.hiddenVideos.value)
    }

    @Test fun deletingVideosCleansRelatedRecords() {
        val u = UserState(MemoryStore())
        u.toggleBookmark("a"); u.toggleBookmark("b"); u.addHistory("a"); u.addHistory("b"); u.setVideoHidden("a", true)
        u.onVideosDeleted(setOf("a"))
        assertEquals(setOf("b"), u.bookmarks.value); assertEquals(listOf("b"), u.history.value.map { it.videoId })
        assertTrue(u.hiddenVideos.value.isEmpty())
    }

    @Test fun historyDedupesConsecutiveAndIsCapped() {
        var t = 0L
        val u = UserState(MemoryStore()) { ++t }
        u.addHistory("a"); u.addHistory("a"); u.addHistory("b")
        assertEquals(listOf("b", "a"), u.history.value.map { it.videoId })
        repeat(400) { u.addHistory("x$it") }
        assertEquals(UserState.MAX_HISTORY, u.history.value.size)
    }
}

class PinTest {
    @Test fun pinHashIsSaltedAndVerifies() {
        val a = PinHasher.create("1234".toCharArray()); val b = PinHasher.create("1234".toCharArray())
        assertFalse(a.hash.contentEquals(b.hash))
        assertTrue(PinHasher.verify("1234".toCharArray(), a)); assertFalse(PinHasher.verify("1235".toCharArray(), a))
    }

    @Test fun pinFormat() {
        assertTrue(PinHasher.isValidFormat("1234".toCharArray())); assertTrue(PinHasher.isValidFormat("12345678".toCharArray()))
        assertFalse(PinHasher.isValidFormat("123".toCharArray())); assertFalse(PinHasher.isValidFormat("123456789".toCharArray()))
        assertFalse(PinHasher.isValidFormat("12a4".toCharArray()))
    }

    @Test fun limiterLocksOutWithGrowingDelayAndPersists() {
        var now = 1_000_000L
        val store = MemoryStore()
        val l = AttemptLimiter(store) { now }
        repeat(4) { l.recordFailure() }
        assertEquals(0L, l.remainingLockMs())
        l.recordFailure()
        assertEquals(30_000L, l.remainingLockMs())
        assertEquals(30_000L, AttemptLimiter(store) { now }.remainingLockMs())   // survives restart
        now += 30_001; assertEquals(0L, l.remainingLockMs())
        l.recordFailure(); assertEquals(60_000L, l.remainingLockMs())
        l.recordSuccess(); assertEquals(0L, l.remainingLockMs()); assertEquals(0, l.failureCount)
    }
}

class ReshuffleTest {
    private val ids = (1..10).map { "v$it" }

    @Test fun reshuffleBuildsFreshCycleWithDifferentFirstVideo() {
        for (seed in 1..100) {
            val c = ShuffleCycle(Random(seed)); c.sync(ids); c.moveTo(3)
            val shown = c.current()!!.videoId
            c.reshuffle(ids)
            assertEquals(0, c.position)
            assertNotEquals("seed $seed", shown, c.current()!!.videoId)
            assertEquals(ids.toSet(), c.items.take(10).map { it.videoId }.toSet())
        }
    }

    @Test fun reshuffleWithNothingEligibleClearsFeed() {
        val c = ShuffleCycle(Random(1)); c.sync(ids); c.reshuffle(emptyList())
        assertTrue(c.items.isEmpty())
    }

    @Test fun reshuffleKeepsRecentlyWatchedAtTheEnd() {
        for (seed in 1..50) {
            val c = ShuffleCycle(Random(seed)); c.sync(ids)
            val recent = listOf("v1", "v2", "v3")
            c.reshuffle(ids, recent)
            val first = c.items.take(10).map { it.videoId }
            assertEquals(ids.toSet(), first.toSet())                 // still exactly once per cycle
            assertEquals(recent.toSet(), first.takeLast(3).toSet())  // recently watched go last
        }
    }
}
