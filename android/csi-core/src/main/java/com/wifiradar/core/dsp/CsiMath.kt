package net.rslvd.wifiradar.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object CsiMath {

    fun amplitude(re: FloatArray, im: FloatArray): FloatArray =
        FloatArray(re.size) { sqrt(re[it] * re[it] + im[it] * im[it]) }

    fun phase(re: FloatArray, im: FloatArray): FloatArray =
        FloatArray(re.size) { atan2(im[it], re[it]) }

    fun toDb(amp: FloatArray): FloatArray =
        FloatArray(amp.size) { 20f * log10(max(amp[it], 1e-3f)) }

    /** FFT order (0..N/2-1, then -N/2..-1) -> natural order (-N/2..N/2-1). */
    fun fftShift(x: FloatArray): FloatArray {
        val n = x.size
        val half = n / 2
        return FloatArray(n) { i -> x[(i + half) % n] }
    }

    /** Natural-order mask that is true for sub-carriers with minAbs <= |k| <= halfWidth. */
    fun usedSubcarrierMask(n: Int, halfWidth: Int, minAbs: Int = 1): BooleanArray =
        BooleanArray(n) { i ->
            val k = abs(i - n / 2)
            k in minAbs..halfWidth
        }

    /** Divides by the mean amplitude over valid sub-carriers; removes the AGC/RSSI dependence. */
    fun normalize(amp: FloatArray, valid: BooleanArray): FloatArray {
        var sum = 0f
        var count = 0
        for (i in amp.indices) if (valid[i]) { sum += amp[i]; count++ }
        val mean = if (count > 0) sum / count else 1f
        val inv = if (mean > 1e-6f) 1f / mean else 0f
        return FloatArray(amp.size) { if (valid[it]) amp[it] * inv else 0f }
    }

    /**
     * Phase sanitisation: unwrap along the valid sub-carriers, then subtract the
     * least-squares line (slope = sampling-time offset, intercept = carrier/phase
     * offset). What remains is the multipath-induced phase, the only part that
     * carries motion information. Invalid sub-carriers are set to 0.
     */
    fun sanitizePhase(phase: FloatArray, valid: BooleanArray): FloatArray {
        val n = phase.size
        val out = FloatArray(n)
        val idx = (0 until n).filter { valid[it] }
        if (idx.size < 3) return out

        val unwrapped = FloatArray(idx.size)
        unwrapped[0] = phase[idx[0]]
        for (j in 1 until idx.size) {
            var d = (phase[idx[j]] - phase[idx[j - 1]]).toDouble()
            d -= 2.0 * PI * (d / (2.0 * PI)).roundToInt()
            unwrapped[j] = (unwrapped[j - 1] + d).toFloat()
        }

        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (j in idx.indices) {
            val x = (idx[j] - n / 2).toDouble()
            sx += x; sy += unwrapped[j]; sxx += x * x; sxy += x * unwrapped[j]
        }
        val m = idx.size.toDouble()
        val denom = m * sxx - sx * sx
        val slope = if (abs(denom) > 1e-9) (m * sxy - sx * sy) / denom else 0.0
        val offset = (sy - slope * sx) / m
        for (j in idx.indices) {
            val x = (idx[j] - n / 2).toDouble()
            out[idx[j]] = (unwrapped[j] - slope * x - offset).toFloat()
        }
        return out
    }
}
