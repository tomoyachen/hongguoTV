// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Rect
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import com.hongguotv.core.Series
import kotlin.math.roundToInt

/** Fixed text slots keep D-pad movement from resizing the content below the preview. */
class SeriesPreview(context: Context) : LinearLayout(context) {
    private fun label(size: Float, color: Int) =
        TextView(context).apply {
            textSize = size
            setTextColor(color)
            includeFontPadding = false
        }

    private val title =
        label(20f, TvStyle.text).apply {
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    private val meta =
        label(12f, TvStyle.muted).apply {
            minLines = 1
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    init {
        orientation = VERTICAL
        val dp = resources.displayMetrics.density
        setPadding(0, (4 * dp).toInt(), 0, (6 * dp).toInt())
        addView(title)
        addView(meta, LayoutParams(-1, -2).apply { topMargin = (3 * dp).toInt() })
        show(null, "")
    }

    fun show(series: Series?, status: String) {
        val nextTitle = series?.title.orEmpty()
        val nextMeta =
            if (series == null) ""
            else
                listOf(status, series.badge, series.tags)
                    .flatMap { it.split('·') }
                    .map(String::trim)
                    .filter { it.isNotBlank() && !it.all(Char::isDigit) }
                    .distinct()
                    .joinToString("  /  ")
        if (title.text.toString() != nextTitle) title.text = nextTitle
        if (meta.text.toString() != nextMeta) meta.text = nextMeta
    }
}

/** Bounded horizontal shelves with stable D-pad selection and per-row scroll positions. */
class HomeScreen(
    context: Context,
    private val top: View,
    private val loadCover: (ImageView, String) -> Unit,
    private val selected: (Series, String) -> Unit,
    private val open: (Series, Boolean) -> Unit,
    private val actions: (Series, View) -> Unit,
    private val remember: (String) -> Unit,
) : LinearLayout(context) {
    data class Entry(
        val series: Series,
        val label: String,
        val resume: Boolean = false,
        val progress: Int? = null,
    )

    data class Shelf(
        val key: String,
        val title: String,
        val entries: List<Entry>,
        val more: (() -> Unit)? = null,
    )

    data class Position(
        val focus: String,
        val shelf: String?,
        val column: Int,
        val row: Int,
        val vertical: Int,
        val horizontal: Map<String, Int>,
    )

    private data class Card(
        val key: String,
        val view: LinearLayout,
        val image: ImageView?,
        val title: TextView,
        val label: TextView,
        val progress: ProgressBar?,
        var entry: Entry?,
        var pending: Boolean = false,
    )

    private data class Row(
        val key: String,
        val block: LinearLayout,
        val horizontal: HorizontalScrollView,
        val cards: List<Card>,
    )

    private val accent get() = TvStyle.accent

    private fun currentCovers(): Map<String, android.graphics.drawable.Drawable> =
        cards.values.mapNotNull { card ->
            val cover = card.entry?.series?.cover ?: return@mapNotNull null
            card.image?.drawable?.let { cover to it }
        }.toMap()

    // The owner builds a new HomeScreen whenever home data changes; hand the covers over to it.
    override fun onDetachedFromWindow() {
        carriedCovers = currentCovers().entries.take(MAX_CARRIED).associate { it.key to it.value }
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MAX_CARRIED = 40
        var carriedCovers: Map<String, android.graphics.drawable.Drawable> = emptyMap()
        const val POSTERS_PER_ROW = 7
        const val POSTER_GAP_DP = 22
        const val LIFT_ROOM_DP = 16
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun shape(@Suppress("UNUSED_PARAMETER") focus: Boolean): android.graphics.drawable.Drawable =
        TvCardDrawable().apply { setColor(Color.TRANSPARENT) }

    private fun label(value: String, size: Float, color: Int = TvStyle.text) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
        }

    private val scroll =
        object : ScrollView(context) {
            override fun requestChildFocus(child: View, focused: View) {
                super.requestChildFocus(child, focused)
                if (focused.tag?.toString()?.startsWith("home:spotlight:") == true) {
                    // Arrow navigation can calculate its scroll before the new child owns focus.
                    // Run after that native scroll and replace any in-flight shelf animation.
                    post {
                        if (focused.hasFocus()) leading?.let { smoothScrollTo(0, it.top) }
                    }
                }
            }

            override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
                val section = leading?.takeIf { it.hasFocus() }
                // The action buttons sit at the bottom of the banner. Reveal its title and
                // artwork too, including native focus scrolls after layout/restoration.
                val visible = if (section != null)
                    Rect(rect.left, section.top, rect.right, section.bottom) else rect
                return super.computeScrollDeltaToGetChildRectOnScreen(visible)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
    private val contents = LinearLayout(context).apply { orientation = VERTICAL }
    private val rows = mutableListOf<Row>()
    private val cards = linkedMapOf<String, Card>()
    private var shelves = emptyList<Shelf>()
    private var footerMore: (() -> Unit)? = null
    private var moreButton: View? = null
    private var lastFocus = ""
    private var sideEntry: View? = null
    private var leading: HomeSpotlight? = null

    internal fun setLeadingSection(view: HomeSpotlight?) { leading = view }

    fun setSideEntry(view: View) { sideEntry = view }
    private var lastRow: String? = null
    private var restoring = false
    private var revision = 0
    private val notice = label("", 13f).apply { visibility = GONE }

    private val retryButton = label("重试", 15f).apply {
        id = View.generateViewId()
        isClickable = true
        gravity = Gravity.CENTER
        minHeight = dp(42)
        setPadding(dp(24), dp(8), dp(24), dp(8))
        TvStyle.stylePill(this)
        visibility = GONE
    }

    fun showStatus(message: String, retry: (() -> Unit)? = null) {
        notice.text = message
        notice.visibility = if (message.isBlank()) GONE else VISIBLE
        if (message.isNotBlank()) showLoading(false)
        retryButton.visibility = if (retry != null) VISIBLE else GONE
        retryButton.setOnClickListener { retry?.invoke() }
        if (retry != null && cards.isEmpty()) retryButton.requestFocus()
    }

    /** Centred spinner while the page has nothing to show yet; no text. */
    private val spinner = ProgressBar(context).apply {
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        visibility = GONE
    }
    private var loadingRequested = false

    fun showLoading(loading: Boolean) {
        loadingRequested = loading
        spinner.visibility = if (loading && cards.isEmpty()) VISIBLE else GONE
    }

    init {
        orientation = VERTICAL
        scroll.addView(contents)
        // Room for the focus lift of each row's first and last poster. The owner widens this
        // screen by [liftRoom] on both sides (see MainActivity); the scroll pads the content back
        // into place and lets rows draw into that padding.
        scroll.setPadding(liftRoom, 0, liftRoom, 0)
        scroll.clipChildren = false
        contents.clipChildren = false
        val frame = FrameLayout(context).apply { clipChildren = false }
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        frame.addView(spinner, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        addView(frame, LayoutParams(-1, 0, 1f))
    }

    /** Horizontal margin the owner should give back as negative margins around this screen. */
    val liftRoom: Int get() = dp(LIFT_ROOM_DP)

    fun capturePosition(): Position {
        val key =
            findFocus()?.tag?.toString()?.takeIf {
                it.startsWith("home:spotlight:") || it in cards || it == "home:more"
            } ?: lastFocus
        val row = rows.indexOfFirst { item -> item.cards.any { it.key == key } }
        val column = rows.getOrNull(row)?.cards?.indexOfFirst { it.key == key } ?: 0
        return Position(
            key,
            rows.getOrNull(row)?.key,
            column.coerceAtLeast(0),
            row.coerceAtLeast(0),
            scroll.scrollY,
            rows.associate { it.key to it.horizontal.scrollX },
        )
    }

    /** Rebuild only this bounded shelf area, preserving the old card or its nearest neighbour. */
    fun updateShelves(next: List<Shelf>, requestFocus: Boolean = hasFocus()) {
        val position = capturePosition()
        render(next, position.focus, requestFocus, footerMore, position)
    }

    /**
     * Update an existing card without replacing views or moving focus. Structural changes use
     * updateShelves.
     */
    fun updateEntry(shelfKey: String, entry: Entry): Boolean {
        val card = cards["$shelfKey:${entry.series.id}"] ?: return false
        if (card.entry?.series?.cover != entry.series.cover) {
            card.image?.setImageDrawable(null)
        }
        card.entry = entry
        card.title.text = entry.series.title
        card.label.text = entry.label
        card.progress?.apply {
            progress = entry.progress ?: 0
            visibility = if (entry.progress == null) INVISIBLE else VISIBLE
        }
        card.view.contentDescription =
            "${shelves.firstOrNull { it.key==shelfKey }?.title.orEmpty()}，${entry.series.title}，${entry.label}"
        shelves =
            shelves.map { shelf ->
                if (shelf.key == shelfKey)
                    shelf.copy(
                        entries =
                            shelf.entries.map { if (it.series.id == entry.series.id) entry else it }
                    )
                else shelf
            }
        if (lastFocus == card.key) refreshSelection()
        loadNearbySelection()
        return true
    }

    fun refreshSelection() {
        cards[lastFocus]?.entry?.let { selected(it.series, it.label) }
    }

    /** Retry visible cover requests after the owner cancels work for a page or lifecycle change. */
    fun reloadArtwork() {
        loadNearbySelection()
    }

    fun render(
        shelves: List<Shelf>,
        focus: String,
        requestFocus: Boolean,
        more: (() -> Unit)?,
        position: Position? = null,
    ) {
        val previous = position ?: if (rows.isNotEmpty()) capturePosition() else null
        this.shelves = shelves
        footerMore = more
        revision++
        val ticket = revision
        restoring = true
        // Keep the covers already on screen: new cards show them at once, so a rebuild (cache,
        // then fresh data, then extra rows) does not flash blank posters while artwork rebinds.
        val shownCovers = carriedCovers + currentCovers()
        carriedCovers = emptyMap()
        contents.removeAllViews()
        leading?.let { contents.addView(it, LayoutParams(-1, -2).apply { bottomMargin = dp(16) }) }
        rows.clear()
        cards.clear()
        moreButton = null
        lastRow = null
        shelves
            .filter { it.entries.isNotEmpty() || it.more != null }
            .forEach { shelf ->
                val block = LinearLayout(context).apply {
                    orientation = VERTICAL
                    clipChildren = false
                }
                val heading =
                    LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(2), dp(4), dp(2), dp(9))
                    }
                heading.addView(
                    label(shelf.title, 18f, Color.argb(225, 255, 255, 255)).apply { setTypeface(null, Typeface.BOLD) },
                    LayoutParams(0, -2, 1f),
                )
                block.addView(heading)
                val horizontal =
                    HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                    }
                // The scroll view clips the row to the row's own bounds, so the room for TvMotion's
                // focus lift must be padding on the row itself. The negative margins on the scroll
                // view below keep the posters aligned with the heading.
                val line =
                    LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        gravity = Gravity.TOP
                        setPadding(dp(LIFT_ROOM_DP), dp(LIFT_ROOM_DP), dp(LIFT_ROOM_DP), dp(LIFT_ROOM_DP))
                    }
                horizontal.addView(line)
                block.addView(horizontal, LayoutParams(-1, -2).apply {
                    leftMargin = -dp(LIFT_ROOM_DP)
                    rightMargin = -dp(LIFT_ROOM_DP)
                    topMargin = -dp(LIFT_ROOM_DP)
                })
                val rowCards = mutableListOf<Card>()
                fun addCard(entry: Entry?, moreAction: (() -> Unit)? = null) {
                    val width =
                        ((resources.displayMetrics.widthPixels - dp(CatalogShell.HORIZONTAL_INSET_DP) - dp(POSTER_GAP_DP) * (POSTERS_PER_ROW - 1)) / POSTERS_PER_ROW)
                            .toInt()
                            .coerceAtLeast(dp(100))
                    val key =
                        if (entry != null) "${shelf.key}:${entry.series.id}"
                        else "${shelf.key}:more"
                    val view =
                        LinearLayout(context).apply {
                            orientation = VERTICAL
                            id = View.generateViewId()
                            tag = key
                            isFocusable = true
                            isFocusableInTouchMode = true
                            setPadding(0, 0, 0, dp(6))
                            // Transparent card: TvMotion lifts it; the poster frame shows focus.
                            background = shape(false)
                        }
                    val image =
                        if (entry != null)
                            ImageView(context).apply {
                                scaleType = ImageView.ScaleType.CENTER_CROP
                                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                                shownCovers[entry.series.cover]?.let { setImageDrawable(it) }
                            }
                        else null
                    val poster = FrameLayout(context).apply {
                        background = TvStyle.shape(context, TvStyle.surface, radius = 12)
                        outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
                        clipToOutline = true
                    }
                    poster.addView(image ?: label("›", 36f).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
                    val ring = View(context).apply {
                        background = TvStyle.shape(context, Color.TRANSPARENT, Color.WHITE, radius = 12).apply {
                            setStroke(dp(3), Color.WHITE)
                        }
                        visibility = INVISIBLE
                    }
                    poster.addView(ring, FrameLayout.LayoutParams(-1, -1))
                    view.addView(poster, LayoutParams(-1, (width / TvStyle.POSTER_ASPECT).roundToInt()))
                    val title =
                        label(entry?.series?.title ?: "查看全部", 13f, TvStyle.muted).apply {
                            minLines = 2
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(dp(2), dp(8), dp(2), 0)
                        }
                    val badge =
                        label(entry?.label ?: shelf.title, 11f, Color.argb(120, 255, 255, 255)).apply {
                            minLines = 1
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(dp(3), dp(2), 0, 0)
                        }
                    val progress =
                        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
                            .apply {
                                max = 100
                                progress = entry?.progress ?: 0
                                visibility = if (entry?.progress == null) INVISIBLE else VISIBLE
                                progressTintList =
                                    android.content.res.ColorStateList.valueOf(Color.WHITE)
                                progressBackgroundTintList =
                                    android.content.res.ColorStateList.valueOf(Color.argb(60, 255, 255, 255))
                            }
                    view.addView(title)
                    view.addView(badge)
                    view.addView(progress, LayoutParams(-1, dp(3)).apply { topMargin = dp(4) })
                    val card = Card(key, view, image, title, badge, progress, entry)
                    view.contentDescription =
                        entry?.let { "${shelf.title}，${it.series.title}，${it.label}" }
                            ?: "查看全部${shelf.title}"
                    view.setOnClickListener {
                        lastFocus = key
                        remember(key)
                        card.entry?.let { open(it.series, it.resume) } ?: moreAction?.invoke()
                    }
                    if (entry != null) {
                        view.setOnLongClickListener {
                            lastFocus = key
                            remember(key)
                            card.entry?.let { actions(it.series, view) }
                            true
                        }
                        view.setOnKeyListener { _, code, event ->
                            if (code == KeyEvent.KEYCODE_MENU) {
                                if (event.action == KeyEvent.ACTION_UP) {
                                    lastFocus = key
                                    remember(key)
                                    card.entry?.let { actions(it.series, view) }
                                }
                                true
                            } else false
                        }
                    }
                    view.setOnFocusChangeListener { _, focused ->
                        ring.visibility = if (focused) VISIBLE else INVISIBLE
                        title.setTextColor(if (focused) Color.WHITE else TvStyle.muted)
                        if (focused) {
                            lastFocus = key
                            remember(key)
                            card.entry?.let { selected(it.series, it.label) }
                            if (!restoring) {
                                loadNearbySelection()
                                if (lastRow != shelf.key)
                                    scroll.post {
                                        if (revision == ticket && view.hasFocus()) reveal(block)
                                    }
                            }
                            lastRow = shelf.key
                        }
                    }
                    line.addView(view, LayoutParams(width, -1).apply { rightMargin = dp(POSTER_GAP_DP) })
                    rowCards += card
                    cards[key] = card
                }
                shelf.entries.forEach { addCard(it) }
                shelf.more?.let { action -> addCard(null, action) }
                contents.addView(block, LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
                rows += Row(shelf.key, block, horizontal, rowCards)
            }
        showLoading(loadingRequested)
        contents.addView(notice, LayoutParams(-1, -2).apply { topMargin = dp(10) })
        (retryButton.parent as? android.view.ViewGroup)?.removeView(retryButton)
        contents.addView(retryButton, LayoutParams(-2, -2).apply { topMargin = dp(12) })
        val footer = mutableListOf<View>()
        if (more != null) {
            val next =
                label("更多热门  ›", 15f).apply {
                    id = View.generateViewId()
                    tag = "home:more"
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setPadding(dp(24), dp(12), dp(24), dp(12))
                    TvStyle.stylePill(this)
                    setOnFocusChangeListener { _, f ->
                        if (f) lastFocus = tag.toString()
                    }
                    setOnClickListener { more() }
                }
            contents.addView(next, LayoutParams(-2, -2).apply { topMargin = dp(8) })
            footer += next
            moreButton = next
        }
        rows.forEachIndexed { i, row ->
            row.cards.forEachIndexed { j, card ->
                // Top navigation is reached with up, so the row's left edge stops here.
                card.view.nextFocusLeftId = if (j == 0) card.view.id else row.cards[j - 1].view.id
                card.view.nextFocusRightId =
                    row.cards[(j + 1).coerceAtMost(row.cards.lastIndex)].view.id
                card.view.nextFocusUpId =
                    if (i == 0) leading?.play?.id ?: top.id
                    else rows[i - 1].cards[j.coerceAtMost(rows[i - 1].cards.lastIndex)].view.id
                card.view.nextFocusDownId =
                    if (i == rows.lastIndex) moreButton?.id ?: card.view.id
                    else rows[i + 1].cards[j.coerceAtMost(rows[i + 1].cards.lastIndex)].view.id
            }
        }
        footer.forEachIndexed { i, v ->
            v.nextFocusUpId =
                if (i == 0) rows.lastOrNull()?.cards?.firstOrNull()?.view?.id ?: top.id
                else footer[i - 1].id
            v.nextFocusDownId = footer.getOrNull(i + 1)?.id ?: v.id
        }
        leading?.let { section ->
            val below = rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: moreButton?.id ?: top.id
            for (action in listOf(section.play, section.detail)) {
                action.nextFocusUpId = top.id
                action.nextFocusDownId = below
            }
            section.play.nextFocusLeftId = section.play.id
        }
        top.nextFocusDownId = firstId()
        val preferred = focus.ifBlank { previous?.focus.orEmpty() }
        val target = target(preferred, previous)
        if (requestFocus) target.requestFocus()
        post layout@{
            if (revision != ticket) return@layout
            fitArtwork()
            post restore@{
                if (revision != ticket) return@restore
                if (previous != null) restoreOffsets(previous)
                restoring = false
                if (requestFocus && previous == null)
                    rows
                        .firstOrNull { row -> row.cards.any { it.view === target } }
                        ?.let { revealSelection(it) }
                loadNearbySelection()
            }
        }
    }

    fun restorePosition(position: Position, requestFocus: Boolean = true) {
        restoring = true
        if (requestFocus) target(position.focus, position).requestFocus()
        post {
            restoreOffsets(position)
            restoring = false
            loadNearbySelection()
        }
    }

    private fun target(key: String, position: Position?): View {
        leading?.let {
            if (key == "home:spotlight:detail") return it.detail
            if (key == "home:spotlight:play" || key.isBlank() && position == null) return it.play
        }
        if (key == "home:more")
            moreButton?.let {
                return it
            }
        cards[key]?.let {
            return it.view
        }
        if (position != null) {
            val row =
                rows.firstOrNull { it.key == position.shelf }
                    ?: rows.getOrNull(position.row.coerceAtMost(rows.lastIndex))
            row?.cards?.getOrNull(position.column.coerceAtMost(row.cards.lastIndex))?.let {
                return it.view
            }
        }
        return cards.values.firstOrNull()?.view ?: moreButton ?: top
    }

    private fun restoreOffsets(position: Position) {
        rows.forEach { row -> row.horizontal.scrollTo(position.horizontal[row.key] ?: 0, 0) }
        scroll.scrollTo(0, position.vertical)
        leading?.takeIf { it.hasFocus() }?.let { reveal(it, false) }
        // A removed row can leave the nearest surviving card outside the old viewport.
        rows.firstOrNull { row -> row.cards.any { it.view.hasFocus() } }?.let(::revealSelection)
    }

    private fun revealSelection(row: Row) {
        row.cards
            .firstOrNull { it.view.hasFocus() }
            ?.view
            ?.let { card ->
                val horizontal = row.horizontal
                val left = horizontal.scrollX
                val right = left + horizontal.width - horizontal.paddingRight
                val target =
                    when {
                        card.left < left -> card.left
                        card.right > right ->
                            card.right - horizontal.width + horizontal.paddingRight
                        else -> left
                    }
                if (target != left) horizontal.scrollTo(target.coerceAtLeast(0), 0)
            }
        reveal(row.block, false)
    }

    private fun reveal(block: View, smooth: Boolean = true) {
        val target =
            if (smooth) block.top
            else
                when {
                    block.top < scroll.scrollY -> block.top
                    block.bottom > scroll.scrollY + scroll.height ->
                        if (block.height >= scroll.height) block.top
                        else block.bottom - scroll.height
                    else -> scroll.scrollY
                }.coerceAtLeast(0)
        if (target != scroll.scrollY) {
            if (smooth) scroll.smoothScrollTo(0, target) else scroll.scrollTo(0, target)
        }
    }

    private fun loadNearbySelection() {
        if (restoring) return
        val row =
            rows.indexOfFirst { it.cards.any { card -> card.key == lastFocus } }.coerceAtLeast(0)
        val column =
            rows.getOrNull(row)?.cards?.indexOfFirst { it.key == lastFocus }?.coerceAtLeast(0) ?: 0
        rows.getOrNull(row)?.cards?.let { items ->
            for (index in
                (column - 1).coerceAtLeast(0)..(column + 6).coerceAtMost(
                        items.lastIndex
                    )) requestCover(items[index])
        }
        rows.getOrNull(row + 1)?.cards?.take(6)?.forEach(::requestCover)
    }

    private fun requestCover(card: Card) {
        val image = card.image ?: return
        if (card.pending || card.entry?.series?.cover.isNullOrBlank()) return
        val ticket = revision
        fun start() {
            card.pending = false
            if (
                ticket != revision ||
                    !image.isAttachedToWindow ||
                    image.width <= 0 ||
                    image.height <= 0
            )
                return
            loadCover(image, card.entry!!.series.cover)
        }
        if (image.isLaidOut && !image.isLayoutRequested) start()
        else {
            card.pending = true
            image.addOnLayoutChangeListener(
                object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View,
                        left: Int,
                        top: Int,
                        right: Int,
                        bottom: Int,
                        oldLeft: Int,
                        oldTop: Int,
                        oldRight: Int,
                        oldBottom: Int,
                    ) {
                        image.removeOnLayoutChangeListener(this)
                        start()
                    }
                }
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { reloadArtwork() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh)
            post {
                fitArtwork()
                loadNearbySelection()
            }
    }

    private fun fitArtwork() {
        rows.forEach { row ->
            val cardWidth = row.cards.firstOrNull()?.view?.width ?: return@forEach
            if (cardWidth <= 0) return@forEach
            val imageHeight =
                (cardWidth.coerceAtLeast(1) / TvStyle.POSTER_ASPECT).roundToInt()
            row.cards.forEach { card ->
                card.view.getChildAt(0).let { artwork ->
                    if (artwork.layoutParams.height != imageHeight)
                        artwork.layoutParams = artwork.layoutParams.apply { height = imageHeight }
                }
            }
        }
    }

    fun firstId() = leading?.play?.id ?: rows.firstOrNull()?.cards?.firstOrNull()?.view?.id ?: moreButton?.id ?: top.id
}
