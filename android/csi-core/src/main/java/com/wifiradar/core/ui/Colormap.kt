package net.rslvd.wifiradar.core.ui

import android.graphics.Color

/** Viridis-like perceptually uniform colour map, 256-entry LUT. */
object Colormap {
    private val anchors = arrayOf(
        intArrayOf(68, 1, 84), intArrayOf(71, 44, 122), intArrayOf(59, 81, 139),
        intArrayOf(44, 113, 142), intArrayOf(33, 144, 141), intArrayOf(39, 173, 129),
        intArrayOf(92, 200, 99), intArrayOf(170, 220, 50), intArrayOf(253, 231, 37),
    )
    private val lut = IntArray(256) { i ->
        val pos = i / 255f * (anchors.size - 1)
        val a = pos.toInt().coerceAtMost(anchors.size - 2)
        val f = pos - a
        val c0 = anchors[a]; val c1 = anchors[a + 1]
        Color.rgb(
            (c0[0] + (c1[0] - c0[0]) * f).toInt(),
            (c0[1] + (c1[1] - c0[1]) * f).toInt(),
            (c0[2] + (c1[2] - c0[2]) * f).toInt(),
        )
    }

    /** t in 0..1 */
    fun color(t: Float): Int = lut[(t.coerceIn(0f, 1f) * 255f).toInt()]
}
