package com.arena.carlauncher.home

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.arena.carlauncher.data.Cards
import com.arena.carlauncher.ui.Views
import com.arena.carlauncher.ui.card.BaseCard
import com.arena.carlauncher.ui.card.CardFactory

/**
 * The centre carousel: a ViewPager2 whose pages are full-size cards.
 *
 * Two decisions worth stating, because they are the difference between a launcher that feels smooth on
 * this hardware and one that stutters:
 *
 *  - The ViewHolder holds a *container*, and the card is placed into it on bind. RecyclerView asks for a
 *    holder before it knows the position, so creating the card in `onCreateViewHolder` would need the
 *    id smuggled through `getItemViewType` — fragile for no gain.
 *  - The recycled-view pool is emptied (`maxRecycledViews = 0`) and ids are stable, so a page that is
 *    off screen but still cached keeps its card instance, its subscriptions paused by [BaseCard.unbind]
 *    rather than torn down and rebuilt. Re-inflating a card costs ~30 ms and a few MB of churn per swipe.
 */
class CardPagerAdapter(
    private val context: Context,
    private val full: Boolean
) : RecyclerView.Adapter<CardPagerAdapter.PageHolder>() {

    interface Callback {
        /** A card asked to be brought to the front (its id is a [Cards] constant). */
        fun onRequestCenter(id: String)
        fun onCardSelected(id: String)
        fun openSettings()
    }

    var callback: Callback? = null

    var ids: List<String> = listOf(Cards.CLOCK, Cards.MUSIC)
        set(value) {
            val v = value.ifEmpty { listOf(Cards.CLOCK) }
            if (v == field) return
            val old = field
            field = v
            // A one-element move can be animated; anything else is a full reload.
            if (old.size == v.size && old.zip(v).count { it.first != it.second } == 1) {
                val i = old.zip(v).indexOfFirst { it.first != it.second }
                notifyItemChanged(i)
            } else {
                notifyDataSetChanged()
            }
        }

    init {
        setHasStableIds(true)
    }

    /**
     * Applied by the host: keep every page alive instead of pooling it, so scrolling back is a repaint
     * and not a re-inflate. `setMaxRecycledViews(0, 0)` plus a cache size >= page count does that.
     */
    fun applyNoRecycle(pager: androidx.viewpager2.widget.ViewPager2) {
        try {
            pager.recycledViewPool.setMaxRecycledViews(0, 0)
            pager.offscreenPageLimit = ids.size.coerceAtMost(6)
            pager.isUserInputEnabled = true
        } catch (_: Throwable) {
        }
    }

    private val live = HashMap<String, BaseCard>()

    override fun getItemCount(): Int = ids.size

    override fun getItemId(position: Int): Long = ids[position].let { it.hashCode().toLong() * 31L + it.length }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder =
        PageHolder(FrameLayout(parent.context))

    @SuppressLint("NotifyDataSetChanged")
    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        val id = ids.getOrNull(position) ?: return
        if (holder.boundId == id) return
        val card = CardFactory.create(context, id, full)
        card.onRequestCard = { target -> callback?.onRequestCenter(target) }
        card.onOpenSettings = { callback?.openSettings() }
        holder.attach(card)
        live.put(id, card)
        card.bind()
    }

    override fun onViewAttachedToWindow(holder: PageHolder) {
        super.onViewAttachedToWindow(holder)
        holder.card?.bind()
    }

    override fun onViewDetachedFromWindow(holder: PageHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.card?.unbind()
    }

    override fun onViewRecycled(holder: PageHolder) {
        super.onViewRecycled(holder)
        holder.card?.let { live.remove(it.cardId) }
        holder.detach()
    }

    /** The palette flipped: every live card re-tints itself in place. */
    fun repaint() {
        live.values.forEach { runCatching { it.onPaletteChanged() } }
    }

    fun cardFor(id: String): BaseCard? = live[id]

    fun indexOf(id: String): Int = ids.indexOf(id)

    fun pageId(position: Int): String? = ids.getOrNull(position)

    /** Full rebuild after a layout/preset change, dropping every cached card. */
    @SuppressLint("NotifyDataSetChanged")
    fun reload(newIds: List<String>) {
        live.values.forEach { runCatching { it.unbind() } }
        live.clear()
        ids = newIds
        notifyDataSetChanged()
    }

    class PageHolder(container: FrameLayout) : RecyclerView.ViewHolder(container) {

        var card: BaseCard? = null
            private set
        var boundId: String? = null
            private set

        private val frame: FrameLayout = container

        fun attach(newCard: BaseCard) {
            card?.let { old ->
                old.unbind()
                frame.removeView(old)
            }
            card = newCard
            boundId = newCard.cardId
            frame.addView(
                newCard,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        fun detach() {
            card?.let {
                it.unbind()
                frame.removeView(it)
            }
            card = null
            boundId = null
        }
    }
}

/**
 * A vertical strip of mini cards, used for the left and right columns. Fixed height is per card
 * (weights) so a 2-card column and a 5-card column both fill the screen without scrolling.
 */
class MiniCardStrip(context: Context) : LinearLayout(context) {

    var onRequestCard: ((String) -> Unit)? = null
    var onOpenSettings: (() -> Unit)? = null

    private val built = ArrayList<BaseCard>(6)
    private var ids: List<String> = emptyList()

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setCards(ctx: Context, newIds: List<String>) {
        if (newIds == ids && built.isNotEmpty()) return
        ids = newIds
        rebuild(ctx)
    }

    fun rebuild(ctx: Context) {
        built.forEach { it.unbind() }
        removeAllViews()
        built.clear()
        for ((i, id) in ids.withIndex()) {
            val card = CardFactory.create(ctx, id, false)
            card.onRequestCard = { onRequestCard?.invoke(it) }
            card.onOpenSettings = { onOpenSettings?.invoke() }
            addView(
                card,
                LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    if (i > 0) topMargin = Views.dp(ctx, 8f)
                }
            )
            built.add(card)
        }
    }

    fun bindAll() = built.forEach { it.bind() }
    fun unbindAll() = built.forEach { it.unbind() }
    fun repaint() = built.forEach { runCatching { it.onPaletteChanged() } }
    fun size(): Int = built.size
}
