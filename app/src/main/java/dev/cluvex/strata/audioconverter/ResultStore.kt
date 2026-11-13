package dev.cluvex.strata.audioconverter

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.util.Locale

/** Saves a finished conversion in the public Music/AudioConverter folder, which Strata and every file manager can see. */
object ResultStore {
    data class Saved(val uri: Uri, val name: String)

    private const val FOLDER = "AudioConverter"
    private const val PREFS = "saved_result"

    fun saveToMusic(context: Context, file: File): Saved {
        val saved = if (Build.VERSION.SDK_INT >= 29) viaMediaStore(context, file) else viaFile(context, file)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("uri", saved.uri.toString()).putString("name", saved.name).apply()
        return saved
    }

    /** The last saved file, so its notification can be posted again after a swipe even if the process was restarted. */
    fun last(context: Context): Saved? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uri = p.getString("uri", null) ?: return null
        val name = p.getString("name", null) ?: return null
        return Saved(Uri.parse(uri), name)
    }

    fun forget(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun mimeFor(name: String): String = when (val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "flac" -> "audio/flac"
        "opus" -> "audio/ogg"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "audio/*"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun viaMediaStore(context: Context, file: File): Saved {
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeFor(file.name))
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the file in Music.")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Could not write the file in Music.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        // A file with the same name gets a number added; show the name it really got.
        val name = resolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: file.name
        return Saved(uri, name)
    }

    /** Android 9 and older: a plain file in the public folder (needs the storage permission). */
    private fun viaFile(context: Context, file: File): Saved {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("No permission to write to the storage.")
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), FOLDER)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the Music/$FOLDER folder.")
        val stem = file.nameWithoutExtension
        val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        var target = File(dir, file.name)
        var n = 1
        while (target.exists()) target = File(dir, "$stem ($n)$ext").also { n++ }
        file.copyTo(target)
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeFor(target.name)), null)
        return Saved(FileProvider.getUriForFile(context, Engine.authority(context), target), target.name)
    }
}
