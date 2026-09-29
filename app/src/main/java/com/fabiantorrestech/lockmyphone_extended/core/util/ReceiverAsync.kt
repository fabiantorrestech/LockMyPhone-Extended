package com.fabiantorrestech.lockmyphone_extended.core.util

import android.content.BroadcastReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Runs [block] in [scope] and keeps the broadcast alive until it finishes, so a receiver can
 * do database work off the main thread. The system allows about 10 seconds.
 */
fun BroadcastReceiver.launchAsync(scope: CoroutineScope, block: suspend () -> Unit) {
    val pending = goAsync()
    scope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}
