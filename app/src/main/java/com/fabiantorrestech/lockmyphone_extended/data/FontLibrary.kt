package com.fabiantorrestech.lockmyphone_extended.data

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Font files (.ttf / .otf) the user added for the allowed-apps picker. Each one is copied into the
 * app's own storage, so it keeps working if the original file is moved or deleted. Backups don't
 * carry these files.
 */
class FontLibrary(private val context: Context) {

    private val folder = File(context.filesDir, FOLDER_NAME)

    private val _fonts = MutableStateFlow(readFonts())

    /** The added fonts' file names, sorted. */
    val fonts: StateFlow<List<String>> = _fonts.asStateFlow()

    /** Loaded typefaces by file name, so a font file is parsed once. */
    private val cache = mutableMapOf<String, Typeface>()

    /**
     * Copies the font at [uri] into the library. Returns its file name, or null if the file
     * isn't a font Android can read (nothing is kept then).
     */
    suspend fun add(uri: Uri): String? = withContext(Dispatchers.IO) {
        folder.mkdirs()
        val target = uniqueFile(displayName(uri))
        val typeface = try {
            val input = context.contentResolver.openInputStream(uri)
            input?.use { source -> target.outputStream().use { source.copyTo(it) } }
            if (input == null) null else loadTypeface(target)
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't add font from $uri", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Not a readable font: $uri", e)
            null
        }
        if (typeface == null) {
            target.delete()
            return@withContext null
        }
        synchronized(cache) { cache[target.name] = typeface }
        _fonts.value = readFonts()
        target.name
    }

    fun remove(fileName: String) {
        File(folder, fileName).delete()
        synchronized(cache) { cache.remove(fileName) }
        _fonts.value = readFonts()
    }

    /** The added font's typeface, or null if its file is missing or broken (e.g. after a restore). */
    fun typeface(fileName: String): Typeface? {
        synchronized(cache) { cache[fileName]?.let { return it } }
        val file = File(folder, fileName)
        if (!file.isFile) return null
        val typeface = try {
            loadTypeface(file)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return null
        synchronized(cache) { cache[fileName] = typeface }
        return typeface
    }

    /** Throws if the file isn't a font Android can read. */
    private fun loadTypeface(file: File): Typeface {
        val font = Font.Builder(file).build()
        return Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build()).build()
    }

    private fun readFonts(): List<String> =
        folder.listFiles()?.filter { it.isFile }?.map { it.name }?.sorted().orEmpty()

    /** The picked file's name, made safe for a file name. */
    private fun displayName(uri: Uri): String {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        return name?.replace(Regex("[^A-Za-z0-9._ -]"), "_")?.takeIf { it.isNotBlank() } ?: "font.ttf"
    }

    /** "Inter.ttf", or "Inter (2).ttf" if that name is taken. */
    private fun uniqueFile(name: String): File {
        val base = name.substringBeforeLast('.')
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var candidate = File(folder, name)
        var n = 2
        while (candidate.exists()) {
            candidate = File(folder, "$base ($n)$extension")
            n++
        }
        return candidate
    }

    companion object {
        private const val TAG = "FontLibrary"
        private const val FOLDER_NAME = "fonts"

        /** The name shown for an added font: its file name without the extension. */
        fun displayName(fileName: String): String = fileName.substringBeforeLast('.')
    }
}
