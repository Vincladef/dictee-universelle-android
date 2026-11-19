package fr.denizot.dictee

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.concurrent.thread

/**
 * La dictée démarre et se termine SUR le clavier, sans quitter le champ de saisie.
 * Seule l'autorisation microphone initiale, gérée par une Activity transparente,
 * peut momentanément afficher le dialogue système Android.
 */
class DictationKeyboard : InputMethodService() {
    private val store by lazy { SessionStore(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var info: TextView? = null
    private var mic: Button? = null
    private var requestTime: Long = 0
    private var waitingForRecording = false
    private var starting = false
    private var transcriptionRunning = false
    private var handledSessionId: String? = null
    private var upper = false
    private var sourcePackage: String? = null
    private var sourceFieldId: Int? = null
    private var editorChanged = false
    private var statusMessage = "🎙 Dictez directement dans ce champ"

    private val accent = Color.rgb(120, 75, 48)
    private val recordingRed = Color.rgb(175, 43, 43)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun addKey(row: LinearLayout, label: String, weight: Float = 1f, action: () -> Unit): Button {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            minWidth = 0; minimumWidth = 0
            setPadding(0, 0, 0, 0)
            textSize = if (label.length > 8) 11f else 17f
            setOnClickListener { action() }
        }
        row.addView(button, LinearLayout.LayoutParams(0, dp(48), weight))
        return button
    }
    private fun row(root: LinearLayout) = LinearLayout(this).also { root.addView(it) }
    private fun report(value: String) {
        statusMessage = value
        info?.text = value
    }
    private fun isProtected(): Boolean {
        val inputType = currentInputEditorInfo?.inputType ?: return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            else -> false
        }
    }
    private fun write(value: String) {
        if (isProtected()) {
            report("Champ protégé : insertion de dictée désactivée")
            return
        }
        currentInputConnection?.commitText(value, 1)
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(4), dp(3), dp(8))
            setBackgroundColor(Color.rgb(245, 238, 225))
        }
        info = TextView(this).apply {
            text = statusMessage
            textSize = 13f
            setTextColor(accent)
            setPadding(dp(8), dp(1), 0, dp(5))
        }
        root.addView(info)
        val actions = row(root)
        mic = addKey(actions, "🎙 Dicter", 2f) { toggleRecording() }
        addKey(actions, "Insérer texte", 1.5f) {
            val previous = store.all().firstOrNull { it.transcript.isNotBlank() }
            if (previous != null) {
                if (isProtected()) report("Insertion impossible dans un mot de passe")
                else {
                    write(previous.transcript)
                    report("Dernière dictée insérée")
                }
            } else report("Aucune transcription disponible")
        }
        addKey(actions, "⚙", 0.6f) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        addKey(actions, "🌐", 0.6f) { switchToNextInputMethod(false) }
        listOf("azertyuiop", "qsdfghjklm", "wxcvbn").forEachIndexed { index, letters ->
            val line = row(root)
            if (index == 2) addKey(line, "⇧") {
                upper = !upper
                report(if (upper) "Majuscules activées" else "Dictée directement dans ce champ")
            }
            letters.forEach { character ->
                addKey(line, character.toString()) {
                    write(if (upper) character.uppercaseChar().toString() else character.toString())
                    upper = false
                }
            }
            if (index == 2) addKey(line, "⌫") {
                currentInputConnection?.deleteSurroundingText(1, 0)
            }
        }
        val bottom = row(root)
        addKey(bottom, ",") { write(",") }
        addKey(bottom, "espace", 5f) { write(" ") }
        addKey(bottom, ".") { write(".") }
        addKey(bottom, "↵") { write("\n") }
        updateControls()
        return root
    }

    private fun toggleRecording() {
        if (transcriptionRunning) return
        if (CaptureService.active) {
            mic?.isEnabled = false
            mic?.text = "Finalisation…"
            report("Enregistrement terminé ; sauvegarde de l'audio…")
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.STOP))
            return
        }
        if (starting || waitingForRecording) return
        requestTime = System.currentTimeMillis()
        waitingForRecording = true
        starting = true
        handledSessionId = null
        editorChanged = false
        sourcePackage = currentInputEditorInfo?.packageName
        sourceFieldId = currentInputEditorInfo?.fieldId
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            report("Autorisation du microphone : dialogue Android (une seule fois)")
            // On ne lance jamais la page principale depuis le bouton Dicter.
            // Le dialogue système passe par une Activity transparente puis lance le service.
            try {
                startActivity(Intent(this, MicrophonePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                waitingForRecording = false
                starting = false
                report("Impossible de demander l'accès au microphone : " + (e.message ?: "erreur"))
            }
            updateControls()
            return
        }
        try {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.START)
                    .putExtra("source", "keyboard")
            )
            report("Ouverture du microphone…")
        } catch (e: Exception) {
            waitingForRecording = false
            starting = false
            report("Le microphone est inaccessible : " + (e.message ?: "erreur Android"))
        }
        updateControls()
    }

    private fun updateControls() {
        val button = mic ?: return
        val active = CaptureService.active
        val elapsedSeconds = if (active && CaptureService.startedElapsedMs > 0)
            ((SystemClock.elapsedRealtime() - CaptureService.startedElapsedMs) / 1000).coerceAtLeast(0)
        else 0
        val clock = String.format(Locale.FRANCE, "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)
        button.text = when {
            active -> "■ Arrêter · $clock"
            transcriptionRunning -> "Transcription…"
            starting -> "Micro en cours…"
            else -> "🎙 Dicter"
        }
        button.isEnabled = active || (!transcriptionRunning && !starting)
        button.backgroundTintList = ColorStateList.valueOf(if (active) recordingRed else accent)
        button.setTextColor(Color.WHITE)
        if (active && !transcriptionRunning) {
            info?.text = "● Enregistrement $clock · vous pouvez verrouiller l'écran"
        } else if (!waitingForRecording && !transcriptionRunning) {
            info?.text = statusMessage
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (waitingForRecording && !CaptureService.active && !transcriptionRunning) {
                val last = store.all().firstOrNull {
                    it.source == "keyboard" && it.createdAt >= requestTime - 1000L
                }
                if (last != null && last.state != "recording" && handledSessionId != last.id) {
                    handledSessionId = last.id
                    waitingForRecording = false
                    starting = false
                    if (last.state == "ready" && last.segments.isNotEmpty()) {
                        runTranscription(last.id)
                    } else report(last.error.ifBlank { "Aucun son sauvegardé" })
                } else if (starting && System.currentTimeMillis() - requestTime > 12000L) {
                    starting = false
                    waitingForRecording = false
                    report(CaptureService.lastError.ifBlank {
                        "Microphone non autorisé ; accordez l'accès via ⚙"
                    })
                }
            }
            if (CaptureService.active) starting = false
            updateControls()
            handler.postDelayed(this, if (CaptureService.active) 350L else 650L)
        }
    }

    private fun runTranscription(id: String) {
        if (DictationSettings.token(this).length < 20) {
            report("Audio conservé. Configurez une fois la transcription via ⚙.")
            return
        }
        transcriptionRunning = true
        report("Enregistrement conservé ; transcription IA en cours…")
        thread(name = "keyboard-transcription") {
            try {
                val result = Transcriber(this).transcribe(id) { progress ->
                    handler.post { report(progress) }
                }
                handler.post {
                    transcriptionRunning = false
                    val editor = currentInputEditorInfo
                    val sameTarget = !editorChanged &&
                        editor != null &&
                        editor.packageName == sourcePackage &&
                        editor.fieldId == sourceFieldId
                    if (sameTarget && isInputViewShown && currentInputConnection != null && !isProtected()) {
                        write(result)
                        report("Texte inséré directement dans votre message")
                    } else {
                        report("Dictée retranscrite et sauvegardée. Touchez « Insérer texte ».")
                    }
                    updateControls()
                }
            } catch (e: Exception) {
                handler.post {
                    transcriptionRunning = false
                    report("Audio conservé ; transcription à réessayer via ⚙ : " +
                        (e.message ?: "erreur serveur"))
                    updateControls()
                }
            }
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (waitingForRecording && !starting && attribute != null &&
            (attribute.packageName != sourcePackage || attribute.fieldId != sourceFieldId)) {
            editorChanged = true
        }
    }
    override fun onWindowShown() {
        super.onWindowShown()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }
    override fun onWindowHidden() {
        handler.removeCallbacks(tick)
        super.onWindowHidden()
    }
    override fun onDestroy() {
        handler.removeCallbacks(tick)
        // Ne coupe pas l'audio : CaptureService continue si l'écran est verrouillé.
        super.onDestroy()
    }
}
