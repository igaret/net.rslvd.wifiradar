package net.rslvd.wifiradar.esp32.net

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-device mode illuminator. The ESP32 only sees the channel when a frame
 * arrives, so the phone sends small UDP datagrams at a fixed rate; each one is
 * one CSI sample. The ESP32 discards the payload.
 */
class TrafficGenerator(private val scope: CoroutineScope) {

    val sent = AtomicLong()
    private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    fun start(host: String, port: Int, rateHz: Int) {
        stop()
        val rate = rateHz.coerceIn(1, 500)
        job = scope.launch(Dispatchers.IO) {
            val socket = DatagramSocket()
            val address = InetAddress.getByName(host)
            val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            val periodNs = 1_000_000_000L / rate
            var next = System.nanoTime()
            try {
                while (isActive) {
                    buf.clear()
                    buf.putLong(sent.get())
                    buf.putLong(System.currentTimeMillis())
                    socket.send(DatagramPacket(buf.array(), 16, address, port))
                    sent.incrementAndGet()
                    next += periodNs
                    val waitNs = next - System.nanoTime()
                    if (waitNs > 0) delay(waitNs / 1_000_000) else next = System.nanoTime()   // fell behind: resync
                }
            } catch (e: java.io.IOException) {
                Log.w("TrafficGenerator", "stopped: ${e.message}")      // e.g. ENETUNREACH after the AP vanished
            } finally {
                socket.close()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
