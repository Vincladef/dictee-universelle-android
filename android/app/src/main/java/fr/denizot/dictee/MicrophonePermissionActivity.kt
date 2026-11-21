package fr.denizot.dictee

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Foreground, user-triggered permission bridge for the IME.
 * Notification permission is independent from microphone permission.
 */
class MicrophonePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        continuePermissions()
    }

    private fun continuePermissions() {
        when {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 301)
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 302)
            else -> startKeyboardRecorder()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            301 -> if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                continuePermissions()
            } else {
                Toast.makeText(this, "Autorisation microphone refusée", Toast.LENGTH_LONG).show()
                finish()
            }
            302 -> {
                if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this,
                        "Notifications désactivées : Pause/Reprendre sera masqué sur l'écran verrouillé. Autorisez-les dans les réglages.",
                        Toast.LENGTH_LONG).show()
                }
                startKeyboardRecorder()
            }
        }
    }

    private fun startKeyboardRecorder() {
        try {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.START)
                    .putExtra("source", "keyboard")
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Micro inaccessible : " + (e.message ?: "Erreur Android"), Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
