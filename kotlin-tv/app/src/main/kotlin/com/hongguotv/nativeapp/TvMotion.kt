// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

/**
 * Apple TV–style focus lift: the focused card or pill grows and rises; the previous one settles.
 * One global listener per window, so screens need no per-view focus code. Only scale and
 * translationZ are animated (both run on the render thread), which old TV GPUs handle well.
 */
internal object TvMotion {
    private const val DURATION = 150L
    private val easing = DecelerateInterpolator()

    fun install(root: View) {
        root.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            old?.let { settle(it) }
            new?.let { lift(it) }
        }
    }

    /**
     * Only poster-sized cards grow. Pills turn white and rows light up instead: they sit at the
     * edges of many containers (hero actions, settings, filters), where any growth gets clipped.
     * Poster rows reserve room for the lift; see HomeScreen and the catalog grid.
     */
    private fun lifts(view: View): Boolean {
        if (view.background !is TvCardDrawable) return false
        val width = view.width
        return width > 0 && width <= view.resources.displayMetrics.widthPixels * .3f
    }

    private fun scaleFor(view: View) = 1.07f

    private fun lift(view: View) {
        // Focus listeners may swap the background on this same focus change; check after them.
        view.post {
            if (!view.isFocused || !lifts(view)) return@post
            unclip(view)
            val scale = scaleFor(view)
            view.animate().cancel()
            view.animate().scaleX(scale).scaleY(scale).translationZ(view.resources.displayMetrics.density * 8)
                .setDuration(DURATION).setInterpolator(easing).start()
        }
    }

    private fun settle(view: View) {
        if (view.scaleX == 1f && view.translationZ == 0f) return
        view.animate().cancel()
        view.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(DURATION).setInterpolator(easing).start()
    }

    /**
     * A grown card must not be cut off by its row: lift the clip on its row and the row's
     * container only. Going higher reaches the page body, and pages that scroll their own header
     * out of view would then paint it over the top navigation. Stop at scrolling containers too;
     * scrolling rows give their content enough padding for the lift instead.
     */
    private fun unclip(view: View) {
        var parent = view.parent as? ViewGroup
        repeat(2) {
            val current = parent ?: return
            if (current is android.widget.ScrollView || current is android.widget.HorizontalScrollView ||
                current is android.widget.AbsListView) return
            current.clipChildren = false
            current.clipToPadding = false
            parent = current.parent as? ViewGroup
        }
    }
}
