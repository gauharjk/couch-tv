package dev.couchtv.ontv

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Sees Amazon's home come on screen, event for event, on Fire OS builds that give third-party
 * apps neither their own ADB nor usage events (both seen blocked on Fire OS 8.1.8.5 / RS8185).
 * FireHome switches this service on with the secure-settings grant, and Android keeps it bound
 * across reboots, so the Home key keeps landing on this app's home with no computer involved.
 */
class WatchService : AccessibilityService() {
    override fun onServiceConnected() { connected = true; FireHome.readHomes() }
    override fun onDestroy() { connected = false; super.onDestroy() }
    override fun onInterrupt() {}

    /**
     * Xiaomi remotes: the PatchWall / Mi button arrives as KEYCODE_BUTTON_5 (scan code 580), and the
     * TV's partnercustomizer turns it into a start of PatchWall by name, so no intent filter can
     * claim it. Here it opens Couch TV instead and is swallowed (repeats from a long press too: one
     * open, on release). Every other key goes straight on: this is the only check made per key.
     */
    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_BUTTON_5 || !FireHome.isXiaomi) return false
        if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) App.openHome()
        return true
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString(); val cls = e.className?.toString()
        FireHome.seen(pkg)
        when {
            FireHome.isAmazonHome(pkg, cls) -> FireHome.onAmazonHome()
            FireHome.isPatchWall(pkg, cls) -> FireHome.onPatchWall()
            else -> FireHome.onOtherHome(pkg, cls)
        }
    }

    companion object { @Volatile var connected = false }
}
