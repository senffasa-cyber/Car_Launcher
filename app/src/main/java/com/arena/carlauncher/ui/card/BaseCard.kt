package com.arena.carlauncher.ui.card

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import com.arena.carlauncher.R
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.ui.Views
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Common shell for every home-screen card.
 *
 * Two sizes share one implementation (`full` for the centre carousel, `mini` for the side columns)
 * so a card behaves identically when the user moves it between slots. Subclasses build their view
 * tree in [onCreate], subscribe in [bind] and are guaranteed to be unsubscribed in [unbind] —
 * ViewPager2 keeps offscreen pages alive, and a card collecting a flow forever is exactly how car
 * launchers end up in a 300 MB RSS OOM.
 */
abstract class BaseCard(
    context: Context,
    val cardId: String,
    val full: Boolean
) : FrameLayout(context) {

    protected val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = Views.dp(context, if (full) 16f else 10f)
        setPadding(p, p, p, p)
    }

    private var scope: CoroutineScope? = null
    private var paletteListener: (() -> Unit)? = null
    private var bound = false

    /** Set by the host so a card can ask the carousel to change page. */
    var onRequestCard: ((String) -> Unit)? = null
    var onOpenSettings: (() -> Unit)? = null

    open val title: String get() = ""
    open val iconRes: Int get() = R.drawable.ic_apps

    init {
        isClickable = false
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        background = cardBackground()
        clipToOutline = false
        onCreate()
    }

    protected fun cardBackground(): Drawable = Palette.card(context)

    protected open fun onCreate() = Unit

    open fun onPaletteChanged() {
        background = cardBackground()
        invalidate()
    }

    fun bind() {
        if (bound) return
        bound = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val l = { onPaletteChanged() }
        paletteListener = l
        Palette.addListener(l)
        onBind()
    }

    fun unbind() {
        if (!bound) return
        bound = false
        paletteListener?.let { Palette.removeListener(it) }
        paletteListener = null
        scope?.cancel()
        scope = null
        onUnbind()
    }

    protected open fun onBind() = Unit
    protected open fun onUnbind() = Unit

    /** Subscribe to a hub flow; automatically cancelled with the card. */
    protected fun <T> observe(flow: Flow<T>, block: (T) -> Unit) {
        val s = scope ?: return
        s.launch { flow.collect { v -> try { block(v) } catch (_: Throwable) {} } }
    }

    protected fun launch(block: suspend CoroutineScope.() -> Unit): Job? = scope?.launch(block = block)

    protected fun post(block: () -> Unit) {
        postDelayed({ block() }, 0L)
    }

    // ------------------------------------------------------------------ small building blocks
    protected fun header(title: String, trailing: View? = null): LinearLayout {
        val row = Views.row(context)
        row.addView(
            Views.icon(context, iconRes, if (full) 16 else 14, Palette.colors.onSurfaceMuted)
        )
        val t = Views.text(
            context, title, if (full) 12f else 10.5f, Palette.colors.onSurfaceMuted
        ).apply {
            letterSpacing = 0.06f
            setPadding(Views.dp(context, 6f), 0, 0, 0)
        }
        row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) row.addView(trailing)
        return row
    }

    /** A compact labelled value used across vehicle / trip / weather rows. */
    protected fun stat(label: String, value: String, accent: Boolean = false): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            addView(
                Views.text(context, label, if (full) 10.5f else 9f, Palette.colors.onSurfaceMuted),
                matchWrap()
            )
            addView(
                Views.text(
                    context, value,
                    if (full) 17f else 13.5f,
                    if (accent) Palette.colors.accent else Palette.colors.onSurface,
                    bold = true, condensed = true
                ),
                matchWrap()
            )
        }

    protected fun matchWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    protected fun weightWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    /** Big tap target used for Home / Work / dashcam rows. */
    protected fun actionRow(
        iconRes: Int,
        label: String,
        sub: String,
        accent: Boolean = false,
        onClick: () -> Unit
    ): View {
        val row = Views.row(context, paddingDp = if (full) 10 else 7).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Palette.chip(context, accent)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }
        row.addView(
            AppCompatImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(if (accent) Palette.colors.onAccent else Palette.colors.accent)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(Views.dp(context, if (full) 24f else 19f), Views.dp(context, if (full) 24f else 19f))
        )
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Views.dp(context, 9f), 0, 0, 0)
        }
        texts.addView(
            Views.text(
                context, label, if (full) 15f else 12.5f,
                if (accent) Palette.colors.onAccent else Palette.colors.onSurface, bold = true
            ), weightWrap()
        )
        if (sub.isNotBlank()) {
            texts.addView(
                Views.text(
                    context, sub, if (full) 11f else 9.5f,
                    if (accent) Palette.colors.onAccent else Palette.colors.onSurfaceMuted
                ), matchWrap()
            )
        }
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    protected fun emptyState(message: String, buttonLabel: String? = null, onClick: (() -> Unit)? = null): View {
        val col = Views.column(context, paddingDp = 0).apply {
            gravity = Gravity.CENTER
        }
        col.addView(
            AppCompatTextView(context).apply {
                text = message
                setTextColor(Palette.colors.onSurfaceMuted)
                textSize = if (full) 14f else 11.5f
                gravity = Gravity.CENTER
                setPadding(0, Views.dp(context, 6f), 0, Views.dp(context, 6f))
            },
            matchWrap()
        )
        if (buttonLabel != null && onClick != null) {
            col.addView(
                Views.text(context, buttonLabel, 12f, Palette.colors.onAccent, bold = true).apply {
                    gravity = Gravity.CENTER
                    setBackgroundDrawable(Palette.fill(context, Palette.colors.accent, 10f))
                    val px = Views.dp(context, 14f)
                    val py = Views.dp(context, 7f)
                    setPadding(px, py, px, py)
                    isClickable = true
                    setOnClickListener { onClick() }
                },
                matchWrap()
            )
        }
        return col
    }
}
