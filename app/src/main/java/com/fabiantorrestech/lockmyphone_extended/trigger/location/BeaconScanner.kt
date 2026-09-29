package com.fabiantorrestech.lockmyphone_extended.trigger.location

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.fabiantorrestech.lockmyphone_extended.data.db.LocationTrigger
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Bluetooth LE scanning for beacon places:
 * - in the background, a low-power scan filtered to the chosen beacons delivers sightings to
 *   [LocationReceiver] (works while the app isn't running)
 * - in the editor, a short open scan lists nearby beacons, and calibration samples one of them
 */
class BeaconScanner(private val context: Context) {

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val backgroundIntent by lazy { locationPendingIntent(context, LocationReceiver.ACTION_BEACON, REQUEST_CODE) }

    fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    /** Watches the beacons of [places] in the background; replaces any earlier background scan. */
    @SuppressLint("MissingPermission") // Checked by hasPermission().
    fun startBackground(places: List<LocationTrigger>) {
        stopBackground()
        val filters = places.flatMap(::scanKeys).distinct().mapNotNull(::filterFor)
        if (filters.isEmpty() || !hasPermission() || !isBluetoothOn()) return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            // Batch sightings for a few seconds: fewer wake-ups, the rule smooths anyway.
            .setReportDelay(REPORT_DELAY_MS)
            .build()
        val result = adapter?.bluetoothLeScanner?.startScan(filters, settings, backgroundIntent)
        Log.i(TAG, "Background beacon scan for ${filters.size} beacon(s): result $result")
    }

    @SuppressLint("MissingPermission")
    fun stopBackground() {
        if (!hasPermission()) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(backgroundIntent) }
    }

    /** An open scan for [durationMs], for picking a beacon: the strongest sighting of each. */
    suspend fun discover(durationMs: Long): List<BeaconSighting> {
        val found = mutableMapOf<String, BeaconSighting>()
        scanFor(durationMs) { result ->
            val sighting = parse(result) ?: return@scanFor
            val previous = found[sighting.key]
            if (previous == null || sighting.rssi > previous.rssi) found[sighting.key] = sighting
        }
        return found.values.sortedByDescending { it.rssi }
    }

    /** Every reading of [key] (a beacon or a device) during [durationMs] (calibration). */
    suspend fun sample(key: String, durationMs: Long): List<Int> {
        val readings = mutableListOf<Int>()
        scanFor(durationMs) { result -> sightingsOf(result).filter { it.key == key }.forEach { readings += it.rssi } }
        return readings
    }

    /** The addresses heard during [durationMs], with their strongest signal (paired-device picker). */
    suspend fun seenAddresses(durationMs: Long): Map<String, Int> {
        val seen = mutableMapOf<String, Int>()
        scanFor(durationMs) { result ->
            val address = PairedDevices.normalize(result.device.address)
            seen[address] = maxOf(seen[address] ?: Int.MIN_VALUE, result.rssi)
        }
        return seen
    }

    /**
     * Everything one background result can match: the beacon it carries, if any, and the device
     * itself by address (for paired devices detected by being nearby, which often have no name).
     */
    fun sightingsOf(result: ScanResult): List<BeaconSighting> {
        val beacon = parse(result)
        val device = BeaconSighting(
            BeaconRules.deviceKey(result.device.address),
            BeaconType.DEVICE,
            result.scanRecord?.deviceName ?: result.device.address,
            result.rssi,
        )
        return if (beacon == null || beacon.key == device.key) listOf(device) else listOf(beacon, device)
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanFor(durationMs: Long, onResult: (ScanResult) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner ?: return
        if (!hasPermission()) return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                onResult(result)
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        try {
            delay(durationMs)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    /** A sighting from a scan result, if it's a beacon the app understands (or a named device). */
    fun parse(result: ScanResult): BeaconSighting? {
        val record = result.scanRecord
        val name = record?.deviceName
        record?.getManufacturerSpecificData(BeaconRules.APPLE_COMPANY_ID)?.let { data ->
            BeaconRules.iBeaconKey(data)?.let { return BeaconSighting(it, BeaconType.IBEACON, name ?: "iBeacon", result.rssi) }
        }
        record?.getServiceData(ParcelUuid(BeaconRules.APP_SERVICE_UUID))?.let { data ->
            BeaconRules.appKey(data)?.let { return BeaconSighting(it, BeaconType.APP, name ?: "LockMyPhone beacon", result.rssi) }
        }
        record?.getServiceData(ParcelUuid(BeaconRules.EDDYSTONE_SERVICE_UUID))?.let { data ->
            BeaconRules.eddystoneKey(data)?.let { return BeaconSighting(it, BeaconType.EDDYSTONE, name ?: "Eddystone", result.rssi) }
        }
        // Other devices only if they have a name (an unnamed device can't be recognised by you).
        if (name.isNullOrBlank()) return null
        return BeaconSighting(BeaconRules.deviceKey(result.device.address), BeaconType.DEVICE, name, result.rssi)
    }

    /** The background-scan filter that matches exactly the beacon [key]. */
    private fun filterFor(key: String): ScanFilter? {
        val parts = key.split(':')
        return when (BeaconRules.typeOf(key)) {
            BeaconType.IBEACON -> {
                if (parts.size != 4) return null
                val uuid = UUID.fromString(parts[1])
                val data = ByteArray(22)
                data[0] = 0x02; data[1] = 0x15
                putLong(data, 2, uuid.mostSignificantBits)
                putLong(data, 10, uuid.leastSignificantBits)
                val major = parts[2].toInt()
                val minor = parts[3].toInt()
                data[18] = (major ushr 8).toByte(); data[19] = major.toByte()
                data[20] = (minor ushr 8).toByte(); data[21] = minor.toByte()
                ScanFilter.Builder()
                    .setManufacturerData(BeaconRules.APPLE_COMPANY_ID, data, ByteArray(22) { 0xFF.toByte() })
                    .build()
            }
            BeaconType.EDDYSTONE -> {
                if (parts.size != 3) return null
                val data = ByteArray(18)
                hexInto(parts[1], data, 2)
                hexInto(parts[2], data, 12)
                // Match the frame type, namespace and instance; ignore the TX power byte.
                val mask = ByteArray(18) { 0xFF.toByte() }.also { it[1] = 0 }
                ScanFilter.Builder().setServiceData(ParcelUuid(BeaconRules.EDDYSTONE_SERVICE_UUID), data, mask).build()
            }
            BeaconType.APP -> {
                if (parts.size != 2) return null
                val data = ByteArray(4).also { hexInto(parts[1], it, 0) }
                ScanFilter.Builder()
                    .setServiceData(ParcelUuid(BeaconRules.APP_SERVICE_UUID), data, ByteArray(4) { 0xFF.toByte() })
                    .build()
            }
            BeaconType.DEVICE -> ScanFilter.Builder().setDeviceAddress(key.removePrefix("device:")).build()
            null -> null
        }
    }

    private fun putLong(target: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) target[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }

    private fun hexInto(hex: String, target: ByteArray, offset: Int) {
        hex.chunked(2).forEachIndexed { i, byte -> target[offset + i] = byte.toInt(16).toByte() }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "BeaconScanner"
        const val REQUEST_CODE = 4006
        const val REPORT_DELAY_MS = 5_000L
    }
}
