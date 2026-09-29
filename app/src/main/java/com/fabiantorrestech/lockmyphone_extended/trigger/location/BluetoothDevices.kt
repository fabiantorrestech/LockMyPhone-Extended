package com.fabiantorrestech.lockmyphone_extended.trigger.location

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.core.util.launchAsync
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** What kind of thing a paired device is, for its icon and label. */
enum class PairedDeviceKind { AUDIO, WATCH, COMPUTER, PHONE, INPUT, OTHER }

/** A device paired with this phone. */
data class PairedDevice(val address: String, val name: String, val kind: PairedDeviceKind)

/** The phone's paired Bluetooth devices ("Nearby devices" permission on Android 12+). */
object PairedDevices {

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Checked by hasPermission().
    fun list(context: Context): List<PairedDevice> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .map { PairedDevice(normalize(it.address), it.name ?: it.address, kindOf(it.bluetoothClass)) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun normalize(address: String): String = address.uppercase(Locale.ROOT)

    private fun kindOf(deviceClass: BluetoothClass?): PairedDeviceKind = when (deviceClass?.majorDeviceClass) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> PairedDeviceKind.AUDIO
        BluetoothClass.Device.Major.WEARABLE -> PairedDeviceKind.WATCH
        BluetoothClass.Device.Major.COMPUTER -> PairedDeviceKind.COMPUTER
        BluetoothClass.Device.Major.PHONE -> PairedDeviceKind.PHONE
        BluetoothClass.Device.Major.PERIPHERAL -> PairedDeviceKind.INPUT
        else -> PairedDeviceKind.OTHER
    }
}

/**
 * Which Bluetooth devices are connected right now, as told by Android's connect/disconnect
 * broadcasts ([BtConnectionReceiver]). Kept in device-protected prefs, stamped with the boot
 * count: a connection from before a reboot doesn't count any more.
 */
class BtConnectionTracker(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun set(address: String, connected: Boolean) = prefs.edit {
        putString(PairedDevices.normalize(address), "${if (connected) 1 else 0}|${bootCount()}")
    }

    /**
     * Connected = inside, anything else = outside. A device never seen connected counts as
     * outside, so its first connection is an arrival.
     */
    fun presence(address: String): Presence {
        val parts = prefs.getString(PairedDevices.normalize(address), null)?.split('|') ?: return Presence.OUTSIDE
        val connected = parts.getOrNull(0) == "1" && parts.getOrNull(1)?.toIntOrNull() == bootCount()
        return if (connected) Presence.INSIDE else Presence.OUTSIDE
    }

    /**
     * Devices connected before the app was watching (e.g. right after install) are found by
     * asking the audio profiles. Other kinds (watches, keyboards) are only known from their next
     * connection.
     */
    @SuppressLint("MissingPermission") // Checked by PairedDevices.hasPermission().
    suspend fun refreshFromProfiles(addresses: Set<String>) {
        if (addresses.isEmpty() || !PairedDevices.hasPermission(appContext)) return
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) return
        val profiles = buildList {
            add(BluetoothProfile.A2DP)
            add(BluetoothProfile.HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(BluetoothProfile.LE_AUDIO)
        }
        val wanted = addresses.map(PairedDevices::normalize).toSet()
        for (profile in profiles) {
            val connected = withTimeoutOrNull(PROXY_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                            val devices = runCatching { proxy.connectedDevices.map { PairedDevices.normalize(it.address) } }.getOrDefault(emptyList())
                            adapter.closeProfileProxy(profileId, proxy)
                            if (continuation.isActive) continuation.resume(devices)
                        }

                        override fun onServiceDisconnected(profileId: Int) = Unit
                    }
                    if (!adapter.getProfileProxy(appContext, listener, profile) && continuation.isActive) continuation.resume(emptyList())
                }
            }.orEmpty()
            connected.filter { it in wanted }.forEach { set(it, connected = true) }
        }
    }

    private fun bootCount(): Int = Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, 0)

    private companion object {
        const val PREFS_NAME = "bt_connections"
        const val PROXY_TIMEOUT_MS = 2_000L
    }
}

/**
 * Android's "a Bluetooth device connected / disconnected" broadcasts. Direct-boot aware, so a
 * device that reconnects right after a reboot is recorded even before the first unlock.
 */
class BtConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val connected = when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
            else -> return
        }
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
        BtConnectionTracker(context).set(device.address, connected)
        Log.i(TAG, "Bluetooth ${if (connected) "connected" else "disconnected"}: ${device.address}")
        // Place triggers read credential-protected settings, so they only run after the first unlock.
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        val container = LockApp.container(context)
        launchAsync(container.appScope) { container.locationTriggerManager.onWake(freshFix = false) }
    }

    private companion object {
        const val TAG = "BtConnection"
    }
}
