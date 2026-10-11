package net.rslvd.wifiradar.rooted.nexmon

import net.rslvd.wifiradar.core.dsp.CsiMath
import net.rslvd.wifiradar.core.model.CsiFrame
import net.rslvd.wifiradar.core.model.CsiSource
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the UDP payload the nexmon_csi firmware injects (dst 255.255.255.255:5500).
 *
 *  0-1   magic 0x1111        2 rssi (int8)        3 frame control
 *  4-9   source MAC          10-11 seq nr         12-13 core|nss (core bits 0-2, nss bits 3-5)
 *  14-15 chanspec            16-17 chip version   18.. CSI data (nfft = bw × 3.2 sub-carriers)
 *
 * bcm4339 / bcm43455c0: int16 real, int16 imag per sub-carrier.
 * bcm4358 / bcm4366c0: one packed-float uint32 per sub-carrier (unpack_float_acphy; bcm4358 =
 * format 0 with 9/5 mantissa/exponent bits, bcm4366c0 = format 1 with 12/6).
 * The firmware overwrites 3 tones per frame with PHY status words (tones +28..+30 at 20 MHz,
 * tones 0..+2 at 40/80 MHz); those are masked out.
 */
object NexmonParser {
    const val HEADER_LEN = 18
    const val UDP_PORT = 5500

    fun parse(payload: ByteArray, length: Int, chip: NexmonChip, receivedAtMs: Long, seq: Long): CsiFrame? {
        if (length < HEADER_LEN + 4) return null
        if (payload[0] != 0x11.toByte() || payload[1] != 0x11.toByte()) return null
        val b = ByteBuffer.wrap(payload, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val rssi = b.get(2).toInt()
        val mac = ByteArray(6).also { for (i in 0 until 6) it[i] = payload[4 + i] }
        val coreNss = b.getShort(12).toInt() and 0xffff
        val chanspec = b.getShort(14).toInt() and 0xffff
        val bw = when (chanspec and 0x3800) { 0x1000 -> 20; 0x1800 -> 40; 0x2000 -> 80; 0x2800 -> 160; else -> 20 }
        val nfft = bw * 16 / 5                       // 64 / 128 / 256 / 512
        if (length - HEADER_LEN < nfft * 4) return null

        val reFft = FloatArray(nfft)
        val imFft = FloatArray(nfft)
        if (chip.packedFloat) {
            val words = IntArray(nfft) { b.getInt(HEADER_LEN + 4 * it) }
            val out = IntArray(nfft * 2)
            unpackFloatAcphy(10, true, chip.nman, chip.nexp, nfft, words, out)
            for (i in 0 until nfft) { reFft[i] = out[2 * i].toFloat(); imFft[i] = out[2 * i + 1].toFloat() }
        } else {
            for (i in 0 until nfft) {
                reFft[i] = b.getShort(HEADER_LEN + 4 * i).toFloat()
                imFft[i] = b.getShort(HEADER_LEN + 4 * i + 2).toFloat()
            }
        }
        val valid = when (nfft) {
            64 -> CsiMath.usedSubcarrierMask(64, 28).also { it[32 + 28] = false }          // HT20 ±1..±28, +28 = phystatus
            128 -> CsiMath.usedSubcarrierMask(128, 58, 2).also { it[64 + 2] = false }      // HT40 ±2..±58, +2 = phystatus
            256 -> CsiMath.usedSubcarrierMask(256, 122, 2).also { it[128 + 2] = false }    // VHT80 ±2..±122
            else -> BooleanArray(nfft) { true }.also { for (k in 0..2) it[nfft / 2 + k] = false }
        }
        return CsiFrame(
            source = CsiSource.NEXMON,
            receivedAtMs = receivedAtMs,
            seq = seq,
            deviceTimestampUs = 0,
            sourceMac = mac.joinToString(":") { "%02x".format(it) },
            rssi = rssi,
            noiseFloor = 0,
            channel = chanspec and 0xff,
            bandwidthMhz = bw,
            sigMode = -1,
            mcs = (coreNss shr 3) and 0x7,            // spatial stream index, the closest thing we get
            re = CsiMath.fftShift(reFft),
            im = CsiMath.fftShift(imFft),
            validMask = valid,
            raw = payload.copyOf(length),
        )
    }

    private const val SGN_MASK = Int.MIN_VALUE   // 1 << 31

    /**
     * Port of nexmon_csi utils/matlab/unpack_float.c::unpack_float_acphy for format 1
     * (nman = 12 mantissa bits incl. sign, nexp = 6 exponent bits). Word layout:
     * [29] sign_re [28:18] mant_re [17] sign_im [16:6] mant_im [5:0] exponent (two's complement).
     * With autoscale the whole vector is shifted so its largest value uses `nbits` bits.
     */
    fun unpackFloatAcphy(nbits: Int, autoscale: Boolean, nman: Int, nexp: Int, nfft: Int, h: IntArray, out: IntArray) {
        val iqMask = (1 shl (nman - 1)) - 1
        val eMask = (1 shl nexp) - 1
        val eP = 1 shl (nexp - 1)
        val sgnrMask = 1 shl (nexp + 2 * nman - 1)
        val sgniMask = sgnrMask ushr nman
        val eZero = -nman
        val he = IntArray(nfft)
        var maxbit = -eP

        for (i in 0 until nfft) {
            val w = h[i]
            var vi = (w ushr (nexp + nman)) and iqMask
            var vq = (w ushr nexp) and iqMask
            var e = w and eMask
            if (e >= eP) e -= eP shl 1
            he[i] = e
            var x = vi or vq
            if (autoscale && x != 0) {
                var m = -0x10000           // 0xffff0000
                var bm = 0xffff
                var s = 16
                while (s > 0) {
                    if (x and m != 0) { e += s; x = x ushr s }
                    s = s shr 1
                    m = (m ushr s) and bm
                    bm = bm ushr s
                }
                if (e > maxbit) maxbit = e
            }
            if (w and sgnrMask != 0) vi = vi or SGN_MASK
            if (w and sgniMask != 0) vq = vq or SGN_MASK
            out[i shl 1] = vi
            out[(i shl 1) + 1] = vq
        }

        val shft = nbits - maxbit
        for (i in 0 until nfft * 2) {
            var e = he[i shr 1] + shft
            var v = out[i]
            var sgn = 1
            if (v and SGN_MASK != 0) { sgn = -1; v = v and SGN_MASK.inv() }
            v = when {
                e < eZero -> 0
                e < 0 -> { e = -e; v shr e }
                else -> v shl e
            }
            out[i] = sgn * v
        }
    }
}
