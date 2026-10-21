@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lita.app.playback

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSourceException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.lita.app.crypto.ChunkedReader
import com.lita.app.vault.MediaInfo
import com.lita.app.vault.VideoEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Media3 data source that decrypts vault objects on the fly: no plaintext ever touches disk. */
class VaultDataSource(private val openReader: (String) -> ChunkedReader) : BaseDataSource(false) {
    private var reader: ChunkedReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val id = dataSpec.uri.lastPathSegment ?: throw IOException("Invalid vault URI")
        val r = try { openReader(id) } catch (e: Exception) { throw IOException(e.message ?: "Cannot open vault object", e) }
        reader = r
        if (dataSpec.position > r.plainLength) {
            r.close(); reader = null
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, r.plainLength - position) else r.plainLength - position
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: throw IOException("Data source is closed")
        val n = try {
            r.readAt(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        } catch (e: Exception) {
            throw IOException(e.message ?: "Decryption failed", e)
        }
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n; remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { reader?.close() } finally {
            reader = null; uri = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}

data class PlaybackError(val videoId: String, val message: String)

/** Owns a single ExoPlayer (bounded allocation). Released by [release]. */
class PlayerController(private val context: Context, private val openReader: (String) -> ChunkedReader) {
    private var exo: ExoPlayer? = null
    private val _isPlaying = MutableStateFlow(false)
    private val _error = MutableStateFlow<PlaybackError?>(null)
    private val _muted = MutableStateFlow(false)
    private val _firstFrame = MutableStateFlow<String?>(null)
    private val _userPaused = MutableStateFlow(false)
    private val _appVolume = MutableStateFlow(100)
    var currentId: String? = null; private set

    val isPlaying: StateFlow<Boolean> get() = _isPlaying
    val error: StateFlow<PlaybackError?> get() = _error
    val muted: StateFlow<Boolean> get() = _muted
    val appVolume: StateFlow<Int> get() = _appVolume
    /** True once the current video has rendered a frame; until then the UI keeps showing the thumbnail (no black flash). */
    val firstFrameId: StateFlow<String?> get() = _firstFrame
    /** True only when the user paused by tapping (not for lifecycle pauses or buffering). */
    val userPaused: StateFlow<Boolean> get() = _userPaused

    val player: ExoPlayer
        get() = exo ?: build().also { exo = it }

    private fun build(): ExoPlayer {
        val factory = DataSource.Factory { VaultDataSource(openReader) }
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(2_000, 8_000, 1_000, 1_500).build()
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setLoadControl(loadControl)
            .build()
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.volume = effectiveVolume()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying }
            override fun onRenderedFirstFrame() { _firstFrame.value = currentId }
            override fun onPlayerError(error: PlaybackException) {
                val id = currentId ?: return
                _error.value = PlaybackError(id, error.cause?.message ?: error.message ?: "Playback failed")
            }
        })
        return p
    }

    fun play(entry: VideoEntry) {
        currentId = entry.id
        _error.value = null
        _firstFrame.value = null
        _userPaused.value = false
        val p = player
        p.setMediaItem(MediaItem.Builder().setUri("lita://vault/${entry.id}").setMimeType(entry.mime).build())
        p.prepare()
        p.playWhenReady = true
    }

    fun togglePlay() {
        exo?.let { if (it.playWhenReady) { it.pause(); _userPaused.value = true } else { it.play(); _userPaused.value = false } }
    }
    fun pause() { exo?.pause() }
    private var resumeOnForeground = false
    /** App went to the background: pause, remembering whether playback should continue on return. */
    fun pauseForBackground() { resumeOnForeground = exo?.isPlaying == true; exo?.pause() }
    fun resumeFromBackground() { if (resumeOnForeground) { resumeOnForeground = false; exo?.play() } }
    /** Squared curve: 1% steps sound even instead of jumping at the low end. */
    private fun effectiveVolume(): Float = if (_muted.value) 0f else 1f   // loudness comes from the phone's media volume

    fun setMuted(m: Boolean) { _muted.value = m; exo?.volume = effectiveVolume() }
    fun setAppVolume(level: Int) { _appVolume.value = level.coerceIn(0, 100); exo?.volume = effectiveVolume() }
    fun stop() { exo?.let { it.stop(); it.clearMediaItems() }; currentId = null; _isPlaying.value = false; _firstFrame.value = null; _userPaused.value = false }

    fun release() {
        exo?.release(); exo = null
        currentId = null; _isPlaying.value = false
    }
}

/** Builds a muted, looping preview player (Showcase). The caller must release it. */
fun createPreviewPlayer(context: Context, openReader: (String) -> ChunkedReader): ExoPlayer {
    val factory = DataSource.Factory { VaultDataSource(openReader) }
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 4_000, 500, 1_000).build())
        .build().apply { volume = 0f; repeatMode = Player.REPEAT_MODE_ONE }
}

fun previewMediaItem(entry: VideoEntry): MediaItem =
    MediaItem.Builder().setUri("lita://vault/${entry.id}").setMimeType(entry.mime).build()

/** Lets MediaMetadataRetriever read a vault object through the decrypting reader. */
private class ReaderMediaDataSource(private val reader: ChunkedReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = reader.readAt(position, buffer, offset, size)
    override fun getSize(): Long = reader.plainLength
    override fun close() {}
}

/** Extracts duration and a small JPEG thumbnail from an encrypted vault object. */
fun probeMedia(reader: ChunkedReader): MediaInfo? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ReaderMediaDataSource(reader))
        val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val frame = r.getFrameAtTime(minOf(1_000_000L, dur * 1000 / 4), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        var jpeg: ByteArray? = null
        if (frame != null) {
            val maxW = 480
            val scaled = if (frame.width > maxW) {
                Bitmap.createScaledBitmap(frame, maxW, (frame.height * maxW.toFloat() / frame.width).toInt().coerceAtLeast(1), true)
            } else frame
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            jpeg = bos.toByteArray()
        }
        MediaInfo(dur, jpeg)
    } catch (e: Exception) {
        null
    } finally {
        runCatching { r.release() }
    }
}
