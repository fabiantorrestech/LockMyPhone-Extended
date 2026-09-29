package com.fabiantorrestech.lockmyphone_extended.core.enforce

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.R

class AdminReceiver : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        if (LockApp.container(context).lockController.isLockActive()) {
            context.getString(R.string.admin_disable_warning_locked)
        } else {
            context.getString(R.string.admin_disable_warning)
        }
}
