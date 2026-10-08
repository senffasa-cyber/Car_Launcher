package com.arena.carlauncher.home

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arena.carlauncher.R
import com.arena.carlauncher.data.AppRepository
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.ui.Views

/**
 * The full-screen app grid, drawn over the home screen instead of being a separate activity — that
 * keeps the wallpaper, the clock and the map visible behind it, and avoids an Activity transition in
 * the one place a driver taps most.
 *
 * The list is whatever [AppRepository] already resolved (launchable, user-visible, collator-sorted for
 * Persian); the search box filters that in memory, so typing costs nothing but a `notifyDataSetChanged`
 * over ~60 entries.
 */
class AllAppsView(context: Context) : LinearLayout(context) {

    interface Callback {
        fun onLaunch(entry: AppRepository.AppEntry)
        fun onLongPress(entry: AppRepository.AppEntry)
        fun onClose()
    }

    var callback: Callback? = null

    private val search = EditText(context).apply {
        hint = context.getString(R.string.allapps_search)
        setSingleLine()
        textSize = 15f
        setTextColor(Palette.colors.onSurface)
        setHintTextColor(Palette.colors.onSurfaceMuted)
        background = Palette.chip(context, false)
        val p = Views.dp(context, 12f)
        setPadding(p, p, p, p)
    }
    private val title = Views.text(context, "", 13f, Palette.colors.accent, bold = true)
    private val grid = RecyclerView(context).apply {
        hasFixedSize = true
        setItemViewCacheSize(24)
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
    }
    private val adapter = Adapter()
    private var source: List<AppRepository.AppEntry> = emptyList()
    private var shown: List<AppRepository.AppEntry> = emptyList()

    val isEmpty: Boolean get() = visibility != View.VISIBLE

    init {
        orientation = VERTICAL
        val pad = Views.dp(context, 14f)
        setPadding(pad, pad, pad, 0)
        isClickable = true
        background = Palette.card(context, radiusDp = 24f, alphaPercent = if (Palette.night) 94 else 88, clickable = false)

        val head = Views.row(context, paddingDp = 0)
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(
            Views.iconButton(context, R.drawable.ic_close, 34, 18, onClick = { callback?.onClose() }),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Views.dp(context, 8f) })
        addView(head, matchWrap())

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                applyQuery(s?.toString().orEmpty())
            }
        })
        addView(search, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = Views.dp(context, 8f)
            bottomMargin = Views.dp(context, 8f)
        })
        grid.layoutManager = GridLayoutManager(context, columns())
        grid.adapter = adapter
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun matchWrap() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)

    private fun columns(): Int {
        val p = LauncherPrefs.get(context)
        if (p.appGridColumns > 0) return p.appGridColumns
        // 1208 px wide at ~160 dpi fits 7; anything smaller gets fewer.
        val dpWidth = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return when {
            dpWidth > 900f -> 8
            dpWidth > 620f -> 6
            else -> 4
        }
    }

    /** @param entries null = the whole app list; pass a list for "recents" or "hidden apps" modes. */
    @SuppressLint("NotifyDataSetChanged")
    fun open(entries: List<AppRepository.AppEntry>? = null, heading: String = "") {
        source = entries ?: AppRepository.all()
        shown = source
        title.text = heading
        title.visibility = if (heading.isBlank()) View.GONE else View.VISIBLE
        search.setText("")
        search.visibility = if (entries == null) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        (grid.layoutManager as? GridLayoutManager)?.spanCount = columns()
        visibility = View.VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(140L).start()
        if (entries == null) grid.scrollToPosition(0)
    }

    fun close() {
        if (visibility != View.VISIBLE) return
        animate().alpha(0f).setDuration(110L).withEndAction {
            visibility = View.GONE
            alpha = 1f
        }.start()
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        try {
            imm?.hideSoftInputFromWindow(search.windowToken, 0)
        } catch (_: Throwable) {
        }
    }

    fun isOpen() = visibility == View.VISIBLE

    private fun applyQuery(q: String) {
        val query = q.trim()
        val lower = query.lowercase(java.util.Locale.ROOT)
        shown = if (query.isEmpty()) source else source.filter { e ->
            e.label.contains(query, ignoreCase = true) ||
                e.packageName.contains(lower, ignoreCase = false) ||
                e.sortKey.contains(lower)
        }
        adapter.notifyDataSetChanged()
        grid.scrollToPosition(0)
    }

    /** Palette change → rebuild colours without losing the scroll position. */
    fun repaint() {
        background = Palette.card(context, radiusDp = 24f, alphaPercent = if (Palette.night) 94 else 88, clickable = false)
        search.setTextColor(Palette.colors.onSurface)
        search.setHintTextColor(Palette.colors.onSurfaceMuted)
        title.setTextColor(Palette.colors.accent)
        adapter.notifyDataSetChanged()
    }

    fun focusSearch() {
        search.requestFocus()
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        try {
            imm?.showSoftInput(search, 1)
        } catch (_: Throwable) {
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Cell>() {
        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = shown[position].token.let { it.hashCode().toLong() xor (it.length * 31L) }

        override fun getItemCount(): Int = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Cell {
            val cell = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(Views.dp(context, 4f), Views.dp(context, 6f), Views.dp(context, 4f), Views.dp(context, 6f))
                background = Palette.chip(context, false)
                isClickable = true
                isFocusable = true
            }
            val size = Views.dp(context, 40f)
            val icon = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            cell.addView(icon, LinearLayout.LayoutParams(size, size))
            val label = Views.text(context, "", 10.5f, Palette.colors.onSurface).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                includeFontPadding = false
                setLineSpacing(0f, 0.94f)
            }
            cell.addView(
                label,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = Views.dp(context, 4f) }
            )
            return Cell(cell, icon, label)
        }

        override fun onBindViewHolder(holder: Cell, position: Int) {
            val entry = shown[position]
            holder.label.text = entry.label
            val px = Views.dp(context, 36f)
            val bmp = AppRepository.icon(context, entry, px)
            if (bmp != null) {
                holder.icon.setImageBitmap(bmp)
            } else {
                holder.icon.setImageResource(R.drawable.ic_apps)
                holder.icon.setColorFilter(Palette.colors.onSurfaceMuted)
            }
            holder.cell.contentDescription = entry.label
            holder.cell.setOnClickListener { callback?.onLaunch(entry) }
            holder.cell.setOnLongClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                callback?.onLongPress(entry)
                true
            }
            val gap = Views.dp(context, 5f)
            (holder.cell.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.marginStart = gap; it.topMargin = gap
            }
        }
    }

    class Cell(val cell: View, val icon: ImageView, val label: android.widget.TextView)
}
