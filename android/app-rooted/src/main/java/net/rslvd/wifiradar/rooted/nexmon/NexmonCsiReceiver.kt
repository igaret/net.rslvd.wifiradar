package net.rslvd.wifiradar.rooted.nexmon

import android.util.Log
import net.rslvd.wifiradar.core.model.CsiFrame
import net.rslvd.wifiradar.rooted.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Receives nexmon CSI frames either (a) directly from a UDP socket on port 5500 — the
 * firmware's fake UDP packets travel up the normal network stack — or (b) by parsing a
 * live pcap stream from a root `tcpdump`, for ROMs whose firewall/driver drops them.
 */
class NexmonCsiReceiver(private val scope: CoroutineScope) {

    enum class Source { UDP_SOCKET, TCPDUMP }

    private val _frames = MutableSharedFlow<CsiFrame>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames = _frames.asSharedFlow()
    val packetCount = AtomicLong()
    val parseErrors = AtomicLong()

    @Volatile var chip: NexmonChip = NexmonChip.BCM4339
    private var job: Job? = null
    private var process: Process? = null
    private var seq = 0L

    val isRunning: Boolean get() = job?.isActive == true

    fun start(source: Source, iface: String, tcpdumpPath: String = "tcpdump") {
        stop()
        packetCount.set(0); parseErrors.set(0); seq = 0
        job = scope.launch(Dispatchers.IO) {
            try {
                when (source) {
                    Source.UDP_SOCKET -> udpLoop()
                    Source.TCPDUMP -> tcpdumpLoop(iface, tcpdumpPath)
                }
            } catch (e: Exception) {
                if (isActive) Log.w(TAG, "receiver stopped: ${e.message}")
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        process?.destroy(); process = null
    }

    private fun handle(data: ByteArray, length: Int) {
        packetCount.incrementAndGet()
        val f = NexmonParser.parse(data, length, chip, System.currentTimeMillis(), seq++)
        if (f != null) _frames.tryEmit(f) else parseErrors.incrementAndGet()
    }

    private suspend fun udpLoop() {
        DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1000
            bind(InetSocketAddress(NexmonParser.UDP_PORT))
        }.use { socket ->
            val buf = ByteArray(4096)
            val packet = DatagramPacket(buf, buf.size)
            while (currentCoroutineContext().isActive) {
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                handle(packet.data, packet.length)
            }
        }
    }

    private suspend fun tcpdumpLoop(iface: String, tcpdumpPath: String) {
        val p = RootShell.start("$tcpdumpPath -i $iface -U -s 0 -w - 'udp dst port ${NexmonParser.UDP_PORT}' 2>/dev/null")
        process = p
        val ctx = currentCoroutineContext()
        try { parsePcapStream(p.inputStream) { ctx.isActive } } finally { p.destroy() }
    }

    /** Minimal libpcap stream reader: 24-byte global header, then 16-byte record headers. */
    private fun parsePcapStream(input: InputStream, stillActive: () -> Boolean) {
        val din = DataInputStream(BufferedInputStream(input, 1 shl 16))
        val gh = ByteArray(24); din.readFully(gh)
        val magicLe = u32(gh, 0, true)
        val little = magicLe == 0xa1b2c3d4L || magicLe == 0xa1b23c4dL
        val linkType = u32(gh, 20, little).toInt()
        val linkHeader = when (linkType) { 1 -> 14; 113 -> 16; 276 -> 20; else -> 14 }
        val rh = ByteArray(16)
        while (stillActive()) {
            din.readFully(rh)
            val caplen = u32(rh, 8, little).toInt()
            if (caplen <= 0 || caplen > 65535) throw IllegalStateException("bad pcap record length $caplen")
            val pkt = ByteArray(caplen); din.readFully(pkt)
            var off = linkHeader
            if (off + 20 > caplen) continue
            val ihl = (pkt[off].toInt() and 0x0f) * 4
            off += ihl + 8                                 // IPv4 header + UDP header
            if (off >= caplen) continue
            handle(pkt.copyOfRange(off, caplen), caplen - off)
        }
    }

    private fun u32(b: ByteArray, off: Int, little: Boolean): Long {
        val v = if (little)
            (b[off].toLong() and 0xff) or ((b[off + 1].toLong() and 0xff) shl 8) or ((b[off + 2].toLong() and 0xff) shl 16) or ((b[off + 3].toLong() and 0xff) shl 24)
        else
            (b[off + 3].toLong() and 0xff) or ((b[off + 2].toLong() and 0xff) shl 8) or ((b[off + 1].toLong() and 0xff) shl 16) or ((b[off].toLong() and 0xff) shl 24)
        return v and 0xffffffffL
    }

    companion object { private const val TAG = "NexmonCsiReceiver" }
}
