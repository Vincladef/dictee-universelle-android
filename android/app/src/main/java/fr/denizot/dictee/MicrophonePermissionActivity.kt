package fr.denizot.dictee

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * Pont minimal pour demander RECORD_AUDIO depuis le clavier.
 * Aucun écran applicatif ; seul le dialogue système s'affiche la première fois.
 */
class MicrophonePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startKeyboardRecorder()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 301)
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 301) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startKeyboardRecorder()
        } else {
            Toast.makeText(this, "Autorisation microphone refusée", Toast.LENGTH_LONG).show()
            finish()
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
