package fr.denizot.dictee

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Opt-in global hardware media-key bridge.
 * Does not inspect windows or text. Only consumes supported keys while an
 * active dictation is running, leaving all other key events untouched.
 */
class HeadsetKeyService : AccessibilityService() {
    companion object {
        @Volatile var connected = false
            private set
        @Volatile var lastKeyDescription = "aucune touche détectée"
            private set
    }

    private var lastDown = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!CaptureService.active) return false
        val supported = event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
        if (!supported) return false

        // Consume both halves of the press; toggle only once on ACTION_DOWN.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDown > 250L) {
                lastDown = now
                lastKeyDescription = KeyEvent.keyCodeToString(event.keyCode) +
                    " (" + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.FRANCE)
                        .format(java.util.Date()) + ")"
                try {
                    startService(Intent(this, CaptureService::class.java).setAction(CaptureService.TOGGLE))
                } catch (_: Exception) {
                    lastKeyDescription += " : commande impossible"
                }
            }
        }
        return true
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }
}
