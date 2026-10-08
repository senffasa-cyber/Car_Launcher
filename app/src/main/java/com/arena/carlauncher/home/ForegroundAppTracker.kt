package com.arena.carlauncher.home

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfoInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Optional accessibility service used purely to read the foreground window.
 * `accessibility_foreground.xml` requests no feedback, no window changes and no content — it can
 * only observe, which is why it is safe to leave enabled in a car.
 */
class ForegroundAppTracker : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            }
        } catch (_: Throwable) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        ForegroundAppWatcher.report(pkg)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        fun enabled(ctx: Context): Boolean = try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            val mine = ctx.packageName
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .orEmpty().any { it.packageName == mine }
        } catch (_: Throwable) {
            false
        }

        fun openSettings(ctx: Context) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
            }
        }
    }
}
