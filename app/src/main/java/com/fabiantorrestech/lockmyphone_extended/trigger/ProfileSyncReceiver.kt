package com.fabiantorrestech.lockmyphone_extended.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.core.lock.PickerLayout
import com.fabiantorrestech.lockmyphone_extended.core.lock.PickerLayoutJson
import com.fabiantorrestech.lockmyphone_extended.core.util.launchAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lets a companion app keep a profile of its own in sync (ADR 0008). The manifest guards this
 * receiver with a signature permission, so only an app signed with this app's key can send it.
 *
 * `ACTION_SYNC_PROFILE` with extras:
 * - `manager`: the sending app's package (who owns the profile)
 * - `name`: the profile's name when it's first created
 * - `allowed`, `hidden`: string arrays of package names (hidden ones must also be allowed)
 * - optional `folders`: JSON `[{"name", "packages": [...], "shown"}]`, the allowed-apps list's
 *   folders (`shown` false = found only by the search); a folder's apps are listed only inside it
 * - optional `order`: JSON `["folder:<name>" | "app:<package>", ...]`, the list's order
 *   Without either, the profile keeps its last folders and order.
 *
 * The profile is found by its manager, so a profile you renamed keeps your name; only its apps
 * follow the sync. A lock that's already running isn't changed. The manager's own app is always
 * allowed in it.
 */
class ProfileSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SYNC_PROFILE) return
        val manager = intent.getStringExtra(EXTRA_MANAGER)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val name = intent.getStringExtra(EXTRA_NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val allowed = intent.getStringArrayExtra(EXTRA_ALLOWED).orEmpty().toSet() + manager
        val hidden = intent.getStringArrayExtra(EXTRA_HIDDEN).orEmpty().toSet() intersect allowed
        val sentLayout = readLayout(intent, allowed)
        // A new profile takes its length and early unlock from Lock defaults, which are
        // credential-protected: before the first unlock after a reboot, wait for the next sync.
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) {
            Log.w(TAG, "SYNC_PROFILE from $manager ignored: not unlocked since boot")
            return
        }
        val container = LockApp.container(context)
        launchAsync(container.appScope) {
            withContext(Dispatchers.IO) {
                val dao = container.database.profileDao()
                val existing = dao.getManagedBy(manager) ?: dao.getByName(name)
                if (existing != null) {
                    dao.update(
                        existing.copy(
                            allowedPackages = allowed,
                            hiddenPackages = hidden,
                            managedBy = manager,
                            pickerLayout = sentLayout ?: existing.pickerLayout,
                        )
                    )
                    Log.i(TAG, "SYNC_PROFILE '${existing.name}' from $manager: ${allowed.size} apps (${hidden.size} hidden), ${folderCount(sentLayout)}")
                } else {
                    dao.insertLast(
                        container.settingsStore.lockDefaults.value.toProfileDraft(name).copy(
                            allowedPackages = allowed,
                            hiddenPackages = hidden,
                            icon = MANAGED_ICON,
                            managedBy = manager,
                            pickerLayout = sentLayout ?: PickerLayout.NONE,
                        )
                    )
                    Log.i(TAG, "SYNC_PROFILE created '$name' for $manager: ${allowed.size} apps (${hidden.size} hidden), ${folderCount(sentLayout)}")
                }
            }
        }
    }

    /** The folders and order sent, or null when neither was (an older sender: keep what's saved). */
    private fun readLayout(intent: Intent, allowed: Set<String>): PickerLayout? {
        val folders = intent.getStringExtra(EXTRA_FOLDERS)
        val order = intent.getStringExtra(EXTRA_ORDER)
        if (folders == null && order == null) return null
        return PickerLayout(
            // Only allowed apps can be in a folder.
            folders = folders?.let(PickerLayoutJson::readFolders).orEmpty()
                .map { it.copy(packages = it.packages.filter(allowed::contains)) },
            order = order?.let(PickerLayoutJson::readOrder).orEmpty(),
        )
    }

    private fun folderCount(layout: PickerLayout?): String =
        if (layout == null) "folders unchanged" else "${layout.folders.size} folders"

    companion object {
        private const val TAG = "ProfileSync"
        const val ACTION_SYNC_PROFILE = "com.fabiantorrestech.lockmyphone_extended.action.SYNC_PROFILE"
        const val EXTRA_MANAGER = "manager"
        const val EXTRA_NAME = "name"
        const val EXTRA_ALLOWED = "allowed"
        const val EXTRA_HIDDEN = "hidden"
        const val EXTRA_FOLDERS = "folders"
        const val EXTRA_ORDER = "order"
        private const val MANAGED_ICON = "🤝"
    }
}
