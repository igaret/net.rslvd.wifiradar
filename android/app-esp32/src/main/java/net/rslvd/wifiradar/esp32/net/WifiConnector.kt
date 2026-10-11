package net.rslvd.wifiradar.esp32.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Two jobs:
 *  1. Keep this process's sockets on Wi-Fi. The ESP32's AP has no internet, and from
 *     Android 9/10 on the system otherwise routes everything over mobile data, so TCP
 *     connects to 192.168.4.1 would silently fail.
 *  2. Optionally join the ESP32 AP: WifiNetworkSpecifier (API 29+, local-only, shows a
 *     system dialog) or legacy WifiConfiguration (API 26-28).
 */
class WifiConnector(context: Context) {

    private val app = context.applicationContext
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    private val _status = MutableStateFlow("not bound to Wi-Fi")
    val status = _status.asStateFlow()
    var boundNetwork: Network? = null
        private set

    fun bindToWifi() {
        release()
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.bindProcessToNetwork(network)
                boundNetwork = network
                _status.value = "bound to Wi-Fi ${currentSsid()}"
            }
            override fun onLost(network: Network) {
                if (network == boundNetwork) {
                    cm.bindProcessToNetwork(null)
                    boundNetwork = null
                    _status.value = "Wi-Fi lost"
                }
            }
        }
        cm.requestNetwork(request, cb)
        callback = cb
        _status.value = "waiting for Wi-Fi…"
    }

    fun connectToAccessPoint(ssid: String, passphrase: String) {
        release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val spec = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .apply { if (passphrase.isNotEmpty()) setWpa2Passphrase(passphrase) }
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cm.bindProcessToNetwork(network)
                    boundNetwork = network
                    _status.value = "joined \"$ssid\""
                }
                override fun onUnavailable() {
                    _status.value = "could not join \"$ssid\" (declined or not found)"
                }
                override fun onLost(network: Network) {
                    cm.bindProcessToNetwork(null)
                    boundNetwork = null
                    _status.value = "\"$ssid\" lost"
                }
            }
            cm.requestNetwork(request, cb, 30_000)
            callback = cb
            _status.value = "requesting \"$ssid\"… (confirm the system dialog)"
        } else {
            @Suppress("DEPRECATION")
            val conf = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                if (passphrase.isEmpty()) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                else preSharedKey = "\"$passphrase\""
            }
            @Suppress("DEPRECATION")
            run {
                val id = wifi.addNetwork(conf)
                wifi.disconnect()
                wifi.enableNetwork(id, true)
                wifi.reconnect()
            }
            bindToWifi()
        }
    }

    fun release() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        cm.bindProcessToNetwork(null)
        boundNetwork = null
    }

    @Suppress("DEPRECATION")
    fun currentSsid(): String = wifi.connectionInfo?.ssid?.takeIf { it != WifiManager.UNKNOWN_SSID } ?: ""

    fun openWifiSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
