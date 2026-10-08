package com.arena.carlauncher.services

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.arena.carlauncher.CarApp
import com.arena.carlauncher.R
import com.arena.carlauncher.theme.Palette

/**
 * Incoming-call banner drawn over whatever is on screen.
 *
 * In a car the call UI of the phone (or of the Bluetooth stack) is often useless or absent, so the
 * launcher shows its own big answer/hang-up strip. It is a normal overlay window, which means it
 * needs `Settings → Display over other apps`; without that grant the service simply does nothing.
 */
class CallOverlayService : Service() {

    private var wm: WindowManager? = null
    private var root: View? = null
    private var lastState = TelephonyManager.CALL_STATE_IDLE

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                    val state = when (i.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                        "RINGING" -> TelephonyManager.CALL_STATE_RINGING
                        "OFFHOOK" -> TelephonyManager.CALL_STATE_OFFHOOK
                        else -> TelephonyManager.CALL_STATE_IDLE
                    }
                    onCallState(state, i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        try {
            registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        } catch (t: Throwable) {
            stopSelf()
        }
    }

    private fun onCallState(state: Int, number: String?) {
        val incoming = state == TelephonyManager.CALL_STATE_RINGING
        if (lastState == state) return
        lastState = state
        if (!incoming) {
            hide()
            return
        }
        show(number.orEmpty())
    }

    private fun show(number: String) {
        if (!canDraw()) return
        if (root == null) root = build()
        val v = root ?: return
        v.findViewById<TextView>(R.id.call_number)?.text = number.ifBlank { getString(R.string.call_incoming) }
        try {
            (v.parent as? android.view.ViewGroup)?.removeView(v)
        } catch (_: Throwable) {
        }
        try {
            wm?.addView(v, params())
            v.visibility = View.VISIBLE
        } catch (_: Throwable) {
        }
    }

    private fun hide() {
        val v = root ?: return
        try {
            wm?.removeView(v)
        } catch (_: Throwable) {
        }
        v.visibility = View.GONE
    }

    private fun canDraw(): Boolean =
        Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this)

    private fun params(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP
        y = 0
    }

    private fun build(): View {
        val c = this
        val bar = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = Palette.fill(c, Palette.colors.glass, 18f)
        }
        val label = TextView(c).apply {
            id = R.id.call_number
            setTextColor(Palette.colors.onSurface)
            textSize = 22f
            text = getString(R.string.call_incoming)
        }
        val answer = TextView(c).apply {
            text = getString(R.string.call_answer)
            setTextColor(Palette.colors.onAccent)
            textSize = 18f
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = Palette.fill(c, Palette.colors.ok, 14f)
            setOnClickListener { telecom("acceptRingingCall") }
        }
        val hangup = TextView(c).apply {
            text = getString(R.string.call_reject)
            setTextColor(Palette.colors.onSurface)
            textSize = 18f
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = Palette.fill(c, Palette.colors.danger, 14f)
            setOnClickListener { telecom("endCall"); hide() }
        }
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(label, lp)
        bar.addView(answer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(12) })
        bar.addView(hangup, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return bar
    }

    private fun telecom(method: String) {
        try {
            val tm = getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager ?: return
            val m = tm.javaClass.getMethod(method)
            m.invoke(tm)
        } catch (t: Throwable) {
            // ANSWER_PHONE_CALLS missing -> let the user tap the system UI instead.
            android.util.Log.d("CallOverlay", "$method refused: ${t.message}")
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        hide()
        try {
            unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun ensureRunning(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, CallOverlayService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
