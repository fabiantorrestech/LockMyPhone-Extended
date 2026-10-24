package com.fabiantorrestech.lockmyphone_extended.core.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.io.File

data class LaunchableApp(val packageName: String, val label: String)

/** Supplies app icons to the UI. It's a single object, so Compose sees a stable parameter. */
interface AppIconProvider {
    /** Returns an icon already in memory, or null. Cheap enough to call during composition. */
    fun cachedIcon(packageName: String, sizePx: Int): ImageBitmap?

    /** Loads (and caches) an icon. Slow, so call it off the main thread. */
    fun loadIcon(packageName: String, sizePx: Int): ImageBitmap?
}

/** Answers questions about the installed apps: what can be launched, labels, icons, the dialer. */
class AppCatalog(private val context: Context) : AppIconProvider {

    private val packageManager: PackageManager = context.packageManager

    /** App icons already loaded, so scrolling a list doesn't reload them. Thread-safe. */
    private val iconCache = LruCache<String, ImageBitmap>(ICON_CACHE_SIZE)

    /** Credential-protected storage, so it's created lazily (only used from the UI). */
    private val listCache by lazy { AppListCache(File(context.filesDir, APP_LIST_CACHE_FILE)) }

    /** The in-memory copy of the app list; null until first loaded. */
    private var listSnapshot: AppListCache.Snapshot? = null

    /**
     * Apps that appear in the launcher, sorted by label, excluding this app. The list is cached
     * in memory and on disk, and rebuilt only when an app was installed, updated or removed
     * since it was taken. Slow on a cache miss, so call it off the main thread.
     */
    @Synchronized
    fun launchableApps(): List<LaunchableApp> {
        val bootCount = currentBootCount()
        val snapshot = listSnapshot ?: listCache.read()
        if (snapshot != null && isStillValid(snapshot, bootCount)) {
            listSnapshot = snapshot
            return snapshot.apps
        }

        // Read the sequence number before querying, so a change during the query isn't missed.
        val sequenceNumber = packageManager.getChangedPackages(0)?.sequenceNumber ?: 0
        val fresh = AppListCache.Snapshot(bootCount, sequenceNumber, queryLaunchableApps())
        listSnapshot = fresh
        listCache.write(fresh)
        return fresh.apps
    }

    private fun isStillValid(snapshot: AppListCache.Snapshot, bootCount: Int): Boolean =
        snapshot.bootCount == bootCount &&
            packageManager.getChangedPackages(snapshot.sequenceNumber) == null

    private fun currentBootCount(): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun queryLaunchableApps(): List<LaunchableApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { LaunchableApp(it, label(it)) }
            .sortedBy { it.label.lowercase() }
    }

    override fun cachedIcon(packageName: String, sizePx: Int): ImageBitmap? =
        iconCache.get(iconKey(packageName, sizePx))

    override fun loadIcon(packageName: String, sizePx: Int): ImageBitmap? {
        cachedIcon(packageName, sizePx)?.let { return it }
        return try {
            packageManager.getApplicationIcon(packageName)
                .toBitmap(sizePx, sizePx)
                .asImageBitmap()
                .also { iconCache.put(iconKey(packageName, sizePx), it) }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun iconKey(packageName: String, sizePx: Int) = "$packageName@$sizePx"

    fun label(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    fun isInstalled(packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun launchIntent(packageName: String): Intent? =
        packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun defaultDialerPackage(): String? =
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage

    /** The app that takes a photo for other apps ("take a photo" in WhatsApp), or null if there's none. */
    fun defaultCameraPackage(): String? =
        context.packageManager
            .resolveActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
            // "android" means several cameras and no default (the chooser would be shown).
            ?.takeIf { it != "android" }

    /** Opens the dialer keypad. */
    fun dialIntent(): Intent =
        Intent(Intent.ACTION_DIAL)
            .setPackage(defaultDialerPackage())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The App info page for [packageName] in Settings (Force stop, Uninstall, permissions…). */
    fun appInfoIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

    /** The package that shows App info pages (Settings on most phones), or null if none does. */
    fun appInfoPackage(): String? =
        context.packageManager
            .resolveActivity(appInfoIntent(context.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName

    private companion object {
        /** About 44 KB per 40 dp icon on a Pixel 7, so the worst case is about 13 MB. */
        const val ICON_CACHE_SIZE = 300
        const val APP_LIST_CACHE_FILE = "app_list_cache.json"
    }
}
