package net.rslvd.wifiradar.core.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import net.rslvd.wifiradar.core.dsp.CsiMath
import net.rslvd.wifiradar.core.model.CsiFrame
import kotlin.math.max
import kotlin.math.min

/**
 * Live per-sub-carrier plot of the newest frame, with the previous frames drawn
 * as fading "ghosts" so motion shows up as a smeared band.
 */
class CsiLineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    enum class Mode { AMPLITUDE_DB, AMPLITUDE_LINEAR, PHASE }

    var mode: Mode = Mode.AMPLITUDE_DB
        set(value) { field = value; history.clear(); yMin = 0f; yMax = 1f; invalidate() }

    private val history = ArrayDeque<FloatArray>()
    private val historySize = 24
    private var valid: BooleanArray = BooleanArray(0)
    private var yMin = 0f
    private var yMax = 1f
    private var lastInvalidate = 0L
    private var subtitle = ""

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 229, 255); strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 229, 255); strokeWidth = 1.5f; style = Paint.Style.STROKE
    }
    private val gridPaint = Paint().apply { color = Color.argb(60, 255, 255, 255); strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 28f }
    private val bgPaint = Paint().apply { color = Color.rgb(18, 18, 24) }
    private val path = Path()

    fun clear() {
        history.clear()
        subtitle = ""
        invalidate()
    }

    fun update(frame: CsiFrame) {
        val curve = when (mode) {
            Mode.AMPLITUDE_DB -> CsiMath.toDb(frame.amplitude)
            Mode.AMPLITUDE_LINEAR -> frame.amplitude
            Mode.PHASE -> CsiMath.sanitizePhase(frame.phase, frame.validMask)
        }
        valid = frame.validMask
        history.addLast(curve)
        while (history.size > historySize) history.removeFirst()

        // Auto-scale on the newest curve with a slow release so the plot doesn't jitter.
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (i in curve.indices) if (valid[i]) { lo = min(lo, curve[i]); hi = max(hi, curve[i]) }
        if (lo < hi) {
            val pad = (hi - lo) * 0.1f + 1e-3f
            yMin = if (lo - pad < yMin) lo - pad else yMin + (lo - pad - yMin) * 0.05f
            yMax = if (hi + pad > yMax) hi + pad else yMax + (hi + pad - yMax) * 0.05f
        }
        subtitle = "n=${frame.subcarrierCount}  rssi=${frame.rssi} dBm  ch=${frame.channel}  seq=${frame.seq}"

        val now = SystemClock.uptimeMillis()
        if (now - lastInvalidate >= 16) {           // cap redraws at ~60 fps
            lastInvalidate = now
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        for (i in 1 until 4) {
            canvas.drawLine(0f, h * i / 4f, w, h * i / 4f, gridPaint)
            canvas.drawLine(w * i / 4f, 0f, w * i / 4f, h, gridPaint)
        }
        val newest = history.lastOrNull() ?: run {
            canvas.drawText("waiting for CSI…", 20f, h / 2f, textPaint)
            return
        }
        val n = newest.size
        if (n < 2) return
        val range = (yMax - yMin).takeIf { it > 1e-6f } ?: 1f
        fun x(i: Int) = i / (n - 1f) * w
        fun y(v: Float) = h - (v - yMin) / range * h

        var k = 0
        for (curve in history) {
            if (curve.size != n) { k++; continue }
            val isNewest = curve === newest
            if (!isNewest) ghostPaint.alpha = (10 + 90 * k / history.size).coerceIn(0, 255)
            path.rewind()
            var started = false
            for (i in 0 until n) {
                if (!valid[i]) { started = false; continue }
                if (!started) { path.moveTo(x(i), y(curve[i])); started = true } else path.lineTo(x(i), y(curve[i]))
            }
            canvas.drawPath(path, if (isNewest) linePaint else ghostPaint)
            k++
        }
        val unit = when (mode) { Mode.AMPLITUDE_DB -> "dB"; Mode.AMPLITUDE_LINEAR -> ""; Mode.PHASE -> "rad" }
        canvas.drawText(String.format("%.1f %s", yMax, unit), 8f, 30f, textPaint)
        canvas.drawText(String.format("%.1f %s", yMin, unit), 8f, h - 10f, textPaint)
        canvas.drawText(subtitle, w - textPaint.measureText(subtitle) - 8f, 30f, textPaint)
        canvas.drawText("sub-carrier −${n / 2} … +${n / 2 - 1}", w / 2f - 120f, h - 10f, textPaint)
    }
}
