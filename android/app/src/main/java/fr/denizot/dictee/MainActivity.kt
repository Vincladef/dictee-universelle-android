package fr.denizot.dictee

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val store by lazy { SessionStore(this) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var recorderButton: Button
    private lateinit var history: LinearLayout
    private lateinit var keyboardStatus: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: Button
    private lateinit var endpointField: EditText
    private lateinit var tokenField: EditText
    private var player: MediaPlayer? = null
    private var lastSnapshot: String = ""
    private val ink = Color.rgb(55, 49, 41)
    private val cream = Color.rgb(252, 247, 236)
    private val accent = Color.rgb(121, 77, 49)

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 16f, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = value; textSize = size; setTextColor(ink)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(5), 0, dp(5))
    }
    private fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun button(value: String, action: () -> Unit): Button = Button(this).apply {
        text = value; isAllCaps = false; setTextColor(ink); setOnClickListener { action() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = accent
        window.navigationBarColor = cream
        val scroll = ScrollView(this).apply { setBackgroundColor(cream); isFillViewport = true }
        val root = column().apply { setPadding(dp(20), dp(22), dp(20), dp(24)) }
        scroll.addView(root)
        root.addView(text("Dictée universelle", 28f, true))
        root.addView(text("Parlez, verrouillez votre téléphone, puis retranscrivez vos idées.", 14f))
        root.addView(text("ENREGISTREMENT", 14f, true))
        status = text("Microphone inactif", 16f)
        root.addView(status)
        recorderButton = button("Démarrer l'enregistrement") { if (CaptureService.active) stopRecording() else askAndRecord() }
        root.addView(recorderButton)
        root.addView(text("L'enregistrement reste actif écran verrouillé. Arrêtez-le depuis cette page ou la notification.", 13f))
        root.addView(text("CLAVIER DE DICTÉE", 14f, true))
        keyboardStatus = text("Vérification du clavier actif…", 13f)
        root.addView(keyboardStatus)
        root.addView(button("Activer le clavier") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        root.addView(button("Choisir le clavier actif") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        root.addView(text("PARAMÈTRES DE TRANSCRIPTION", 14f, true))
        root.addView(text("Adresse HTTPS du serveur (Atelier BD)", 13f))
        endpointField = EditText(this).apply {
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(DictationSettings.endpoint(this@MainActivity))
        }
        root.addView(endpointField)
        root.addView(text("Code d'accès personnel (ne saisissez pas votre clé OpenAI)", 13f))
        tokenField = EditText(this).apply {
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(DictationSettings.token(this@MainActivity))
        }
        root.addView(tokenField)
        root.addView(button("Enregistrer les paramètres") {
            val endpoint = endpointField.text.toString().trim()
            if (!endpoint.startsWith("https://")) {
                toast("Une adresse HTTPS est nécessaire")
            } else {
                DictationSettings.save(this, endpoint, tokenField.text.toString())
                toast("Paramètres enregistrés")
            }
        })
        root.addView(text("MISES À JOUR DU CLAVIER", 14f, true))
        updateStatus = text("Vérification automatique des nouvelles versions", 13f)
        root.addView(updateStatus)
        updateButton = button("Vérifier maintenant") {
            updateStatus.text = "Recherche et téléchargement éventuel…"
            Updates.check(this, force = true) { state ->
                if (!isFinishing) { updateStatus.text = state; refreshUpdateUi() }
            }
        }
        root.addView(updateButton)
        root.addView(button("Installer la mise à jour disponible") {
            toast(Updates.install(this))
        })
        root.addView(text("Les versions sont téléchargées automatiquement ; Android demande une confirmation pour les installer.", 12f))
        root.addView(text("MES DICTÉES", 16f, true))
        history = column(); root.addView(history)
        setContentView(scroll)
        refreshHistory()
        refreshKeyboardStatus()
        Updates.schedule(this)
        refreshUpdateUi()
        Updates.check(this) { state ->
            if (!isFinishing) { updateStatus.text = state; refreshUpdateUi() }
        }
        handler.post(object : Runnable {
            override fun run() {
                updateRecordingUi()
                val snapshot = store.all().joinToString(";") { "${it.id}:${it.state}:${it.segments.size}:${it.transcript.length}" }
                if (snapshot != lastSnapshot) { lastSnapshot = snapshot; refreshHistory() }
                handler.postDelayed(this, 1300)
            }
        })
    }
    private fun refreshKeyboardStatus() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: ""
        keyboardStatus.text = when {
            selected.startsWith("$packageName/") && selected.contains("DictationKeyboard") ->
                "✓ Dictée universelle est le clavier actif"
            enabled -> "Clavier installé et autorisé ; touchez « Choisir le clavier actif » pour remplacer Gboard"
            else -> "Clavier installé mais désactivé ; touchez « Activer le clavier »"
        }
    }
    private fun refreshUpdateUi() {
        val version = Updates.ready(this)
        if (version != null) updateStatus.text = "Version ${version.first} téléchargée et prête à installer"
    }
    override fun onResume() {
        super.onResume()
        if (::updateStatus.isInitialized) refreshUpdateUi()
        if (::keyboardStatus.isInitialized) refreshKeyboardStatus()
    }
    private fun updateRecordingUi() {
        recorderButton.text = if (CaptureService.active) "Terminer et conserver la dictée" else "Démarrer l'enregistrement"
        status.text = when {
            CaptureService.active -> "● Enregistrement actif, même écran verrouillé"
            CaptureService.lastError.isNotBlank() -> CaptureService.lastError
            else -> "Microphone inactif"
        }
    }
    private fun askAndRecord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101); return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
            // Microphone permission is already granted; starting does not depend on notification consent.
        }
        try { startForegroundService(Intent(this, CaptureService::class.java).setAction(CaptureService.START).putExtra("source", "app")) }
        catch (e: Exception) { toast("Le service audio n'a pas démarré : ${e.message}") }
        handler.postDelayed({ updateRecordingUi() }, 400)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) askAndRecord()
    }
    private fun stopRecording() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.STOP))
        handler.postDelayed({ refreshHistory(); updateRecordingUi() }, 750)
    }
    private fun refreshHistory() {
        history.removeAllViews()
        val entries = store.all()
        if (entries.isEmpty()) { history.addView(text("Aucune dictée pour le moment.", 14f)); return }
        entries.forEach { item ->
            val panel = column().apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(Color.rgb(244, 234, 218))
                val m = LinearLayout.LayoutParams(-1, -2)
                m.topMargin = dp(10); layoutParams = m
            }
            val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE).format(Date(item.createdAt))
            val duration = if (item.durationMs > 0) " · ${item.durationMs / 60000} min ${item.durationMs / 1000 % 60} s" else ""
            panel.addView(text("$date$duration", 15f, true))
            panel.addView(text("État : ${item.state} · ${item.segments.size} segment(s)", 13f))
            if (item.error.isNotBlank()) panel.addView(text("Erreur : ${item.error}", 13f))
            if (item.transcript.isNotBlank()) {
                panel.addView(text(item.transcript.take(450) + (if (item.transcript.length > 450) "…" else ""), 14f))
            }
            if (item.segments.isNotEmpty() && item.state != "recording") {
                panel.addView(button("Écouter l'audio") { playSegments(item) })
                if (item.state != "done") panel.addView(button("Transcrire avec l'IA") { transcribe(item.id) })
            }
            if (item.transcript.isNotBlank()) {
                panel.addView(button("Copier le texte") {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Dictée", item.transcript))
                    toast("Texte copié")
                })
                panel.addView(button("Partager le texte") {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, item.transcript)
                    }, "Partager la dictée"))
                })
            }
            panel.addView(button("Supprimer cette dictée") {
                android.app.AlertDialog.Builder(this).setTitle("Supprimer l'audio et la transcription ?")
                    .setNegativeButton("Annuler", null)
                    .setPositiveButton("Supprimer") { _, _ -> store.delete(item); refreshHistory() }
                    .show()
            })
            history.addView(panel)
        }
    }
    private fun transcribe(id: String) {
        if (DictationSettings.token(this).length < 20) { toast("Saisissez le code d'accès au serveur") ; return }
        toast("Transcription en cours ; gardez l'application ouverte")
        thread(name = "dictee-transcription") {
            try {
                Transcriber(this).transcribe(id) { message -> runOnUiThread { status.text = message } }
                runOnUiThread { refreshHistory(); toast("Transcription enregistrée") }
            } catch (e: Exception) {
                runOnUiThread { refreshHistory(); toast("Échec : ${e.message}") }
            }
        }
    }
    private fun playSegments(item: DictationSession, index: Int = 0) {
        player?.release(); player = null
        if (index >= item.segments.size) return
        try {
            val media = MediaPlayer()
            media.setDataSource(store.segmentFile(item, index).absolutePath)
            media.setOnCompletionListener { playSegments(item, index + 1) }
            media.prepare(); media.start(); player = media
        } catch (e: Exception) { toast("Lecture impossible : ${e.message}") }
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); player?.release(); super.onDestroy() }
}