package net.rslvd.wifiradar.core.model

import net.rslvd.wifiradar.core.dsp.CsiMath

enum class CsiSource { ESP32, NEXMON, DEMO }

/**
 * One CSI measurement, normalised across back-ends.
 *
 * [re]/[im] are per sub-carrier in *natural* order: index k is sub-carrier (k - n/2),
 * so DC sits in the middle. Back-ends that deliver FFT order (ESP32, Nexmon) are
 * re-ordered before construction. [validMask] is false for DC, guard-band and
 * hardware-flagged-garbage sub-carriers, which are excluded from plots and detection.
 * [raw] is the untouched payload from the device, kept for lossless export.
 */
class CsiFrame(
    val source: CsiSource,
    val receivedAtMs: Long,
    val seq: Long,
    val deviceTimestampUs: Long,
    val sourceMac: String,
    val rssi: Int,
    val noiseFloor: Int,
    val channel: Int,
    val bandwidthMhz: Int,
    val sigMode: Int,
    val mcs: Int,
    val re: FloatArray,
    val im: FloatArray,
    val validMask: BooleanArray,
    val raw: ByteArray? = null,
) {
    val subcarrierCount: Int get() = re.size
    val amplitude: FloatArray by lazy { CsiMath.amplitude(re, im) }
    val phase: FloatArray by lazy { CsiMath.phase(re, im) }
    val validCount: Int by lazy { validMask.count { it } }
}
