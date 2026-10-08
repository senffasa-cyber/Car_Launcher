package com.arena.carlauncher.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.arena.carlauncher.R
import com.arena.carlauncher.data.AppRepository
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.ui.TimeTicker
import com.arena.carlauncher.ui.Views
import com.arena.carlauncher.util.Format
import com.arena.carlauncher.util.Jalali
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The bottom row: pinned apps, a recents shortcut and the all-apps button, with a clock/date block on
 * the far side so the bar is useful even before any card is configured.
 *
 * Icons are pre-rasterised bitmaps rather than Drawables: `PackageManager` returns adaptive icons that
 * cost 100+ KB of native heap each when drawn directly, and a 2.7 GB head unit notices eight of them.
 */
class DockView(context: Context) : LinearLayout(context) {

    interface Callback {
        fun onLaunch(entry: AppRepository.AppEntry)
        fun onLongPress(entry: AppRepository.AppEntry)
        fun onEmptySlot(index: Int)
        fun onAllApps()
    }

    var callback: Callback? = null

    private val slots = ArrayList<ImageView>(10)
    private var entries: List<AppRepository.AppEntry> = emptyList()
    private val iconPx by lazy { (boxSizeDp() * 0.66f * resources.displayMetrics.density).toInt() }

    private fun boxSizeDp(): Int = LauncherPrefs.get(context).dockSizeDp.coerceIn(40, 96)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = Views.dp(context, 10f)
        setPadding(pad, 0, pad, 0)
        isClickable = true
        rebuild()
    }

    fun rebuild() {
        val p = LauncherPrefs.get(context)
        val tokens = p.dockApps
        entries = AppRepository.favorites(tokens)
        removeAllViews()
        slots.clear()
        // One trailing "+" so the dock is editable from the home screen itself, never a full row of gaps.
        val size = boxSizeDp()
        val count = (tokens.size + 1).coerceIn(4, 10)
        for (i in 0 until count) {
            val entry = entries.getOrNull(i)
            val slot = makeSlot(entry, i, size)
            slots.add(slot)
            addView(slot, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = if (i == 0) 0 else Views.dp(context, 6f)
            })
        }
        addView(Views.weightSpacer(context), LayoutParams(0, 1, 1f))
        addView(makeTrailing(R.drawable.ic_apps, R.string.dock_all_apps) { callback?.onAllApps() }, trailing(size))
        background = Palette.card(context, radiusDp = 18f, alphaPercent = if (Palette.night) 62 else 46, clickable = false)
    }

    private fun trailing(size: Int): LayoutParams = LayoutParams(size + Views.dp(context, 18f), size).apply {
        marginStart = Views.dp(context, 6f)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeSlot(entry: AppRepository.AppEntry?, index: Int, sizeDp: Int): ImageView {
        val size = sizeDp
        val v = ImageView(context).apply {
            layoutParams = LayoutParams(size, size)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = Views.dp(context, 6f)
            setPadding(pad, pad, pad, pad)
            background = Palette.chip(context, false)
            isClickable = true
            isFocusable = true
        }
        if (entry == null) {
            v.setImageResource(R.drawable.ic_plus)
            v.setColorFilter(Palette.colors.onSurfaceMuted)
            v.contentDescription = context.getString(R.string.dock_add_hint)
            v.setOnClickListener { callback?.onEmptySlot(index) }
        } else {
            val bmp = AppRepository.icon(context, entry, iconPx)
            if (bmp != null) v.setImageBitmap(bmp) else v.setImageDrawable(entryBadge(entry))
            v.contentDescription = entry.label
            v.setOnClickListener { callback?.onLaunch(entry) }
            v.setOnLongClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                callback?.onLongPress(entry)
                true
            }
        }
        return v
    }

    private fun entryBadge(entry: AppRepository.AppEntry): Drawable? = try {
        context.packageManager.getActivityIcon(entry.component)
    } catch (_: Throwable) {
        null
    }

    private fun makeTrailing(icon: Int, desc: Int, onClick: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(Palette.colors.onSurface)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = Views.dp(context, 9f)
        setPadding(pad, pad, pad, pad)
        background = Palette.chip(context, false)
        contentDescription = context.getString(desc)
        isClickable = true
        setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            onClick()
        }
    }
}

/**
 * Top status strip. It is deliberately *not* a notification shade replacement: everything on it is
 * reachable with the vehicle moving, so it holds only the clock, the Jalali date and four switches.
 */
class TopBar(context: Context) : LinearLayout(context) {

    interface Callback {
        fun onTheme()
        fun onMute()
        fun onScreenOff()
        fun onSettings()
        fun onClockTap()
    }

    var callback: Callback? = null

    private val time = Views.text(context, "00:00", 24f, Palette.colors.onSurface, bold = true, condensed = true)
    private val date = Views.text(context, "", 11.5f, Palette.colors.accent)
    private val sub = Views.text(context, "", 10f, Palette.colors.onSurfaceMuted, condensed = true)
    private val mute: ImageView
    private val cal = Calendar.getInstance()
    private var muted = false

    val tick = object : TimeTicker.Tick {
        override fun onTick(nowMillis: Long) {
            cal.timeInMillis = nowMillis
            val p = LauncherPrefs.get(context)
            val fa = p.usePersianDigits
            time.text = Format.localized(
                context,
                Format.hourMinute(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), p.clock24h, fa)
            )
            val jd = Jalali.fromGregorian(
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
            )
            date.text = if (p.showJalaliDate) {
                Jalali.formatLong(jd, true, fa)
            } else {
                Jalali.WEEKDAYS_FA[jd.weekday]
            }
            sub.text = if (p.showGregorianDate) {
                Format.localized(context, SimpleDateFormat("EEE d MMM yyyy", Locale.ENGLISH).format(Date(nowMillis)))
            } else {
                Format.localized(context, Jalali.formatShort(jd, fa))
            }
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = Views.dp(context, 12f)
        setPadding(pad, Views.dp(context, 6f), pad, Views.dp(context, 4f))

        val clockBlock = LinearLayout(context).apply {
            orientation = VERTICAL
            isClickable = true
            setOnClickListener { callback?.onClockTap() }
        }
        val row = Views.row(context)
        row.addView(time, matchWrap())
        row.addView(Views.spacer(context, 8f))
        row.addView(date, matchWrap())
        clockBlock.addView(row, matchWrap())
        clockBlock.addView(sub, matchWrap())
        addView(clockBlock, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        addView(iconButton(R.drawable.ic_sun, R.string.top_theme) { callback?.onTheme() })
        mute = iconButton(R.drawable.ic_volume_up, R.string.top_mute) { callback?.onMute() }
        addView(mute)
        addView(iconButton(R.drawable.ic_screen_off, R.string.top_screen_off) { callback?.onScreenOff() })
        addView(iconButton(R.drawable.ic_settings, R.string.top_settings) { callback?.onSettings() })
    }

    private fun matchWrap() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    /** Kept public because the mute state is owned by the activity (audio focus is per-process). */

    private fun iconButton(icon: Int, desc: Int, onClick: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(Palette.colors.onSurface)
        val s = Views.dp(context, 34f)
        layoutParams = LayoutParams(s, s).apply { marginStart = Views.dp(context, 6f) }
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = Views.dp(context, 7f)
        setPadding(pad, pad, pad, pad)
        background = Palette.chip(context, false)
        contentDescription = context.getString(desc)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Re-tint after a palette flip; the text colours are set, not themed. */
    fun refresh() {
        val c = Palette.colors
        time.setTextColor(c.onSurface)
        date.setTextColor(c.accent)
        sub.setTextColor(c.onSurfaceMuted)
        mute.setColorFilter(if (muted) c.warn else c.onSurface)
        tick.onTick(System.currentTimeMillis())
    }

    fun setMuted(on: Boolean) {
        muted = on
        mute.setImageResource(if (on) R.drawable.ic_volume_down else R.drawable.ic_volume_up)
        mute.setColorFilter(if (on) Palette.colors.warn else Palette.colors.onSurface)
    }

    fun isMuted() = muted

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        TimeTicker.register(tick, this)
    }

    override fun onDetachedFromWindow() {
        TimeTicker.unregister(tick)
        super.onDetachedFromWindow()
    }
}
