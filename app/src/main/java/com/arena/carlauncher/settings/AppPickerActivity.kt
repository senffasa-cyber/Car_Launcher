package com.arena.carlauncher.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
import com.arena.carlauncher.theme.DayNightController
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.ui.Views

/**
 * A grid of every launchable app that returns the chosen tokens to its caller.
 *
 * It serves two editors with one implementation — the dock (single tap = toggle, order preserved) and
 * the hidden-apps list (multi-tap) — because both are "pick some apps" and a second screen would only
 * double the number of places the palette has to be reproduced.
 */
class AppPickerActivity : Activity() {

    private lateinit var adapter: Grid
    private val selected = LinkedHashSet<String>()
    private var mode = AppPickerActivity.PICK_DOCK
    private var search: EditText? = null
    private var filter = ""
    private var showingHidden = false

    override fun onCreate(savedInstanceState: Bundle?) {
        DayNightController.install(application)
        super.onCreate(savedInstanceState)
        mode = intent.getIntExtra(EXTRA_MODE, PICK_DOCK)
        showingHidden = mode == PICK_HIDE
        // The editor edits a *set*, so it starts from what is already chosen rather than from scratch.
        val p = com.arena.carlauncher.data.LauncherPrefs.get(this)
        selected.addAll(if (showingHidden) p.hiddenApps else p.dockApps)

        val root = Views.column(this, paddingDp = 12).apply {
            setBackgroundColor(Palette.colors.background)
        }
        val head = Views.row(this, paddingDp = 0).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(
            Views.text(this, getString(if (showingHidden) R.string.pref_hidden_apps else R.string.pref_dock_edit), 17f, Palette.colors.onSurface, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        head.addView(
            Views.text(this, getString(R.string.done), 14f, Palette.colors.onAccent, bold = true).apply {
                setPadding(Views.dp(this@AppPickerActivity, 14f), Views.dp(this@AppPickerActivity, 8f), Views.dp(this@AppPickerActivity, 14f), Views.dp(this@AppPickerActivity, 8f))
                background = Palette.chip(this@AppPickerActivity, true)
                isClickable = true
                setOnClickListener { finishOk() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Views.dp(this@AppPickerActivity, 10f) })
        root.addView(head, matchWrap())

        val edit = EditText(this).apply {
            hint = getString(R.string.allapps_search)
            setSingleLine()
            textSize = 14f
            setTextColor(Palette.colors.onSurface)
            setHintTextColor(Palette.colors.onSurfaceMuted)
            background = Palette.chip(this@AppPickerActivity, false)
            val p = Views.dp(this@AppPickerActivity, 10f)
            setPadding(p, p, p, p)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    filter = s?.toString()?.trim().orEmpty()
                    adapter.submit(query())
                }
            })
        }
        search = edit
        root.addView(edit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = Views.dp(this@AppPickerActivity, 8f)
            bottomMargin = Views.dp(this@AppPickerActivity, 8f)
        })

        val list = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@AppPickerActivity, columns())
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        adapter = Grid()
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        adapter.submit(query())
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun columns(): Int = when {
        resources.displayMetrics.widthPixels / resources.displayMetrics.density > 900f -> 8
        else -> 6
    }

    private fun query(): List<AppRepository.AppEntry> {
        val base = if (showingHidden) AppRepository.allIncludingHidden() else AppRepository.all()
        if (filter.isBlank()) return base
        return base.filter {
            it.label.contains(filter, ignoreCase = true) || it.packageName.contains(filter, true)
        }
    }

    private fun finishOk() {
        val data = Intent()
            .putStringArrayListExtra(EXTRA_RESULT, ArrayList(selected))
            .putExtra(EXTRA_MODE, mode)
        setResult(RESULT_OK, data)
        finish()
    }

    @SuppressLint("NotifyDataSetChanged")
    private inner class Grid : RecyclerView.Adapter<Cell>() {
        private var items: List<AppRepository.AppEntry> = emptyList()

        fun submit(list: List<AppRepository.AppEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Cell {
            val cell = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(Views.dp(parent.context, 6f), Views.dp(parent.context, 8f), Views.dp(parent.context, 6f), Views.dp(parent.context, 8f))
                isClickable = true
                isFocusable = true
            }
            val icon = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val size = Views.dp(parent.context, 34f)
            cell.addView(icon, LinearLayout.LayoutParams(size, size))
            val label = Views.text(parent.context, "", 10f, Palette.colors.onSurface).apply {
                gravity = Gravity.CENTER
                maxLines = 2
            }
            cell.addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(parent.context, 4f) })
            return Cell(cell, icon, label)
        }

        override fun onBindViewHolder(holder: Cell, position: Int) {
            val e = items[position]
            val px = Views.dp(holder.cell.context, 32f)
            val bmp = AppRepository.icon(holder.cell.context, e, px)
            if (bmp != null) holder.icon.setImageBitmap(bmp) else holder.icon.setImageResource(R.drawable.ic_apps)
            holder.label.text = e.label
            val on = selected.contains(e.token)
            holder.cell.background = Palette.chip(holder.cell.context, on)
            holder.cell.setOnClickListener {
                if (!selected.remove(e.token)) selected.add(e.token)
                notifyItemRangeChanged(0, itemCount)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    class Cell(val cell: View, val icon: ImageView, val label: android.widget.TextView) :
        RecyclerView.ViewHolder(cell)

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_RESULT = "result"
        const val PICK_DOCK = 1
        const val PICK_HIDE = 2
    }
}
