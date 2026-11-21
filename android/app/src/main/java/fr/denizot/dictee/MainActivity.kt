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
    private lateinit var pauseButton: Button
    private lateinit var notificationStatus: TextView
    private lateinit var notificationModeButton: Button
    private lateinit var headsetStatus: TextView
    private lateinit var history: LinearLayout
    private lateinit var keyboardStatus: TextView
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
        pauseButton = button("Pause") {
            startService(Intent(this, CaptureService::class.java).setAction(if (CaptureService.paused) CaptureService.RESUME else CaptureService.PAUSE))
            handler.postDelayed({ updateRecordingUi() }, 250)
        }
        pauseButton.visibility = View.GONE
        root.addView(pauseButton)
        root.addView(text("Enregistrez, mettez en pause, reprenez ou arrêtez ; la transcription reste manuelle après la sauvegarde.", 13f))
        root.addView(text("COMMANDES SUR ÉCRAN VERROUILLÉ", 14f, true))
        notificationStatus = text("Vérification des notifications…", 13f)
        root.addView(notificationStatus)
        root.addView(button("Autoriser les notifications") {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
            } else {
                openNotificationSettings()
            }
        })
        root.addView(button("Afficher les réglages de notification") { openNotificationSettings() })
        root.addView(text(
            "Essai du panneau multimédia : commandes compactes avec état du microphone. " +
                "Si Motorola les affiche mal, reviens immédiatement à la notification classique.",
            13f
        ))
        notificationModeButton = button("Chargement du mode de notification…") {
            NotificationMode.setMediaStyleEnabled(
                this, !NotificationMode.mediaStyleEnabled(this)
            )
            updateRemoteControlsUi()
            if (CaptureService.active) {
                startService(Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.REFRESH_NOTIFICATION))
            }
        }
        root.addView(notificationModeButton)
        root.addView(text("ÉCOUTEURS : COMMANDE RENFORCÉE (FACULTATIVE)", 14f, true))
        root.addView(text("Android donne parfois les boutons Play/Pause au dernier lecteur audio. Ce mode utilise une autorisation d'accessibilité pour intercepter la touche pendant la dictée. Il n'analyse ni le texte ni le contenu des écrans ; l'autorisation reste toutefois étendue et doit être activée par vous.", 13f))
        headsetStatus = text("Vérification de la commande…", 13f)
        root.addView(headsetStatus)
        root.addView(button("Configurer la commande des écouteurs") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
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
        root.addView(text("MES DICTÉES", 16f, true))
        history = column(); root.addView(history)
        setContentView(scroll)
        store.recoverAbandonedSessions()
        refreshHistory()
        refreshKeyboardStatus()
        handler.post(object : Runnable {
            override fun run() {
                updateRecordingUi()
                updateRemoteControlsUi()
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
    override fun onResume() {
        super.onResume()
        if (::keyboardStatus.isInitialized) refreshKeyboardStatus()
        if (::notificationStatus.isInitialized) updateRemoteControlsUi()
    }
    private fun updateRecordingUi() {
        recorderButton.text = if (CaptureService.active) "Arrêter et sauvegarder (sans transcrire)" else "Démarrer l'enregistrement"
        pauseButton.visibility = if (CaptureService.active) View.VISIBLE else View.GONE
        pauseButton.text = if (CaptureService.paused) "▶ Reprendre l'enregistrement" else "⏸ Mettre en pause"
        status.text = when {
            CaptureService.active && CaptureService.paused -> "⏸ Enregistrement en pause · audio préservé"
            CaptureService.active -> "● Enregistrement actif, même écran verrouillé"
            CaptureService.lastError.isNotBlank() -> CaptureService.lastError
            else -> "Microphone inactif"
        }
    }
    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        })
    }

    private fun updateRemoteControlsUi() {
        val notificationsAllowed = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val notificationManager = getSystemService(android.app.NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel(CaptureService.CHANNEL)
        notificationStatus.text = when {
            !notificationsAllowed -> "Notifications non autorisées. Active-les pour afficher Pause/Reprendre."
            !notificationManager.areNotificationsEnabled() -> "Notifications coupées pour l'application."
            channel != null && channel.importance == android.app.NotificationManager.IMPORTANCE_NONE ->
                "Le canal « Commandes de dictée » est désactivé. Réactive-le dans les réglages."
            else -> "Notifications autorisées. Leur affichage sur l'écran verrouillé dépend aussi des réglages Motorola."
        }
        if (::notificationModeButton.isInitialized) {
            notificationModeButton.text = if (NotificationMode.mediaStyleEnabled(this)) {
                "Présentation : panneau multimédia (toucher pour revenir au classique)"
            } else {
                "Présentation : notification classique (toucher pour essayer le panneau)"
            }
        }
        headsetStatus.text = if (HeadsetKeyService.connected) {
            "Commande renforcée active. Dernière touche : " + HeadsetKeyService.lastKeyDescription
        } else {
            "Commande renforcée inactive. Pour l'activer : Paramètres > Accessibilité > Dictée : boutons des écouteurs."
        }
    }

    private fun askAndRecord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101); return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
            return
        }
        startForegroundCapture()
    }
    private fun startForegroundCapture() {
        try { startForegroundService(Intent(this, CaptureService::class.java).setAction(CaptureService.START).putExtra("source", "app")) }
        catch (e: Exception) { toast("Le service audio n'a pas démarré : ${e.message}") }
        handler.postDelayed({ updateRecordingUi() }, 400)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) askAndRecord()
        if (requestCode == 102) {
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                toast("Notifications refusées. Les commandes sur écran verrouillé peuvent rester invisibles.")
            }
            updateRemoteControlsUi()
            startForegroundCapture()
        }
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
            if (item.segments.isNotEmpty() && item.state != "recording" && item.state != "paused" && item.state != "transcribing") {
                panel.addView(button("Écouter l'audio") { playSegments(item) })
                if (item.state != "done") panel.addView(button("✨ Transcrire avec l'IA (à la demande)") { transcribe(item.id) })
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