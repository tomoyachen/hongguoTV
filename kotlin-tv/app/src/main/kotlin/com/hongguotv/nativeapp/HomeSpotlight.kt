// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.Series

/**
 * Apple TV–style Top Shelf: the cover, enlarged and darkened, is the backdrop; title and actions
 * sit on the left over a scrim, and the poster itself on the right. No live blur — the enlarged,
 * dimmed cover reads as ambient colour, which old TV GPUs draw for free.
 */
internal class HomeSpotlight(
    context: Context,
    series: Series,
    status: String,
    resume: Boolean,
    cover: (ImageView, String) -> Unit,
    open: (Boolean) -> Unit,
) : FrameLayout(context) {
    val play: TextView
    val detail: TextView
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        val posterHeight = (resources.displayMetrics.heightPixels * .38f).toInt()
        background = TvStyle.shape(context, TvStyle.surface, radius = 20)
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true

        val backdrop = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = .55f
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(backdrop, LayoutParams(-1, -1))
        addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(250, 12, 12, 16), Color.argb(215, 12, 12, 16), Color.argb(90, 12, 12, 16)),
            )
        }, LayoutParams(-1, -1))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(40), dp(28), dp(40), dp(28))
        }
        val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun label(value: String, size: Float, color: Int, lines: Int = 1) = TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        info.addView(label(if (resume) "接着上次的故事" else "今日精选", 14f, Color.argb(190, 255, 255, 255)))
        info.addView(label(series.title, 38f, TvStyle.text, 2).apply {
            setTypeface(null, Typeface.BOLD)
            setLineSpacing(0f, 1.1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        info.addView(label(status.ifBlank { series.tags }, 15f, TvStyle.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val actions = LinearLayout(context)
        fun action(value: String, proceed: () -> Unit) = label(value, 16f, TvStyle.text).apply {
            id = View.generateViewId()
            isClickable = true
            minHeight = dp(44)
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(10), dp(24), dp(10))
            TvStyle.stylePill(this)
            setOnClickListener { proceed() }
        }
        play = action(if (resume) "继续观看" else "开始观看") { open(true) }
        TvTextGlyph.prefix(play, TvTextGlyph.Kind.PLAY)
        detail = action("查看详情") { open(false) }
        play.tag = "home:spotlight:play"
        detail.tag = "home:spotlight:detail"
        actions.addView(play)
        actions.addView(detail, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) })
        play.nextFocusRightId = detail.id
        detail.nextFocusLeftId = play.id
        info.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))

        val poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            background = TvStyle.shape(context, TvStyle.raised, radius = 14)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        row.addView(poster, LinearLayout.LayoutParams((posterHeight * TvStyle.POSTER_ASPECT).toInt(), posterHeight).apply { leftMargin = dp(32) })
        addView(row, LayoutParams(-1, -2))
        cover(poster, series.cover)
        cover(backdrop, series.cover)
    }
}
