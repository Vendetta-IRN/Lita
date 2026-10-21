package com.lita.app.data

import com.lita.app.vault.VideoEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.URLDecoder
import java.net.URLEncoder

data class HistoryItem(val videoId: String, val playedAt: Long)

/**
 * Bookmarks, playback history and visibility filters. Everything refers to stable vault video IDs.
 * Filter semantics: hidden == true means the switch is ON and the item is hidden (OFF = visible).
 * Hiding never deletes anything; clearing history or removing a bookmark never touches videos.
 */
class UserState(private val store: KeyValueStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val _bookmarks = MutableStateFlow(readSet(K_BOOKMARKS))
    private val _hiddenVideos = MutableStateFlow(readSet(K_HIDDEN_VIDEOS))
    private val _hiddenFolders = MutableStateFlow(readSet(K_HIDDEN_FOLDERS))
    private val _history = MutableStateFlow(readHistory())

    val bookmarks: StateFlow<Set<String>> get() = _bookmarks
    val hiddenVideos: StateFlow<Set<String>> get() = _hiddenVideos
    val hiddenFolders: StateFlow<Set<String>> get() = _hiddenFolders
    val history: StateFlow<List<HistoryItem>> get() = _history

    // ---- bookmarks ----
    fun isBookmarked(id: String) = id in _bookmarks.value
    fun toggleBookmark(id: String): Boolean {
        val next = LinkedHashSet(_bookmarks.value)
        val added = next.add(id)
        if (!added) next.remove(id)
        _bookmarks.value = next
        writeSet(K_BOOKMARKS, next)
        return added
    }
    fun removeBookmark(id: String) {
        if (id !in _bookmarks.value) return
        val next = LinkedHashSet(_bookmarks.value); next.remove(id)
        _bookmarks.value = next; writeSet(K_BOOKMARKS, next)
    }

    // ---- history ----
    fun addHistory(id: String) {
        val cur = _history.value
        val rest = if (cur.firstOrNull()?.videoId == id) cur.drop(1) else cur
        val next = (listOf(HistoryItem(id, clock())) + rest).take(MAX_HISTORY)
        _history.value = next
        writeHistory(next)
    }
    fun clearHistory() { _history.value = emptyList(); writeHistory(emptyList()) }

    // ---- filters ----
    fun setVideoHidden(id: String, hidden: Boolean) {
        val next = LinkedHashSet(_hiddenVideos.value)
        if (hidden) next.add(id) else next.remove(id)
        _hiddenVideos.value = next; writeSet(K_HIDDEN_VIDEOS, next)
    }
    fun setFolderHidden(folder: String, hidden: Boolean) {
        val next = LinkedHashSet(_hiddenFolders.value)
        if (hidden) next.add(folder) else next.remove(folder)
        _hiddenFolders.value = next; writeSet(K_HIDDEN_FOLDERS, next)
    }

    /** A video is visible unless it, or its folder or any parent folder, is hidden. */
    fun isVisible(e: VideoEntry): Boolean {
        if (e.id in _hiddenVideos.value) return false
        val hf = _hiddenFolders.value
        if (hf.isEmpty() || e.folder.isEmpty()) return true
        var path = e.folder
        while (true) {
            if (path in hf) return false
            val i = path.lastIndexOf('/')
            if (i < 0) return true
            path = path.substring(0, i)
        }
    }

    /** Called after videos were removed from the vault. */
    fun onVideosDeleted(ids: Set<String>) {
        if (ids.isEmpty()) return
        _bookmarks.value = _bookmarks.value.filterTo(LinkedHashSet()) { it !in ids }.also { writeSet(K_BOOKMARKS, it) }
        _hiddenVideos.value = _hiddenVideos.value.filterTo(LinkedHashSet()) { it !in ids }.also { writeSet(K_HIDDEN_VIDEOS, it) }
        _history.value = _history.value.filter { it.videoId !in ids }.also { writeHistory(it) }
    }

    // ---- persistence ----
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    private fun readSet(key: String): Set<String> =
        store.get(key)?.split(',')?.filter { it.isNotEmpty() }?.mapTo(LinkedHashSet()) { dec(it) } ?: LinkedHashSet()
    private fun writeSet(key: String, set: Set<String>) = store.put(key, set.joinToString(",") { enc(it) })
    private fun readHistory(): List<HistoryItem> =
        store.get(K_HISTORY)?.split(',')?.mapNotNull {
            val p = it.split(':'); val t = p.getOrNull(1)?.toLongOrNull()
            if (p.size == 2 && t != null) HistoryItem(dec(p[0]), t) else null
        } ?: emptyList()
    private fun writeHistory(list: List<HistoryItem>) = store.put(K_HISTORY, list.joinToString(",") { enc(it.videoId) + ":" + it.playedAt })

    companion object {
        const val MAX_HISTORY = 300
        private const val K_BOOKMARKS = "state.bookmarks"
        private const val K_HIDDEN_VIDEOS = "state.hiddenVideos"
        private const val K_HIDDEN_FOLDERS = "state.hiddenFolders"
        private const val K_HISTORY = "state.history"
    }
}
