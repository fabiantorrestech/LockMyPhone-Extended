package com.fabiantorrestech.lockmyphone_extended.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.fabiantorrestech.lockmyphone_extended.LockApp
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
                    dao.update(existing.copy(allowedPackages = allowed, hiddenPackages = hidden, managedBy = manager))
                    Log.i(TAG, "SYNC_PROFILE '${existing.name}' from $manager: ${allowed.size} apps (${hidden.size} hidden)")
                } else {
                    dao.insertLast(
                        container.settingsStore.lockDefaults.value.toProfileDraft(name).copy(
                            allowedPackages = allowed,
                            hiddenPackages = hidden,
                            icon = MANAGED_ICON,
                            managedBy = manager,
                        )
                    )
                    Log.i(TAG, "SYNC_PROFILE created '$name' for $manager: ${allowed.size} apps (${hidden.size} hidden)")
                }
            }
        }
    }

    companion object {
        private const val TAG = "ProfileSync"
        const val ACTION_SYNC_PROFILE = "com.fabiantorrestech.lockmyphone_extended.action.SYNC_PROFILE"
        const val EXTRA_MANAGER = "manager"
        const val EXTRA_NAME = "name"
        const val EXTRA_ALLOWED = "allowed"
        const val EXTRA_HIDDEN = "hidden"
        private const val MANAGED_ICON = "🤝"
    }
}
