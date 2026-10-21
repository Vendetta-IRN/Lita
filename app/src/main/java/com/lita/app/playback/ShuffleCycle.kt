package com.lita.app.playback

import kotlin.random.Random

data class FeedItem(val slot: Long, val videoId: String)

/**
 * Persistent fair shuffle. The feed is a concatenation of "cycles"; each cycle contains every
 * eligible video exactly once. A new cycle is appended before the previous one runs out, and its first
 * video is never the previous cycle's last video (when more than one video is eligible).
 * Failed videos are tracked separately and excluded until [clearFailure] / [clearFailures].
 */
class ShuffleCycle(private val random: Random = Random.Default) {
    private val feed = ArrayList<FeedItem>()
    var position = 0; private set
    private var cycleStart = 0
    private var nextSlot = 1L
    private val failed = LinkedHashSet<String>()
    private var eligible: List<String> = emptyList()

    val items: List<FeedItem> get() = feed
    val failedIds: Set<String> get() = failed
    fun current(): FeedItem? = feed.getOrNull(position)

    /** Adapts to imported, deleted, hidden or failed videos without restarting the cycle. */
    fun sync(eligibleAll: Collection<String>) {
        eligible = eligibleAll.filter { it !in failed }.distinct()
        val set = eligible.toHashSet()
        removeWhere { _, it -> it.videoId !in set }
        if (eligible.isEmpty()) { feed.clear(); position = 0; cycleStart = 0; return }
        if (feed.isEmpty()) { position = 0; appendBlock() } else {
            val inBlock = HashSet<String>()
            for (i in cycleStart until feed.size) inBlock.add(feed[i].videoId)
            for (id in eligible) {
                if (id in inBlock) continue
                val lo = maxOf(position + 1, cycleStart)
                feed.add(lo + random.nextInt(feed.size - lo + 1), FeedItem(nextSlot++, id))
            }
        }
        ensureLookahead()
    }

    /** Pager settled on [index]. */
    fun moveTo(index: Int) {
        if (index !in feed.indices) return
        position = index
        ensureLookahead()
    }

    /** User explicitly chose [videoId]: it plays next and is not repeated later in the current cycle. */
    fun pin(videoId: String) {
        if (feed.isEmpty()) return
        val keepFrom = position + 1
        removeWhere { i, it -> it.videoId == videoId && i >= keepFrom && i >= cycleStart }
        feed.add(position + 1, FeedItem(nextSlot++, videoId))
        position += 1
        ensureLookahead()
    }

    /** Records a playback failure without removing the item being shown; future cycles skip it. */
    fun markFailed(videoId: String) {
        failed.add(videoId)
        eligible = eligible.filter { it != videoId }
        val cur = position
        removeWhere { i, it -> it.videoId == videoId && i != cur }
        ensureLookahead()
    }

    fun clearFailure(videoId: String) { failed.remove(videoId) }
    fun clearFailures() { failed.clear() }

    /**
     * Pull-to-refresh: start a brand-new random cycle. Every eligible video is still in the cycle exactly once,
     * but the most recently watched half ([recent], newest first) is placed at the end so a refresh does not
     * immediately show videos you just saw. The first video also differs from the one on screen.
     */
    fun reshuffle(eligibleAll: Collection<String>, recent: List<String> = emptyList()) {
        val prev = current()?.videoId
        failed.clear()
        eligible = eligibleAll.distinct()
        feed.clear(); position = 0; cycleStart = 0
        if (eligible.isEmpty()) return
        val pool = eligible.toHashSet()
        val recentSet = recent.distinct().filter { it in pool }.take(eligible.size / 2).toSet()
        val shuffled = eligible.shuffled(random)
        val block = (shuffled.filter { it !in recentSet } + shuffled.filter { it in recentSet }).toMutableList()
        if (prev != null && block.size > 1 && block[0] == prev) { val t = block[0]; block[0] = block[1]; block[1] = t }
        for (id in block) feed.add(FeedItem(nextSlot++, id))
        ensureLookahead()
    }

    private fun ensureLookahead() {
        if (eligible.isEmpty()) return
        if (feed.isEmpty()) { position = 0; appendBlock() }
        if (position >= feed.size - 1) appendBlock()
    }

    private fun appendBlock(avoid: String? = feed.lastOrNull()?.videoId) {
        val block = eligible.shuffled(random).toMutableList()
        val prev = avoid
        if (prev != null && block.size > 1 && block[0] == prev) {
            val j = 1 + random.nextInt(block.size - 1)
            val t = block[0]; block[0] = block[j]; block[j] = t
        }
        cycleStart = feed.size
        for (id in block) feed.add(FeedItem(nextSlot++, id))
    }

    private fun removeWhere(pred: (Int, FeedItem) -> Boolean) {
        var newPos = 0; var newStart = 0
        val kept = ArrayList<FeedItem>(feed.size)
        for ((i, it) in feed.withIndex()) {
            if (pred(i, it)) continue
            if (i < position) newPos++
            if (i < cycleStart) newStart++
            kept.add(it)
        }
        feed.clear(); feed.addAll(kept)
        position = newPos.coerceAtMost(maxOf(feed.size - 1, 0))
        cycleStart = newStart.coerceAtMost(feed.size)
    }

    // ---- persistence ----
    fun serialize(): String {
        val from = maxOf(0, position - 20)
        val window = feed.subList(from, feed.size)
        return listOf(
            "v1", (position - from).toString(), (maxOf(cycleStart - from, 0)).toString(), nextSlot.toString(),
            failed.joinToString(","), window.joinToString(",") { "${it.slot}:${it.videoId}" },
        ).joinToString("\n")
    }

    companion object {
        fun restore(data: String?, random: Random = Random.Default): ShuffleCycle {
            val s = ShuffleCycle(random)
            if (data == null) return s
            try {
                val l = data.split("\n")
                if (l.size < 6 || l[0] != "v1") return s
                val items = l[5].split(",").filter { it.isNotEmpty() }.map {
                    val p = it.split(":"); FeedItem(p[0].toLong(), p[1])
                }
                val pos = l[1].toInt(); val cs = l[2].toInt()
                if (items.isNotEmpty() && pos in items.indices && cs in 0..items.size) {
                    s.feed.addAll(items); s.position = pos; s.cycleStart = cs
                }
                s.nextSlot = maxOf(l[3].toLong(), (items.maxOfOrNull { it.slot } ?: 0L) + 1)
                s.failed.addAll(l[4].split(",").filter { it.isNotEmpty() })
            } catch (e: Exception) {
                return ShuffleCycle(random)
            }
            return s
        }
    }
}
