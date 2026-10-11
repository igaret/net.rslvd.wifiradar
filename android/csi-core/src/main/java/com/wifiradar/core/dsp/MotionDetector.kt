package net.rslvd.wifiradar.core.dsp

import net.rslvd.wifiradar.core.model.CsiFrame
import kotlin.math.sqrt

/**
 * Baseline motion detector: the temporal standard deviation of the per-frame
 * normalised amplitude, averaged over sub-carriers, within a sliding window.
 * A person moving in the Fresnel zones between TX and RX changes the multipath
 * sum per sub-carrier; a static room gives a flat, low-variance profile.
 *
 * score = std / threshold, so 1.0 means "exactly at the threshold".
 */
class MotionDetector(
    var windowMs: Long = 1000L,
    var threshold: Float = 0.06f,
    var presenceHoldMs: Long = 15_000L,
) {
    data class Result(val score: Float, val moving: Boolean, val presence: Boolean, val windowFrames: Int)

    private class Entry(val timeMs: Long, val amp: FloatArray)

    private val window = ArrayDeque<Entry>()
    private var lastMotionMs = Long.MIN_VALUE
    private var lastSize = -1

    fun reset() {
        window.clear()
        lastMotionMs = Long.MIN_VALUE
    }

    fun push(frame: CsiFrame): Result {
        if (frame.subcarrierCount != lastSize) {       // layout changed (e.g. HT-LTF enabled)
            window.clear()
            lastSize = frame.subcarrierCount
        }
        val amp = CsiMath.normalize(frame.amplitude, frame.validMask)
        window.addLast(Entry(frame.receivedAtMs, amp))
        while (window.isNotEmpty() && frame.receivedAtMs - window.first().timeMs > windowMs) {
            window.removeFirst()
        }
        if (window.size < 5) {
            return Result(0f, false, frame.receivedAtMs - lastMotionMs < presenceHoldMs, window.size)
        }

        val n = amp.size
        val size = window.size
        var accStd = 0f
        var used = 0
        for (k in 0 until n) {
            if (!frame.validMask[k]) continue
            var mean = 0f
            for (e in window) mean += e.amp[k]
            mean /= size
            var v = 0f
            for (e in window) { val d = e.amp[k] - mean; v += d * d }
            accStd += sqrt(v / size)
            used++
        }
        val std = if (used > 0) accStd / used else 0f
        val score = std / threshold
        val moving = score >= 1f
        if (moving) lastMotionMs = frame.receivedAtMs
        val presence = frame.receivedAtMs - lastMotionMs < presenceHoldMs
        return Result(score, moving, presence, size)
    }
}
