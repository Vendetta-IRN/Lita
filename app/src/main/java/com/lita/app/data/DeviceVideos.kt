package com.lita.app.data

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

class DeviceVideo(
    val id: Long, val uri: Uri, val name: String, val mime: String, val size: Long,
    val durationMs: Long, val bucketId: Long, val bucketName: String,
)

/** Lists the videos on the device (MediaStore) for the in-app import browser. Never used for Explore/Showcase. */
object DeviceVideos {
    fun permission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission()) == PackageManager.PERMISSION_GRANTED

    fun query(resolver: ContentResolver): List<DeviceVideo> {
        val out = ArrayList<DeviceVideo>()
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val proj = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_ID, MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        resolver.query(collection, proj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                out.add(
                    DeviceVideo(
                        id = id, uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(1) ?: "video", mime = c.getString(2) ?: "video/mp4",
                        size = c.getLong(3), durationMs = c.getLong(4),
                        bucketId = c.getLong(5), bucketName = c.getString(6) ?: "Other",
                    )
                )
            }
        }
        return out
    }
}
