package com.lita.app.vault

import com.lita.app.crypto.CorruptDataException
import com.lita.app.crypto.VaultCrypto
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class VideoEntry(
    val id: String,
    val objectName: String,
    val thumbObject: String?,
    val name: String,
    val folder: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val durationMs: Long,
    val addedAt: Long,
)

data class IndexData(val generation: Long, val entries: List<VideoEntry>)

/** The index (names, folders, hashes ...) is a single AES-256-GCM blob; nothing is stored in plaintext. */
object IndexCodec {
    private val AAD = "lita-index-v1".toByteArray(Charsets.UTF_8)

    fun encode(index: IndexData, key: ByteArray): ByteArray {
        val arr = JSONArray()
        for (e in index.entries) {
            arr.put(
                JSONObject()
                    .put("id", e.id).put("obj", e.objectName)
                    .put("thumb", e.thumbObject ?: JSONObject.NULL)
                    .put("name", e.name).put("folder", e.folder).put("mime", e.mime)
                    .put("size", e.size).put("sha", e.sha256).put("dur", e.durationMs).put("added", e.addedAt)
            )
        }
        val root = JSONObject().put("v", 1).put("gen", index.generation).put("entries", arr)
        return VaultCrypto.seal(key, root.toString().toByteArray(Charsets.UTF_8), AAD)
    }

    fun decode(blob: ByteArray, key: ByteArray): IndexData {
        val plain = VaultCrypto.open(key, blob, AAD)
        try {
            val root = JSONObject(String(plain, Charsets.UTF_8))
            if (root.getInt("v") != 1) throw CorruptDataException("Unsupported index version")
            val arr = root.getJSONArray("entries")
            val list = ArrayList<VideoEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    VideoEntry(
                        id = o.getString("id"), objectName = o.getString("obj"),
                        thumbObject = if (o.isNull("thumb")) null else o.getString("thumb"),
                        name = o.getString("name"), folder = o.getString("folder"), mime = o.getString("mime"),
                        size = o.getLong("size"), sha256 = o.getString("sha"),
                        durationMs = o.getLong("dur"), addedAt = o.getLong("added"),
                    )
                )
            }
            return IndexData(root.getLong("gen"), list)
        } catch (e: JSONException) {
            throw CorruptDataException("Index content is invalid")
        }
    }
}
