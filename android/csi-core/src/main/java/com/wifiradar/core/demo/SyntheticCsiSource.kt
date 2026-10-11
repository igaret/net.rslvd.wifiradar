package net.rslvd.wifiradar.core.demo

import net.rslvd.wifiradar.core.dsp.CsiMath
import net.rslvd.wifiradar.core.model.CsiFrame
import net.rslvd.wifiradar.core.model.CsiSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Three-path channel model with a "person" whose reflection path fades in and out
 * every few seconds. Lets you exercise the UI, recorder and detector without hardware.
 */
class SyntheticCsiSource(private val rateHz: Int = 50, private val n: Int = 64) {

    fun frames(): Flow<CsiFrame> = flow {
        var seq = 0L
        val rnd = Random(42)
        val start = System.currentTimeMillis()
        val valid = CsiMath.usedSubcarrierMask(n, 26)
        while (true) {
            val now = System.currentTimeMillis()
            val t = (now - start) / 1000.0
            val moving = (t % 10.0) < 5.0                  // 5 s of motion, 5 s still
            val motionGain = if (moving) 0.35 * (1 + sin(2 * PI * 0.8 * t)) else 0.0
            val re = FloatArray(n); val im = FloatArray(n)
            for (i in 0 until n) {
                val k = (i - n / 2).toDouble()
                if (!valid[i]) continue
                var hr = 0.0; var hi = 0.0
                // static paths: direct + wall reflection
                for ((a, tau) in listOf(1.0 to 0.0, 0.45 to 1.7)) {
                    hr += a * cos(-2 * PI * k * tau / n); hi += a * sin(-2 * PI * k * tau / n)
                }
                // moving reflector with time-varying delay
                val tau = 3.2 + 0.6 * sin(2 * PI * 0.3 * t)
                hr += motionGain * cos(-2 * PI * k * tau / n); hi += motionGain * sin(-2 * PI * k * tau / n)
                re[i] = (hr * 40 + rnd.nextDouble(-1.5, 1.5)).toFloat()
                im[i] = (hi * 40 + rnd.nextDouble(-1.5, 1.5)).toFloat()
            }
            emit(
                CsiFrame(
                    source = CsiSource.DEMO, receivedAtMs = now, seq = seq++,
                    deviceTimestampUs = (now - start) * 1000, sourceMac = "de:mo:de:mo:de:mo",
                    rssi = -48 - (motionGain * 6).toInt(), noiseFloor = -94, channel = 6, bandwidthMhz = 20,
                    sigMode = 1, mcs = 0, re = re, im = im, validMask = valid,
                )
            )
            delay((1000L / rateHz).coerceAtLeast(1L))
        }
    }
}
