package com.fabiantorrestech.lockmyphone_extended.trigger.location

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
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
    /** The background scan's keys and plan, so an unchanged one isn't restarted. */
    @Volatile private var running: Pair<Set<String>, ScanPlan>? = null

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
    fun startBackground(places: List<LocationTrigger>, plan: ScanPlan = ScanPlan.BASE) {
        val keys = places.flatMap(::scanKeys).toSet()
        // Restarting a scan has a cost, and Android throttles more than 5 starts in 30 s: only on a change.
        if (running == keys to plan) return
        stopBackground()
        val filters = keys.mapNotNull(::filterFor)
        if (filters.isEmpty() || !hasPermission() || !isBluetoothOn()) return
        val speed = plan.speed
        val settings = ScanSettings.Builder()
            .setScanMode(
                when (speed) {
                    BeaconScanSpeed.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
                    BeaconScanSpeed.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
                    BeaconScanSpeed.LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
                }
            )
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            // Batch sightings for a few seconds: fewer wake-ups, the rule smooths anyway.
            .setReportDelay(plan.batchMs)
            .build()
        val result = adapter?.bluetoothLeScanner?.startScan(filters, settings, backgroundIntent)
        running = keys to plan
        Log.i(TAG, "Background beacon scan ($speed, ${plan.batchMs} ms batches) for ${filters.size} beacon(s): result $result")
    }

    @SuppressLint("MissingPermission")
    fun stopBackground() {
        running = null
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

    /** Every beacon and device key heard during [durationMs], with all its readings (Refresh, Test now). */
    suspend fun readingsByKey(durationMs: Long): Map<String, List<Int>> {
        val readings = mutableMapOf<String, MutableList<Int>>()
        scanFor(durationMs) { result ->
            sightingsOf(result).forEach { readings.getOrPut(it.key) { mutableListOf() } += it.rssi }
        }
        return readings
    }

    /**
     * An open scan for [durationMs], grouped per sender: one entry per physical beacon with every
     * format it sent (strongest of each), so a multi-slot beacon is listed once (ADR 0006).
     * Listed if it sent a recognised format or a name; strongest first.
     */
    suspend fun discoverGrouped(durationMs: Long): List<BeaconGroup> {
        val builders = mutableMapOf<String, GroupBuilder>()
        scanFor(durationMs) { result ->
            val address = PairedDevices.normalize(result.device.address)
            val builder = builders.getOrPut(address) { GroupBuilder(address, addressIsRandom(result)) }
            builder.name = builder.name ?: result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
            builder.strongest = maxOf(builder.strongest, result.rssi)
            parse(result)?.takeIf { it.type != BeaconType.DEVICE }?.let { frame ->
                val previous = builder.frames[frame.key]
                if (previous == null || frame.rssi > previous.rssi) builder.frames[frame.key] = frame
            }
        }
        return builders.values
            .filter { it.frames.isNotEmpty() || it.name != null }
            .map { it.build() }
            .sortedByDescending { it.strongestRssi }
    }

    /** Calls [onSightings] with every sighting (each format, and the sender itself) for [durationMs]: the live signal meter. */
    suspend fun watch(durationMs: Long, onSightings: (List<BeaconSighting>) -> Unit) {
        scanFor(durationMs) { result -> onSightings(sightingsOf(result)) }
    }

    /** True / false on Android 15+, which reports whether an address is random; null before. */
    private fun addressIsRandom(result: ScanResult): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            result.device.addressType == BluetoothDevice.ADDRESS_TYPE_RANDOM
        } else {
            null
        }

    private class GroupBuilder(val address: String, val isRandom: Boolean?) {
        var name: String? = null
        var strongest = Int.MIN_VALUE
        val frames = linkedMapOf<String, BeaconSighting>()

        fun build() = BeaconGroup(
            address = address,
            name = name,
            frames = frames.values.sortedBy { it.type.ordinal },
            strongestRssi = strongest,
            oneMeterPower = frames.values.sortedBy { it.type.ordinal }.firstNotNullOfOrNull { it.oneMeterPower },
            addressRotates = BeaconRules.addressRotates(address, isRandom),
        )
    }

    /** The signal 1 m away that beacon [key] says it has, from a scan of up to [durationMs]; null if it doesn't say. */
    suspend fun advertisedPower(key: String, durationMs: Long): Int? {
        var power: Int? = null
        scanFor(durationMs) { result ->
            if (power == null) power = parse(result)?.takeIf { it.key == key }?.oneMeterPower
        }
        return power
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
            address = PairedDevices.normalize(result.device.address),
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
        val address = PairedDevices.normalize(result.device.address)
        record?.getManufacturerSpecificData(BeaconRules.APPLE_COMPANY_ID)?.let { data ->
            BeaconRules.iBeaconKey(data)?.let {
                return BeaconSighting(it, BeaconType.IBEACON, name ?: "iBeacon", result.rssi, BeaconRules.iBeaconMeasuredPower(data), address)
            }
        }
        // AltBeacon can sit under any company ID.
        record?.manufacturerSpecificData?.let { all ->
            for (i in 0 until all.size()) {
                val data = all.valueAt(i) ?: continue
                BeaconRules.altBeaconKey(all.keyAt(i), data)?.let {
                    return BeaconSighting(it, BeaconType.ALTBEACON, name ?: "AltBeacon", result.rssi, BeaconRules.altBeaconOneMeterPower(data), address)
                }
            }
        }
        record?.getServiceData(ParcelUuid(BeaconRules.APP_SERVICE_UUID))?.let { data ->
            BeaconRules.appKey(data)?.let { return BeaconSighting(it, BeaconType.APP, name ?: "LockMyPhone beacon", result.rssi, address = address) }
        }
        record?.getServiceData(ParcelUuid(BeaconRules.EDDYSTONE_SERVICE_UUID))?.let { data ->
            BeaconRules.eddystoneKey(data)?.let {
                return BeaconSighting(it, BeaconType.EDDYSTONE, name ?: "Eddystone", result.rssi, BeaconRules.eddystoneOneMeterPower(data), address)
            }
        }
        // Other devices only if they have a name (an unnamed device can't be recognised by you).
        if (name.isNullOrBlank()) return null
        return BeaconSighting(BeaconRules.deviceKey(result.device.address), BeaconType.DEVICE, name, result.rssi, address = address)
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
            BeaconType.ALTBEACON -> {
                if (parts.size != 5) return null
                val companyId = parts[1].toIntOrNull() ?: return null
                val uuid = UUID.fromString(parts[2])
                val data = ByteArray(22)
                data[0] = 0xBE.toByte(); data[1] = 0xAC.toByte()
                putLong(data, 2, uuid.mostSignificantBits)
                putLong(data, 10, uuid.leastSignificantBits)
                val id2 = parts[3].toInt()
                val id3 = parts[4].toInt()
                data[18] = (id2 ushr 8).toByte(); data[19] = id2.toByte()
                data[20] = (id3 ushr 8).toByte(); data[21] = id3.toByte()
                ScanFilter.Builder().setManufacturerData(companyId, data, ByteArray(22) { 0xFF.toByte() }).build()
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
    }
}
