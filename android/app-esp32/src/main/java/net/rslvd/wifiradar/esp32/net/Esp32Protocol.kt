package net.rslvd.wifiradar.esp32.net

import net.rslvd.wifiradar.core.dsp.CsiMath
import net.rslvd.wifiradar.core.model.CsiFrame
import net.rslvd.wifiradar.core.model.CsiSource
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Parser for the ESP32 firmware's binary frames. Layout: docs/PROTOCOL.md. */
object Esp32Protocol {
    const val MAGIC = 0xC51A
    const val TYPE_CSI = 1
    const val TYPE_STATUS = 2
    const val TYPE_TEXT = 3
    const val HEADER_LEN = 6
    const val CSI_HEADER_LEN = 28

    const val DEFAULT_TCP_PORT = 5000
    const val DISCOVERY_PORT = 5005
    const val PING_PORT = 5006
    const val DEFAULT_AP_IP = "192.168.4.1"
    const val DEFAULT_SSID = "WiFiRadar"
    const val DEFAULT_PASSWORD = "wifiradar123"

    private const val FLAG_FIRST_WORD_INVALID = 0x01
    private const val FLAG_LLTF = 0x02

    data class Status(
        val uptimeS: Long, val csiTotal: Long, val csiDropped: Long, val csiFiltered: Long,
        val pingsRx: Long, val txCount: Long, val freeHeap: Long,
        val role: Int, val channel: Int, val rssi: Int, val staCount: Int, val txRateHz: Int,
    ) {
        val roleName: String get() = roleName(role)
    }

    fun roleName(role: Int): String = when (role) { 0 -> "single"; 1 -> "tx"; 2 -> "rx"; else -> "?" }

    private fun ByteBuffer.u32(): Long = int.toLong() and 0xffffffffL
    private fun ByteBuffer.u8(): Int = get().toInt() and 0xff

    fun parseStatus(p: ByteArray): Status? {
        if (p.size < 36) return null
        val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        return Status(
            uptimeS = b.u32(), csiTotal = b.u32(), csiDropped = b.u32(), csiFiltered = b.u32(),
            pingsRx = b.u32(), txCount = b.u32(), freeHeap = b.u32(),
            role = b.u8(), channel = b.u8(), rssi = b.get().toInt(), staCount = b.u8(),
            txRateHz = b.short.toInt() and 0xffff,
        )
    }

    /**
     * Decodes a CSI payload. The "primary" LTF segment (LLTF when enabled, otherwise the
     * first HT-LTF) is converted to natural sub-carrier order for display/analysis; the
     * full raw int8 buffer is kept in [CsiFrame.raw] for export.
     */
    fun parseCsi(p: ByteArray, receivedAtMs: Long): CsiFrame? {
        if (p.size < CSI_HEADER_LEN) return null
        val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        val seq = b.u32()
        val timestampUs = b.u32()
        val mac = ByteArray(6).also { b.get(it) }
        val rssi = b.get().toInt()
        val noise = b.get().toInt()
        val channel = b.u8()
        /* secondary channel */ b.u8()
        val sigMode = b.u8()
        val mcs = b.u8()
        val cwb = b.u8()
        /* rate */ b.u8(); /* stbc */ b.u8(); /* sgi */ b.u8(); /* ant */ b.u8()
        val flags = b.u8()
        val csiLen = b.short.toInt() and 0xffff
        if (csiLen < 2 || p.size < CSI_HEADER_LEN + csiLen) return null

        val pairs = csiLen / 2
        val hasLltf = flags and FLAG_LLTF != 0
        // LLTF is always 64 entries; an HT-LTF is 64 (20 MHz) or 128 (40 MHz).
        val n = (if (hasLltf) 64 else if (cwb == 1) 128 else 64).coerceAtMost(pairs)
        val reFft = FloatArray(n)
        val imFft = FloatArray(n)
        for (k in 0 until n) {
            // ESP32 stores (imag, real) per sub-carrier
            imFft[k] = p[CSI_HEADER_LEN + 2 * k].toFloat()
            reFft[k] = p[CSI_HEADER_LEN + 2 * k + 1].toFloat()
        }
        val firstWordInvalid = flags and FLAG_FIRST_WORD_INVALID != 0
        if (firstWordInvalid) {                       // hardware quirk: first 4 bytes are garbage
            reFft[0] = 0f; imFft[0] = 0f
            if (n > 1) { reFft[1] = 0f; imFft[1] = 0f }
        }
        val re = CsiMath.fftShift(reFft)
        val im = CsiMath.fftShift(imFft)
        val halfWidth = when { hasLltf -> 26; n == 128 -> 58; else -> 28 }
        val valid = CsiMath.usedSubcarrierMask(n, halfWidth)
        if (firstWordInvalid && n / 2 + 1 < n) valid[n / 2 + 1] = false   // sub-carrier +1 (0 is DC anyway)

        return CsiFrame(
            source = CsiSource.ESP32,
            receivedAtMs = receivedAtMs,
            seq = seq,
            deviceTimestampUs = timestampUs,
            sourceMac = mac.joinToString(":") { "%02x".format(it) },
            rssi = rssi,
            noiseFloor = noise,
            channel = channel,
            bandwidthMhz = if (cwb == 1) 40 else 20,
            sigMode = sigMode,
            mcs = mcs,
            re = re,
            im = im,
            validMask = valid,
            raw = p.copyOfRange(CSI_HEADER_LEN, CSI_HEADER_LEN + csiLen),
        )
    }
}
