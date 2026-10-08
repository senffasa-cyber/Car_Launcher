package com.arena.carlauncher.home

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.arena.carlauncher.R
import com.arena.carlauncher.data.LauncherPrefs
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.actions.ActionRouter

/**
 * One bound third-party [AppWidgetHost] for the whole launcher.
 *
 * A head unit shares 2.7 GB of RAM with the IVI stack, so the widget host is allowed exactly one
 * widget: enough for the thing people actually ask for (an OEM media queue, a tyre monitor), not
 * enough for someone to hang six live clocks off the home screen. The id is persisted and the host
 * view is created once — re-inflating it on every `onBind()` re-creates a RemoteViews connection and
 * leaks a Binder, which is how a launcher card ends up at 400 MB.
 *
 * Binding itself is done by the system picker: a normal app may not call
 * `AppWidgetManager.bindAppWidgetId` (it needs `BIND_APPWIDGET`), but starting
 * [AppWidgetManager.ACTION_APPWIDGET_PICK] with an allocated id makes the picker bind that id to
 * *our* host on our behalf. So the flow lives in the activity, not here.
 */
object WidgetSlot {

    private const val TAG = "WidgetSlot"
    private const val KEY_ID = "widget_host_id"
    private const val KEY_PROVIDER = "widget_provider"

    /** Host id: any stable non-zero value unique per package. */
    private const val HOST_ID = 0x5A0E

    private var host: AppWidgetHost? = null
    private var view: AppWidgetHostView? = null

    /** Id handed out for the currently running pick, so the result can be verified. */
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID

    val hasWidget: Boolean get() = view != null

    fun providerComponent(ctx: Context): ComponentName? =
        ComponentName.unflattenFromString(LauncherPrefs.get(ctx).get(KEY_PROVIDER, ""))

    fun boundId(ctx: Context): Int =
        LauncherPrefs.get(ctx).get(KEY_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

    private fun ensureHost(ctx: Context): AppWidgetHost? {
        host?.let { return it }
        return try {
            AppWidgetHost(ctx.applicationContext, HOST_ID).also { host = it }
        } catch (t: Throwable) {
            Log.w(TAG, "host create failed: ${t.message}")
            null
        }
    }

    // ------------------------------------------------------------------ picking

    /** Allocates an id and returns the intent the host activity should start for a result. */
    fun beginPick(ctx: Context): Intent? {
        val h = ensureHost(ctx) ?: return null
        return try {
            val id = h.allocateAppWidgetId()
            pendingId = id
            Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } catch (t: Throwable) {
            Log.w(TAG, "allocate failed: ${t.message}")
            null
        }
    }

    /**
     * @return true when the pick produced a widget (the caller should then refresh the carousel), or
     * false when the user cancelled — in which case the allocated id is released again.
     */
    fun handlePickResult(ctx: Context, resultCode: Int, data: Intent?): Boolean {
        val id = data?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (resultCode != android.app.Activity.RESULT_OK || id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            release(ctx, pendingId)
            pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
            return false
        }
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        val info = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id) ?: run {
            release(ctx, id)
            return false
        }
        // A previous widget in the slot has to be unbound first or the host keeps its view alive.
        clear(ctx)
        LauncherPrefs.get(ctx).put(KEY_ID, id)
        LauncherPrefs.get(ctx).put(KEY_PROVIDER, info.provider?.flattenToString().orEmpty())
        view = null
        return true
    }


    private fun release(ctx: Context, id: Int) {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        try {
            host?.deleteAppWidgetId(id)
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ attaching

    /** The card's content: either the live host view or a "tap to add" placeholder. */
    fun view(ctx: Context): FrameLayout {
        val frame = FrameLayout(ctx)
        if (boundId(ctx) == AppWidgetManager.INVALID_APPWIDGET_ID || providerComponent(ctx) == null) {
            frame.addView(
                TextView(ctx).apply {
                    text = ctx.getString(R.string.widget_empty)
                    setTextColor(Palette.colors.onSurfaceMuted)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    isClickable = true
                    setOnClickListener { com.arena.carlauncher.actions.ActionRouter.ui?.pickWidget() }
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                )
            )
        } else {
            frame.addView(
                TextView(ctx).apply {
                    text = ctx.getString(R.string.widget_hold_hint)
                    setTextColor(Palette.colors.onSurfaceMuted)
                    textSize = 10.5f
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                )
            )
        }
        return frame
    }

    fun attach(container: FrameLayout) {
        val ctx = container.context
        val id = boundId(ctx)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val h = ensureHost(ctx) ?: return
        val provider = providerComponent(ctx) ?: return
        // AppWidgetHost.createView wants the *info*, not the component: the host re-resolves the
        // provider through it (options, min/max size, configure activity).
        val awm = AppWidgetManager.getInstance(ctx)
        val info = try {
            // getAppWidgetInfo takes an id; a provider is resolved through getAppWidgetIds first.
            awm.getAppWidgetInfo(id)
                ?: awm.getAppWidgetIds(provider).firstOrNull()?.let { awm.getAppWidgetInfo(it) }
        } catch (_: Throwable) {
            null
        } ?: return
        if (view == null) {
            view = try {
                h.createView(ctx, id, info).also { v ->
                    val opts = Bundle().apply {
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 560)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120)
                        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 260)
                    }
                    try {
                        AppWidgetManager.getInstance(ctx).updateAppWidgetOptions(id, opts)
                    } catch (_: Throwable) {
                    }
                    v.setOnLongClickListener {
                        com.arena.carlauncher.actions.ActionRouter.ui?.removeWidget()
                        true
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "createView($id) failed: ${t.message}")
                null
            }
        }
        val v = view ?: return
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        if (v.parent == null) container.addView(
            v, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            )
        )
        try {
            h.startListening()
        } catch (_: Throwable) {
        }
    }

    fun detach(container: FrameLayout) {
        val v = view ?: return
        if (v.parent === container) container.removeView(v)
        try {
            host?.stopListening()
        } catch (_: Throwable) {
        }
    }

    fun clear(ctx: Context) {
        val id = boundId(ctx)
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
            try {
                host?.deleteAppWidgetId(id)
            } catch (_: Throwable) {
            }
        }
        LauncherPrefs.get(ctx).put(KEY_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        LauncherPrefs.get(ctx).put(KEY_PROVIDER, "")
        view = null
    }

    /** Frees the view but keeps the binding (used when the launcher is reinstalled over itself). */
    fun dropView() {
        view = null
    }
}
