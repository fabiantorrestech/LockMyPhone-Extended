package com.fabiantorrestech.lockmyphone_extended.trigger.location

import com.fabiantorrestech.lockmyphone_extended.data.db.WifiMatch
import com.fabiantorrestech.lockmyphone_extended.data.db.WifiNetwork

/** A Wi-Fi network seen in a scan: in range, not necessarily connected. */
data class NearbyWifi(val bssid: String, val ssid: String?, val rssi: Int)

/** Whether a Wi-Fi connection (or a network in range) is one of a place's networks. Pure. */
object WifiRules {

    /** [network] is [wifi]: the same router (BSSID, any case), or the same name (SSID, exact). */
    fun matches(network: WifiNetwork, bssid: String?, ssid: String?): Boolean = when (network.matchBy) {
        WifiMatch.ROUTER -> network.bssid != null && network.bssid.equals(bssid, ignoreCase = true)
        WifiMatch.NAME -> network.ssid != null && network.ssid == ssid
    }

    /** Connected to any one of [networks]. */
    fun matchesAny(networks: List<WifiNetwork>, connected: ConnectedWifi?): Boolean =
        connected != null && networks.any { matches(it, connected.bssid, connected.ssid) }

    /** Any one of [networks] is in range ([seen]: the latest scan), connected or not. */
    fun inRange(networks: List<WifiNetwork>, seen: List<NearbyWifi>): Boolean =
        networks.any { network -> seen.any { matches(network, it.bssid, it.ssid) } }
}
