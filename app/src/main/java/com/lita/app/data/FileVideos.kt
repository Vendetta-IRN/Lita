package com.lita.app.data

import android.webkit.MimeTypeMap
import java.io.File

object FileVideos {
    fun isVideoName(name: String) = AndroidIo.isVideo(null, name)

    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "video/mp4"

    /**
     * Expands a selection of files and folders into video files with the vault folder each belongs in.
     * Folders are scanned recursively (hidden folders skipped) and keep their relative structure.
     */
    fun collect(selection: List<File>): List<Pair<File, String>> {
        val out = LinkedHashMap<String, Pair<File, String>>()
        for (item in selection) {
            if (item.isFile) {
                if (isVideoName(item.name)) out[item.absolutePath] = item to (item.parentFile?.name ?: "")
            } else if (item.isDirectory) {
                val base = item.parentFile ?: item
                item.walkTopDown().onEnter { !it.name.startsWith(".") || it == item }.forEach { f ->
                    if (f.isFile && isVideoName(f.name)) {
                        val rel = (f.parentFile ?: item).relativeTo(base).path.replace(File.separatorChar, '/')
                        out[f.absolutePath] = f to rel
                    }
                }
            }
        }
        return out.values.toList()
    }
}
