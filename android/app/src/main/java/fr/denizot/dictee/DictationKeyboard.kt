package fr.denizot.dictee

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Basic AZERTY IME: retains normal typing and inserts transcription into the focused editor. */
class DictationKeyboard : InputMethodService() {
    private val store by lazy { SessionStore(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var info: TextView? = null
    private var mic: Button? = null
    private var requestTime: Long = 0
    private var pending = false
    private var runningTranscription = false
    private var handledSessionId: String? = null
    private var uppercase = false

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun addKey(row: LinearLayout, label: String, weight: Float = 1f, onClick: () -> Unit) {
        val btn = Button(this).apply {
            text = label; isAllCaps = false; minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0); textSize = if (label.length > 3) 10f else 17f
            setOnClickListener { onClick() }
        }
        row.addView(btn, LinearLayout.LayoutParams(0, dp(48), weight))
    }
    private fun row(root: LinearLayout) = LinearLayout(this).also { root.addView(it) }
    private fun write(s: String) { currentInputConnection?.commitText(s, 1) }
    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(4), dp(3), dp(8))
            setBackgroundColor(Color.rgb(245, 238, 225))
        }
        info = TextView(this).apply {
            text = "Dictée universelle · clavier AZERTY"
            textSize = 13f; setTextColor(Color.DKGRAY); setPadding(dp(8), 0, 0, dp(4))
        }
        root.addView(info)
        val actions = row(root)
        addKey(actions, "🎙 Dicter", 2f) { toggleRecording() }
        addKey(actions, "Coller texte", 1.5f) { store.all().firstOrNull { it.transcript.isNotBlank() }?.let { write(it.transcript) } }
        addKey(actions, "⚙", 0.6f) { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        addKey(actions, "🌐", 0.6f) { switchToNextInputMethod(false) }
        mic = actions.getChildAt(0) as Button
        listOf("azertyuiop", "qsdfghjklm", "wxcvbn").forEachIndexed { index, letters ->
            val line = row(root)
            if (index == 2) addKey(line, "⇧", 1f) { uppercase = !uppercase; info?.text = if (uppercase) "Majuscule" else "Dictée universelle" }
            letters.forEach { c -> addKey(line, c.toString()) { write(if (uppercase) c.uppercaseChar().toString() else c.toString()); uppercase = false } }
            if (index == 2) addKey(line, "⌫", 1f) { currentInputConnection?.deleteSurroundingText(1, 0) }
        }
        val bottom = row(root)
        addKey(bottom, ",") { write(",") }
        addKey(bottom, "espace", 5f) { write(" ") }
        addKey(bottom, ".") { write(".") }
        addKey(bottom, "↵") { write("\n") }
        return root
    }
    private fun toggleRecording() {
        if (CaptureService.active) {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.STOP))
            info?.text = "Enregistrement terminé, transcription en préparation…"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Autorisez le microphone depuis l'application", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        requestTime = System.currentTimeMillis(); pending = true; handledSessionId = null
        try {
            startForegroundService(Intent(this, CaptureService::class.java).setAction(CaptureService.START).putExtra("source", "keyboard"))
            info?.text = "Enregistrement en cours…"
        } catch (e: Exception) {
            pending = false
            info?.text = "Démarrez depuis l'application si Android bloque le clavier"
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            mic?.text = if (CaptureService.active) "■ Terminer" else "🎙 Dicter"
            if (pending && !CaptureService.active && !runningTranscription) {
                val latest = store.all().firstOrNull { it.source == "keyboard" && it.createdAt >= requestTime }
                if (latest != null && latest.state != "recording" && handledSessionId != latest.id) {
                    handledSessionId = latest.id; pending = false
                    if (latest.segments.isNotEmpty()) runTranscription(latest.id)
                    else info?.text = latest.error.ifBlank { "Aucun audio exploitable" }
                }
            }
            handler.postDelayed(this, 750)
        }
    }
    private fun runTranscription(id: String) {
        runningTranscription = true
        info?.text = "Transcription IA en cours…"
        thread(name = "ime-transcription") {
            try {
                val result = Transcriber(this).transcribe(id)
                handler.post {
                    runningTranscription = false
                    val type = currentInputEditorInfo?.inputType ?: 0
                    val variation = type and InputType.TYPE_MASK_VARIATION
                    val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                        variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    if (currentInputConnection != null && !isPassword) {
                        write(result); info?.text = "Transcription insérée"
                    } else info?.text = "Texte conservé dans l'historique : ouvrez l'application"
                }
            } catch (e: Exception) {
                handler.post { runningTranscription = false; info?.text = "Échec : ${e.message}" }
            }
        }
    }
    override fun onWindowShown() { super.onWindowShown(); handler.removeCallbacks(tick); handler.post(tick) }
    override fun onWindowHidden() { handler.removeCallbacks(tick); super.onWindowHidden() }
    override fun onDestroy() { handler.removeCallbacks(tick); super.onDestroy() }
}