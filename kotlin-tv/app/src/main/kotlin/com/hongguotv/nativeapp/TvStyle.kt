// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// TV 界面的公共颜色及背景样式；调整公共外观时优先查看此处。

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.TextView

/**
 * Apple TV–style visual language: a near-black canvas, white as the focus colour, rounded cards
 * and pill buttons. Focus lift and scale live in [TvMotion]; nothing here blurs in real time,
 * which old Android 5/6 TVs cannot afford.
 */
object TvStyle {
    data class Palette(
        val id: String, val label: String, val background: Int, val surface: Int,
        val raised: Int, val accent: Int, val text: Int, val muted: Int, val outline: Int,
    )
    // The Apple TV–style design is built on a dark canvas with white focus, so only the dark
    // palette is offered; light palettes would make the white focus pills invisible. The theme API
    // from upstream stays so its settings and persistence keep working.
    val themes = listOf(
        Palette(
            "dark", "深色",
            Color.rgb(12, 12, 16), Color.rgb(30, 30, 36), Color.rgb(54, 54, 62), Color.rgb(255, 92, 80),
            Color.rgb(245, 245, 247), Color.rgb(150, 150, 160), Color.rgb(60, 60, 68),
        ),
    )
    var current: Palette = themes.first()
        private set
    fun load(context: Context) {
        val id = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", "dark")
        current = themes.firstOrNull { it.id == id } ?: themes.first()
    }
    fun select(context: Context, palette: Palette) {
        current = palette
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("theme", palette.id).apply()
    }
    val background get() = current.background
    val surface get() = current.surface
    val raised get() = current.raised
    val accent get() = current.accent
    val text get() = current.text
    val muted get() = current.muted
    val outline get() = current.outline
    /** Text drawn on a focused (white) pill. */
    val onFocus = Color.rgb(16, 16, 20)
    val pillIdle = Color.argb(40, 255, 255, 255)
    val pillSelected = Color.argb(72, 255, 255, 255)
    const val POSTER_ASPECT = .7f

    private fun Context.px(value: Float) = value * resources.displayMetrics.density

    fun shape(context: Context, color: Int, border: Int = Color.TRANSPARENT, radius: Int = 12) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.px(radius.toFloat())
            setStroke(context.px(2f).toInt(), border)
        }

    /** Poster and content cards. [TvMotion] lifts any view whose background is one of these. */
    fun card(context: Context, focused: Boolean, selected: Boolean = false): Drawable =
        TvCardDrawable().apply {
            setColor(if (focused) raised else if (selected) raised else surface)
            cornerRadius = context.px(12f)
            setStroke(maxOf(1, context.px(2.5f).toInt()), if (focused) text else Color.TRANSPARENT)
        }

    /**
     * Poster cards (grids, rankings, related works): no box at rest — just the poster and its
     * title on the canvas, Apple TV style — and a white frame with a faint wash when focused.
     */
    fun poster(context: Context, focused: Boolean): Drawable =
        TvCardDrawable().apply {
            setColor(if (focused) Color.argb(30, 255, 255, 255) else Color.TRANSPARENT)
            cornerRadius = context.px(14f)
            setStroke(context.px(3f).toInt(), if (focused) Color.WHITE else Color.TRANSPARENT)
        }

    /** Rounds a poster image (or its frame) and clips the artwork to the rounded shape. */
    fun roundPoster(view: android.view.View, radius: Int = 10) {
        view.background = shape(view.context, surface, radius = radius)
        view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true
    }

    /** Pill button background: translucent at rest, solid white when focused. */
    fun pill(context: Context): Drawable {
        fun fill(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.px(999f)
        }
        return TvPillStates().apply {
            addState(intArrayOf(android.R.attr.state_focused), fill(Color.WHITE))
            addState(intArrayOf(android.R.attr.state_pressed), fill(Color.WHITE))
            addState(intArrayOf(android.R.attr.state_selected), fill(pillSelected))
            addState(intArrayOf(), fill(pillIdle))
        }
    }

    fun pillText(selectedColor: Int = text) = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf(android.R.attr.state_selected),
            intArrayOf(),
        ),
        intArrayOf(onFocus, onFocus, selectedColor, text),
    )

    /**
     * Turns a text view into a pill button. Focus colours come from drawable/text state lists, so
     * callers must not repaint the background or text colour in their own focus listeners.
     */
    fun stylePill(view: TextView, selectedColor: Int = text) {
        view.background = pill(view.context)
        view.setTextColor(pillText(selectedColor))
        view.isFocusable = true
    }
}

/** Marker types so [TvMotion] can tell cards and pills from other focusable views. */
internal class TvCardDrawable : GradientDrawable()
internal class TvPillStates : StateListDrawable()
