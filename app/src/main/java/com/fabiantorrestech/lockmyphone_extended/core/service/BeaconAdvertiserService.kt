package com.fabiantorrestech.lockmyphone_extended.core.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.fabiantorrestech.lockmyphone_extended.LockApp
import com.fabiantorrestech.lockmyphone_extended.R
import com.fabiantorrestech.lockmyphone_extended.trigger.location.BeaconRules
import java.time.LocalTime

/**
 * "Act as a beacon": this device (e.g. a tablet you work on) broadcasts a small Bluetooth LE
 * beacon, so a phone can use "near this tablet" as a place. It runs as a quiet foreground
 * service, always or only during the chosen hours.
 */
class BeaconAdvertiserService : Service() {

    private val callback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Beacon advertising failed: $errorCode")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(getString(R.string.beacon_mode_notification))
            .setContentText(getString(R.string.beacon_mode_notification_text, BeaconMode.key(this)))
            .setOngoing(true)
            .setSilent(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        startAdvertising()
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // BeaconMode only starts the service with the permission.
    private fun startAdvertising() {
        val advertiser = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(BeaconRules.APP_SERVICE_UUID), BeaconRules.appPayload(BeaconMode.id(this)))
            .setIncludeDeviceName(false)
            .build()
        runCatching { advertiser.stopAdvertising(callback) }
        advertiser.startAdvertising(settings, data, callback)
        Log.i(TAG, "Advertising as ${BeaconMode.key(this)}")
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "BeaconMode"
        private const val CHANNEL = "beacon_mode"
        private const val NOTIFICATION_ID = 5001

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.beacon_mode_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}

/** Starts or stops [BeaconAdvertiserService] to match the settings, and wakes at the hours' edges. */
object BeaconMode {

    private const val REQUEST_CODE = 5002

    fun id(context: Context): Int = LockApp.container(context).settingsStore.beaconModeId

    fun key(context: Context): String = BeaconRules.appKey(BeaconRules.appPayload(id(context))).orEmpty()

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    /** Call after any change to the beacon settings, at boot, and at each hours' edge. */
    fun sync(context: Context) {
        val settings = LockApp.container(context).settingsStore
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val edgeIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, BeaconModeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val service = Intent(context, BeaconAdvertiserService::class.java)
        if (!settings.beaconModeEnabled.value || !hasPermission(context)) {
            context.stopService(service)
            alarmManager.cancel(edgeIntent)
            return
        }
        val start = settings.beaconModeStartMinutes.value
        val end = settings.beaconModeEndMinutes.value
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        val active = start == null || end == null || withinHours(now, start, end)
        if (active) {
            runCatching { ContextCompat.startForegroundService(context, service) }
                .onFailure { Log.w("BeaconMode", "Couldn't start beacon mode", it) }
        } else {
            context.stopService(service)
        }
        // Wake at the next edge (start or end) to switch again.
        if (start != null && end != null) {
            val nextEdge = if (active) end else start
            val minutesAhead = ((nextEdge - now) + 24 * 60) % (24 * 60)
            val atMs = System.currentTimeMillis() + (if (minutesAhead == 0) 24 * 60 else minutesAhead) * 60_000L
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, edgeIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, edgeIntent)
            }
        } else {
            alarmManager.cancel(edgeIntent)
        }
    }

    /** Whether [now] is in [start, end), which may run past midnight. */
    fun withinHours(now: Int, start: Int, end: Int): Boolean =
        if (start <= end) now in start until end else now >= start || now < end
}

/** The beacon hours' start or end. */
class BeaconModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = BeaconMode.sync(context)
}
