package net.rslvd.wifiradar.esp32.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** Listens for the firmware's 1 Hz UDP broadcast announcements. */
class Esp32Discovery(context: Context, private val scope: CoroutineScope) {

    data class Device(
        val role: String, val mac: String, val ip: String, val port: Int,
        val fw: String, val ssid: String, val lastSeenMs: Long,
    )

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices = _devices.asStateFlow()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        if (isRunning) return
        // Without this many Wi-Fi drivers drop broadcast/multicast frames to save power.
        multicastLock = wifi.createMulticastLock("wifiradar-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        job = scope.launch(Dispatchers.IO) {
            try {
                val s = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 1000
                    bind(InetSocketAddress(Esp32Protocol.DISCOVERY_PORT))
                }
                socket = s
                val buf = ByteArray(1024)
                val packet = DatagramPacket(buf, buf.size)
                while (isActive) {
                    try {
                        s.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        prune()
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    runCatching { JSONObject(text) }.getOrNull()?.let { json ->
                        if (json.optString("name") != "WiFiRadar") return@let
                        val d = Device(
                            role = json.optString("role", "?"),
                            mac = json.optString("mac", "?"),
                            ip = json.optString("ip").ifEmpty { packet.address.hostAddress ?: "?" },
                            port = json.optInt("port", Esp32Protocol.DEFAULT_TCP_PORT),
                            fw = json.optString("fw", "?"),
                            ssid = json.optString("ssid", ""),
                            lastSeenMs = System.currentTimeMillis(),
                        )
                        _devices.value = (_devices.value.filter { it.mac != d.mac } + d).sortedBy { it.role }
                    }
                }
            } catch (e: Exception) {
                if (isActive) Log.w(TAG, "discovery stopped: ${e.message}")
            }
        }
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        val alive = _devices.value.filter { now - it.lastSeenMs < 6000 }
        if (alive.size != _devices.value.size) _devices.value = alive
    }

    fun stop() {
        job?.cancel(); job = null
        socket?.close(); socket = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        _devices.value = emptyList()
    }

    companion object { private const val TAG = "Esp32Discovery" }
}
