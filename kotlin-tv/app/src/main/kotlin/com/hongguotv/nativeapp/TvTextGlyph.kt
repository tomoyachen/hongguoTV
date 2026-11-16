// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.TextView

/**
 * Small symbols drawn as shapes instead of text: Android 6 TV fonts (MiTV4-ANSM0) lack "▶" and
 * "✓" and show a crossed box. Drawn in the owning text view's current colour, so it follows the
 * pill's focus state (white at rest, dark on the white focused pill).
 */
internal class TvTextGlyph(private val owner: TextView, private val kind: Kind, private val size: Int) : Drawable() {
    enum class Kind { PLAY, CHECK }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean = true

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        paint.color = owner.currentTextColor
        path.reset()
        when (kind) {
            Kind.PLAY -> {
                // Rounded triangle: fill plus a round-joined stroke of the same colour.
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = w * .16f
                path.moveTo(b.left + w * .24f, b.top + h * .16f)
                path.lineTo(b.left + w * .84f, b.top + h * .5f)
                path.lineTo(b.left + w * .24f, b.top + h * .84f)
                path.close()
            }
            Kind.CHECK -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w * .14f
                path.moveTo(b.left + w * .16f, b.top + h * .54f)
                path.lineTo(b.left + w * .42f, b.top + h * .78f)
                path.lineTo(b.left + w * .86f, b.top + h * .26f)
            }
        }
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** Puts the glyph before the text, sized to the text. */
        fun prefix(view: TextView, kind: Kind) {
            val size = (view.textSize * .82f).toInt()
            view.setCompoundDrawablesWithIntrinsicBounds(TvTextGlyph(view, kind, size), null, null, null)
            view.compoundDrawablePadding = (view.textSize * .45f).toInt()
        }
    }
}
