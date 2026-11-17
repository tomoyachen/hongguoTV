// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

internal interface HandlesHorizontalKeys

/**
 * Apple TV–style top navigation: centred pill tabs, utility icons on the right, content below.
 * Left/right move along the bar, down returns to the page; up from the top of the page reaches
 * the bar. Destination indexes are unchanged from the old side rail, so callers are unaffected.
 */
internal class CatalogShell(context: Context, private val phone: (View) -> Unit = {}, private val navigate: (Int) -> Unit) : LinearLayout(context) {
    companion object {
        /** Left plus right page margin, for screens that size cards from the screen width. */
        const val HORIZONTAL_INSET_DP = 96
    }

    val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val bar = FrameLayout(context)
    private val tabs = listOf(0 to "首页", 8 to "随心看", 1 to "找剧", 2 to "排行榜", 3 to "我的")
    // Phone scan-to-control lives in 设置 → 账户 (手机控制台); the bar keeps search and settings only.
    private val tools = listOf(7 to "搜索", 6 to "设置")
    /** Bar order, left to right: tabs then tools. */
    private val destinations = tabs + tools
    private val buttons = mutableListOf<TextView>()
    private var remembered: View? = null
    private var selectedDestination = 0
    val navigation: List<TextView>
        get() = (0..8).map { index -> buttons[destinations.indexOfFirst { it.first == if (index in 3..5) 3 else index }] }

    val hasNavigationFocus: Boolean get() = bar.hasFocus()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        val compact = resources.configuration.screenHeightDp < 480
        orientation = VERTICAL
        setBackgroundColor(TvStyle.background)
        bar.setPadding(dp(HORIZONTAL_INSET_DP / 2), dp(if (compact) 10 else 18), dp(HORIZONTAL_INSET_DP / 2), dp(if (compact) 6 else 10))

        val tabRow = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setPadding(dp(5), dp(4), dp(5), dp(4))
            background = TvStyle.shape(context, android.graphics.Color.argb(26, 255, 255, 255), radius = 999)
        }
        tabs.forEach { (index, label) ->
            val button = navButton(index, label, compact, icon = false)
            tabRow.addView(button, LayoutParams(-2, dp(if (compact) 32 else 38)).apply { marginEnd = dp(2) })
        }
        bar.addView(tabRow, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        val toolRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        tools.forEach { (index, label) ->
            val button = navButton(index, label, compact, icon = true)
            val size = dp(if (compact) 34 else 40)
            toolRow.addView(button, LayoutParams(size, size).apply { marginStart = dp(8) })
        }
        bar.addView(toolRow, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))

        addView(bar, LayoutParams(-1, -2))
        body.setPadding(dp(HORIZONTAL_INSET_DP / 2), dp(if (compact) 6 else 12), dp(HORIZONTAL_INSET_DP / 2), dp(16))
        // Pages may widen themselves into the side margins (the home screen does, for its focus
        // lift). The body still clips each page to that page's bounds, so a page that scrolls its
        // own header away cannot paint it over the navigation bar.
        body.clipToPadding = false
        addView(body, LayoutParams(-1, 0, 1f))
    }

    private fun navButton(index: Int, label: String, compact: Boolean, icon: Boolean) = TextView(context).apply {
        id = View.generateViewId()
        contentDescription = if (index == 9) "手机扫码控制" else label
        maxLines = 1
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        if (icon) {
            // Icon-only circle; the glyph is drawn as the background's foreground layer.
            setPadding(0, 0, 0, 0)
            val glyph = TvGlyph(index, dp(if (compact) 18 else 20), TvStyle.text)
            setCompoundDrawablesWithIntrinsicBounds(glyph, null, null, null)
            setPadding(dp(if (compact) 8 else 10), 0, 0, 0)
        } else {
            text = label
            textSize = if (compact) 13f else 15f
            setPadding(dp(if (compact) 14 else 18), 0, dp(if (compact) 14 else 18), 0)
        }
        isSelected = index == selectedDestination
        setOnFocusChangeListener { _, _ -> paintNavigation(this) }
        setOnClickListener { if (index == 9) phone(this) else if (index != selectedDestination) navigate(index) else restoreContent() }
        buttons += this
        paintNavigation(this)
    }

    private fun paintNavigation(button: TextView) {
        val focused = button.hasFocus()
        val ink = if (focused) TvStyle.onFocus else if (button.isSelected) TvStyle.text else TvStyle.muted
        button.background = TvStyle.shape(context,
            if (focused) android.graphics.Color.WHITE else if (button.isSelected) TvStyle.pillSelected else android.graphics.Color.TRANSPARENT,
            radius = 999)
        button.setTextColor(ink)
        button.typeface = if (button.isSelected || focused) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        (button.compoundDrawables[0] as? TvGlyph)?.let { it.ink = ink; it.invalidateSelf() }
    }

    fun select(index: Int) {
        selectedDestination = if (index in 3..5) 3 else index
        buttons.forEachIndexed { i, button ->
            button.isSelected = destinations[i].first == selectedDestination
            paintNavigation(button)
        }
    }

    fun clearBody() {
        remembered = null
        body.removeAllViews()
    }

    private fun inContent(view: View?): Boolean {
        var current = view
        while (current != null) {
            if (current === body) return true
            current = current.parent as? View
        }
        return false
    }

    override fun requestChildFocus(child: View, focused: View) {
        if (inContent(focused)) remembered = focused
        super.requestChildFocus(child, focused)
    }

    fun restoreContent(): Boolean {
        val target = remembered?.takeIf { inContent(it) && it.isShown && it.isFocusable }
            ?: body.getFocusables(FOCUS_FORWARD).firstOrNull()
        return target?.requestFocus() == true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val focused = findFocus()
        val index = buttons.indexOf(focused)
        if (index >= 0 && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> buttons[(index - 1).coerceAtLeast(0)].requestFocus()
                KeyEvent.KEYCODE_DPAD_RIGHT -> buttons[(index + 1).coerceAtMost(buttons.lastIndex)].requestFocus()
                KeyEvent.KEYCODE_DPAD_DOWN -> restoreContent()
            }
            return true
        }
        val vertical = event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        val horizontal = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        // Left/right never leave the page for the top bar (it is reached with up only); at a row's
        // edge the focus stays put. Lists and views that handle their own keys are left alone.
        if (event.action == KeyEvent.ACTION_DOWN && horizontal && inContent(focused) &&
            focused !is android.widget.AbsListView && focused !is HandlesHorizontalKeys) {
            val next = focused?.focusSearch(if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) FOCUS_LEFT else FOCUS_RIGHT)
            if (next != null && !inContent(next)) return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && vertical && inContent(focused) &&
            focused !is android.widget.AbsListView) {
            val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) FOCUS_UP else FOCUS_DOWN
            val next = focused?.focusSearch(direction)
            if (next == null || next === focused || !inContent(next)) {
                val contentTarget = android.view.FocusFinder.getInstance()
                    .findNextFocus(body, focused, direction)
                    ?.takeIf { it !== focused && inContent(it) }
                if (contentTarget != null) contentTarget.requestFocus()
                else if (direction == FOCUS_UP) navigation[selectedDestination].requestFocus()
                return true
            }
        }
        val handled = super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && vertical && inContent(focused) && !inContent(findFocus())) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && hasNavigationFocus) return handled
            focused?.requestFocus()
            return true
        }
        return handled
    }
}
