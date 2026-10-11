package net.rslvd.wifiradar.rooted.nexmon

import java.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Kotlin port of nexmon_csi `utils/makecsiparams`. Produces the 34-byte parameter
 * blob that is written to the firmware with:  nexutil -I<iface> -s500 -b -l34 -v<base64>
 *
 * struct csi_params {            // __attribute__((packed))
 *   uint16 chanspec;             // channel + bandwidth + sideband + band (Broadcom D11AC format)
 *   uint8  csi_collect;          // 1 = on
 *   uint8  core_nss_mask;        // core bitmask (low nibble) | spatial-stream bitmask (high nibble)
 *   uint8  use_pkt_filter;       // 1 = only frames whose first byte (frame control) == first_pkt_byte
 *   uint8  first_pkt_byte;
 *   uint16 n_mac_addr;           // 0..4 source-MAC filters
 *   uint16 cmp_src_mac_[0..3]_[0..2];   // 4 MACs × 6 bytes
 *   uint16 delay;                // µs between CSI extractions
 * }
 */
object CsiParams {

    data class Config(
        val channel: Int,
        val bandwidthMhz: Int,
        val coreMask: Int = 0x1,
        val nssMask: Int = 0x1,
        val macFilters: List<String> = emptyList(),
        val firstPacketByte: Int? = null,     // e.g. 0x88 = QoS data
        val delayUs: Int = 0,
        val collect: Boolean = true,
    )

    private const val WL_CHANSPEC_BW_20 = 0x1000
    private const val WL_CHANSPEC_BW_40 = 0x1800
    private const val WL_CHANSPEC_BW_80 = 0x2000
    private const val WL_CHANSPEC_BAND_5G = 0xC000
    private const val WL_CHANSPEC_BAND_2G = 0x0000
    private const val WL_CHANSPEC_CTL_SB_U = 0x0100
    private val centers80 = listOf(42, 58, 106, 122, 138, 155)

    /** Equivalent of wf_chspec_aton("<ctl>/<bw>") for 20/40/80 MHz. */
    fun chanspec(ctlChannel: Int, bandwidthMhz: Int): Int {
        val band = if (ctlChannel > 14) WL_CHANSPEC_BAND_5G else WL_CHANSPEC_BAND_2G
        return when (bandwidthMhz) {
            20 -> ctlChannel or WL_CHANSPEC_BW_20 or band
            40 -> {
                // 40 MHz: the control channel is the lower (sideband L) or upper (U) 20 MHz half.
                val lower = if (ctlChannel <= 14) ctlChannel <= 7
                            else if (ctlChannel >= 149) ((ctlChannel - 149) / 4) % 2 == 0
                            else ((ctlChannel - 36) / 4) % 2 == 0
                val center = if (lower) ctlChannel + 2 else ctlChannel - 2
                center or WL_CHANSPEC_BW_40 or (if (lower) 0 else WL_CHANSPEC_CTL_SB_U) or band
            }
            80 -> {
                val center = centers80.firstOrNull { abs(it - ctlChannel) <= 6 }
                    ?: throw IllegalArgumentException("channel $ctlChannel is not inside an 80 MHz block")
                val sb = (ctlChannel - (center - 6)) / 4            // 0=LL 1=LU 2=UL 3=UU
                center or WL_CHANSPEC_BW_80 or (sb shl 8) or band
            }
            else -> throw IllegalArgumentException("bandwidth must be 20, 40 or 80 MHz")
        }
    }

    fun encode(cfg: Config): ByteArray {
        require(cfg.macFilters.size <= 4) { "at most 4 MAC filters" }
        val b = ByteBuffer.allocate(34).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(chanspec(cfg.channel, cfg.bandwidthMhz).toShort())
        b.put(if (cfg.collect) 1 else 0)
        b.put(((cfg.coreMask and 0xF) or ((cfg.nssMask and 0xF) shl 4)).toByte())
        b.put(if (cfg.firstPacketByte != null) 1 else 0)
        b.put((cfg.firstPacketByte ?: 0).toByte())
        b.putShort(cfg.macFilters.size.toShort())
        for (i in 0 until 4) b.put(cfg.macFilters.getOrNull(i)?.let { parseMac(it) } ?: ByteArray(6))
        b.putShort(cfg.delayUs.toShort())
        return b.array()
    }

    fun encodeBase64(cfg: Config): String = Base64.getEncoder().encodeToString(encode(cfg))

    fun parseMac(s: String): ByteArray {
        val parts = s.trim().split(':', '-')
        require(parts.size == 6) { "bad MAC: $s" }
        return ByteArray(6) { parts[it].toInt(16).toByte() }
    }

    fun frequencyToChannel(freqMhz: Int): Int = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz in 5000..5900 -> (freqMhz - 5000) / 5
        else -> 0
    }
}
