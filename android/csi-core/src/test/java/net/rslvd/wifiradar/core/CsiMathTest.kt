package net.rslvd.wifiradar.core

import net.rslvd.wifiradar.core.dsp.CsiMath
import net.rslvd.wifiradar.core.dsp.MotionDetector
import net.rslvd.wifiradar.core.model.CsiFrame
import net.rslvd.wifiradar.core.model.CsiSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class CsiMathTest {

    @Test fun fftShiftMovesNegativeHalfFirst() {
        val x = FloatArray(8) { it.toFloat() }
        assertArrayEquals(floatArrayOf(4f, 5f, 6f, 7f, 0f, 1f, 2f, 3f), CsiMath.fftShift(x), 0f)
    }

    @Test fun usedMaskFor20MHzLltfHas52Carriers() {
        val m = CsiMath.usedSubcarrierMask(64, 26)
        assertEquals(52, m.count { it })
        assertFalse(m[32])          // DC
        assertTrue(m[33]); assertTrue(m[32 + 26]); assertFalse(m[32 + 27])
        assertTrue(m[32 - 26]); assertFalse(m[32 - 27])
    }

    @Test fun sanitizePhaseRemovesLinearTrend() {
        val n = 64
        val valid = CsiMath.usedSubcarrierMask(n, 26)
        val slope = 0.37f; val offset = 1.1f
        val phase = FloatArray(n) { i ->
            val k = i - n / 2
            atan2(sin(slope * k + offset), cos(slope * k + offset))   // wrapped into (-pi, pi]
        }
        val out = CsiMath.sanitizePhase(phase, valid)
        for (i in 0 until n) if (valid[i]) assertTrue("residual at $i = ${out[i]}", abs(out[i]) < 1e-3f)
    }

    @Test fun normalizeGivesUnitMeanOverValidCarriers() {
        val valid = CsiMath.usedSubcarrierMask(64, 26)
        val amp = FloatArray(64) { 10f + it }
        val norm = CsiMath.normalize(amp, valid)
        val mean = norm.filterIndexed { i, _ -> valid[i] }.average()
        assertEquals(1.0, mean, 1e-5)
        assertEquals(0f, norm[32], 0f)
    }

    private fun frame(t: Long, amp: FloatArray, valid: BooleanArray) = CsiFrame(
        CsiSource.DEMO, t, t, 0, "00:00:00:00:00:00", -50, -90, 6, 20, 1, 0,
        re = amp, im = FloatArray(amp.size), validMask = valid,
    )

    @Test fun motionDetectorStillVsMoving() {
        val valid = CsiMath.usedSubcarrierMask(64, 26)
        val det = MotionDetector(windowMs = 1000, threshold = 0.06f)
        val base = FloatArray(64) { 20f + 5f * sin(it / 5f) }
        var last = MotionDetector.Result(0f, false, false, 0)
        var t = 0L
        repeat(60) { last = det.push(frame(t, base, valid)); t += 20 }
        assertFalse("static channel must not trigger", last.moving)
        assertTrue(last.score < 0.2f)
        val rnd = Random(1)
        repeat(60) {
            val moving = FloatArray(64) { i -> base[i] * (1f + 0.4f * rnd.nextFloat()) }
            last = det.push(frame(t, moving, valid)); t += 20
        }
        assertTrue("fluctuating channel must trigger (score ${last.score})", last.moving)
        assertTrue(last.presence)
    }
}
