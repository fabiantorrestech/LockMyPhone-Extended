package com.fabiantorrestech.lockmyphone_extended.core.enforce

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/** Locks the device through the Device Admin force-lock policy. */
class Relocker(context: Context) {

    private val devicePolicyManager = context.getSystemService(DevicePolicyManager::class.java)
    val adminComponent = ComponentName(context, AdminReceiver::class.java)

    fun isAdminActive(): Boolean = devicePolicyManager.isAdminActive(adminComponent)

    /** Settings → Permissions → Revoke. Locks can't be enforced until it's turned on again. */
    fun deactivateAdmin() {
        if (isAdminActive()) devicePolicyManager.removeActiveAdmin(adminComponent)
    }

    fun lockNow() {
        if (isAdminActive()) devicePolicyManager.lockNow()
    }
}
