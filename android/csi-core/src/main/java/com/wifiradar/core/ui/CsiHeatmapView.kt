package net.rslvd.wifiradar.core.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import net.rslvd.wifiradar.core.model.CsiFrame
import kotlin.math.log10

/**
 * Scrolling waterfall: x = time (newest at the right), y = sub-carrier, colour =
 * amplitude in dB relative to the frame mean. Per-frame normalisation removes
 * the AGC gain so only the channel *shape* and its change over time remain.
 */
class CsiHeatmapView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var columns = 400
    var dbRange = 12f   // colour scale spans mean ± dbRange

    private var bitmap: Bitmap? = null
    private var rows = 0
    private var head = 0
    private var filled = 0
    private var column = IntArray(0)
    private var lastInvalidate = 0L
    private val src = Rect()
    private val dst = RectF()
    private val paint = Paint().apply { isFilterBitmap = false }
    private val bgPaint = Paint().apply { color = Color.rgb(18, 18, 24) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = 26f }

    fun clear() {
        bitmap?.eraseColor(Color.BLACK)
        head = 0; filled = 0
        invalidate()
    }

    fun update(frame: CsiFrame) {
        val amp = frame.amplitude
        val valid = frame.validMask
        var count = 0
        for (v in valid) if (v) count++
        if (count == 0) return
        // Keyed on the sub-carrier count, not the valid count: first_word_invalid toggles
        // per packet and must not wipe the history.
        if (frame.subcarrierCount != rows || bitmap == null) {
            rows = frame.subcarrierCount
            bitmap = Bitmap.createBitmap(columns, rows, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLACK) }
            column = IntArray(rows)
            head = 0; filled = 0
        }
        var sum = 0f
        for (i in amp.indices) if (valid[i]) sum += amp[i]
        val mean = (sum / count).coerceAtLeast(1e-6f)

        // Row 0 (top) = highest sub-carrier index, so the picture reads like a spectrum.
        var r = rows - 1
        for (i in amp.indices) {
            column[r--] = if (valid[i]) {
                val db = 20f * log10((amp[i] / mean).coerceAtLeast(1e-4f))
                Colormap.color((db + dbRange) / (2f * dbRange))
            } else Color.BLACK
        }
        bitmap!!.setPixels(column, 0, 1, head, 0, 1, rows)
        head = (head + 1) % columns
        if (filled < columns) filled++

        val now = SystemClock.uptimeMillis()
        if (now - lastInvalidate >= 33) {           // ~30 fps is plenty for a waterfall
            lastInvalidate = now
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        val bmp = bitmap ?: run {
            canvas.drawText("waiting for CSI…", 20f, h / 2f, textPaint)
            return
        }
        val colW = w / columns
        // Oldest data starts at `head` (ring buffer) and must appear on the left.
        val older = columns - head
        if (older > 0) {
            src.set(head, 0, columns, rows)
            dst.set(0f, 0f, older * colW, h)
            canvas.drawBitmap(bmp, src, dst, paint)
        }
        if (head > 0) {
            src.set(0, 0, head, rows)
            dst.set(older * colW, 0f, w, h)
            canvas.drawBitmap(bmp, src, dst, paint)
        }
        canvas.drawText("+", 6f, 26f, textPaint)
        canvas.drawText("−", 6f, h - 8f, textPaint)
        canvas.drawText("time →  (${filled} frames)", w - 260f, h - 8f, textPaint)
    }
}
