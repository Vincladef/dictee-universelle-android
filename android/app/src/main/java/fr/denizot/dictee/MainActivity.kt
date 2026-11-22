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
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
    private lateinit var syncEndpointField: EditText
    private lateinit var syncTokenField: EditText
    private lateinit var syncStatus: TextView
    private lateinit var syncToggle: Button
    private lateinit var keyboardStatus: TextView
    private lateinit var endpointField: EditText
    private lateinit var tokenField: EditText
    private var player: MediaPlayer? = null
    private var playingSessionId: String? = null
    private var playingSegment = 0
    private var playbackPaused = false
    private var pendingExportId: String? = null
    private val durations = mutableMapOf<String, List<Int>>()
    private data class PlaybackUi(val button: Button, val seek: SeekBar, val time: TextView)
    private val playbackUi = mutableMapOf<String, PlaybackUi>()
    private val exportRequestCode = 2207
    private val importRequestCode = 2208
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
        pendingExportId = savedInstanceState?.getString("pendingExportId")
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
        root.addView(text("SYNCHRONISATION VERS ORDINATEUR", 14f, true))
        root.addView(text("Désactivée par défaut. Une fois activée, les audios sauvegardés et leurs transcriptions seront envoyés sur votre espace privé en Wi-Fi ; ils resteront disponibles sur le téléphone.", 13f))
        syncStatus = text("Chargement des paramètres…", 13f)
        root.addView(syncStatus)
        root.addView(text("Adresse HTTPS de la bibliothèque (/api/library)", 13f))
        syncEndpointField = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(SyncConfig.url(this@MainActivity))
        }
        root.addView(syncEndpointField)
        root.addView(text("Code personnel de synchronisation (32 caractères minimum)", 13f))
        syncTokenField = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(SyncConfig.token(this@MainActivity))
        }
        root.addView(syncTokenField)
        root.addView(button("Enregistrer les paramètres de synchronisation") {
            val endpoint = syncEndpointField.text.toString().trim()
            val code = syncTokenField.text.toString().trim()
            if (!endpoint.startsWith("https://") || !endpoint.endsWith("/api/library") || code.length < 32)
                toast("Adresse /api/library HTTPS et code de 32 caractères requis")
            else {
                SyncConfig.save(this, endpoint, code)
                toast("Paramètres enregistrés")
                updateSyncUi()
            }
        })
        syncToggle = button("Activer la synchronisation") {
            val enable = !SyncConfig.enabled(this)
            if (enable && (SyncConfig.url(this).isBlank() || SyncConfig.token(this).length < 32)) {
                toast("Enregistrez d'abord l'adresse et le code"); return@button
            }
            SyncConfig.enable(this, enable)
            if (enable) { AudioSync.schedulePeriodic(this); AudioSync.scheduleOnce(this) }
            else AudioSync.cancel(this)
            updateSyncUi()
        }
        root.addView(syncToggle)
        root.addView(button("Synchroniser maintenant") {
            if (!SyncConfig.enabled(this)) { toast("Activez d'abord la synchronisation"); return@button }
            syncStatus.text = "Connexion à la bibliothèque…"
            thread(name = "dictee-manual-sync") {
                val result = runCatching {
                    AudioSync.sync(this) { message -> runOnUiThread { syncStatus.text = message } }
                }
                runOnUiThread {
                    if (result.isFailure) syncStatus.text = "Échec : " + (result.exceptionOrNull()?.message ?: "Erreur")
                }
            }
        })
        updateSyncUi()
        root.addView(text("IMPORTER LES ANCIENNES DICTÉES", 14f, true))
        root.addView(text("Si votre ancienne version possédait un identifiant différent, restaurez ici sa sauvegarde ZIP. L'import n'efface ni ne remplace aucune dictée.", 13f))
        root.addView(button("Importer une sauvegarde (.zip)") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
            }
            startActivityForResult(intent, importRequestCode)
        })
        root.addView(text("MES DICTÉES", 16f, true))
        history = column(); root.addView(history)
        setContentView(scroll)
        store.recoverAbandonedSessions()
        AudioSync.schedulePeriodic(this)
        refreshHistory()
        refreshKeyboardStatus()
        handler.post(object : Runnable {
            override fun run() {
                updateRecordingUi()
                updateRemoteControlsUi()
                val snapshot = store.all().joinToString(";") { "${it.id}:${it.state}:${it.segments.size}:${it.transcript.length}" }
                if (snapshot != lastSnapshot) { lastSnapshot = snapshot; refreshHistory() }
                updatePlaybackUi()
                handler.postDelayed(this, 1300)
            }
        })
        handler.post(object : Runnable {
            override fun run() {
                updatePlaybackUi()
                handler.postDelayed(this, 300)
            }
        })
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingExportId", pendingExportId)
        super.onSaveInstanceState(outState)
    }
    private fun updateSyncUi() {
        syncToggle.text = if (SyncConfig.enabled(this)) "Désactiver la synchronisation"
            else "Activer la synchronisation"
        syncStatus.text = if (SyncConfig.enabled(this)) "Synchronisation activée (Wi-Fi)"
            else "Synchronisation désactivée ; vos audios restent uniquement sur cet appareil."
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
        playbackUi.clear()
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
                val button = button("▶ Écouter") { togglePlayback(item) }
                panel.addView(button)
                val seek = SeekBar(this).apply {
                    max = partDurations(item).sum().coerceAtLeast(1)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) = Unit
                        override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(bar: SeekBar?) { if (bar != null) seekPlayback(item, bar.progress) }
                    })
                }
                panel.addView(seek)
                val time = text("00:00 / " + formatTime(partDurations(item).sum()), 12f)
                panel.addView(time)
                playbackUi[item.id] = PlaybackUi(button, seek, time)
                panel.addView(button(if (item.segments.size == 1) "↓ Télécharger l'audio (.m4a)"
                    else "↓ Télécharger les " + item.segments.size + " parties (.zip)") { askExport(item) })
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
                    .setPositiveButton("Supprimer") { _, _ ->
                        if (playingSessionId == item.id) stopPlayback()
                        store.delete(item); durations.remove(item.id); refreshHistory()
                    }
                    .show()
            })
            history.addView(panel)
        }
        updatePlaybackUi()
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
    private fun partDurations(item: DictationSession): List<Int> =
        durations.getOrPut(item.id) {
            item.segments.indices.map { index ->
                val file = store.segmentFile(item, index)
                if (!file.isFile) return@map 1
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 1)
                        .coerceAtLeast(1)
                } catch (_: Exception) { 1 }
                finally { retriever.release() }
            }
        }

    private fun formatTime(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return "%02d:%02d".format(Locale.FRANCE, seconds / 60, seconds % 60)
    }

    private fun togglePlayback(item: DictationSession) {
        val existing = player
        if (playingSessionId == item.id && existing != null) {
            if (existing.isPlaying) {
                existing.pause()
                playbackPaused = true
            } else {
                existing.start()
                playbackPaused = false
            }
            updatePlaybackUi()
        } else playPart(item, 0)
    }

    private fun playPart(item: DictationSession, index: Int, startMs: Int = 0, autoPlay: Boolean = true) {
        player?.release()
        player = null
        if (index !in item.segments.indices) { stopPlayback(); return }
        try {
            val media = MediaPlayer()
            media.setDataSource(store.segmentFile(item, index).absolutePath)
            media.prepare()
            media.setOnCompletionListener {
                if (playingSessionId == item.id && playingSegment == index && index + 1 < item.segments.size) {
                    playPart(item, index + 1)
                } else stopPlayback()
            }
            media.seekTo(startMs.coerceIn(0, (media.duration - 100).coerceAtLeast(0)))
            if (autoPlay) media.start()
            player = media
            playingSessionId = item.id
            playingSegment = index
            playbackPaused = !autoPlay
        } catch (e: Exception) {
            stopPlayback()
            toast("Lecture impossible : " + e.message)
        }
        updatePlaybackUi()
    }

    private fun seekPlayback(item: DictationSession, offset: Int) {
        val lengths = partDurations(item)
        var remaining = offset.coerceIn(0, lengths.sum().coerceAtLeast(1) - 1)
        for (index in lengths.indices) {
            if (remaining < lengths[index] || index == lengths.lastIndex) {
                val autoPlay = playingSessionId != item.id || !playbackPaused
                if (playingSessionId == item.id && playingSegment == index && player != null) {
                    player?.seekTo(remaining)
                } else playPart(item, index, remaining, autoPlay)
                updatePlaybackUi()
                return
            }
            remaining -= lengths[index]
        }
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        playingSessionId = null
        playingSegment = 0
        playbackPaused = false
        updatePlaybackUi()
    }

    private fun updatePlaybackUi() {
        playbackUi.forEach { (id, controls) ->
            val active = id == playingSessionId && player != null
            controls.button.text = when {
                !active -> "▶ Écouter"
                playbackPaused -> "▶ Reprendre l'écoute"
                else -> "⏸ Pause"
            }
            if (active) {
                val lengths = durations[id].orEmpty()
                val before = lengths.take(playingSegment).sum()
                val position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
                val elapsed = before + position
                controls.seek.progress = elapsed.coerceIn(0, controls.seek.max)
                controls.time.text = formatTime(elapsed) + " / " + formatTime(lengths.sum())
            }
        }
    }

    private fun askExport(item: DictationSession) {
        if (item.segments.isEmpty()) return
        val isSingle = item.segments.size == 1
        pendingExportId = item.id
        val name = "dictee-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date(item.createdAt)) +
            if (isSingle) ".m4a" else ".zip"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (isSingle) "audio/mp4" else "application/zip"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, exportRequestCode)
    }

    @Deprecated("Android Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == importRequestCode) {
            val archive = if (resultCode == RESULT_OK) data?.data else null
            if (archive != null) {
                toast("Import de la sauvegarde en cours…")
                thread(name = "dictee-import") {
                    val result = runCatching { DictationArchive(this).importFrom(archive) }
                    runOnUiThread {
                        if (result.isSuccess) {
                            refreshHistory()
                            toast("Dictées restaurées : " + result.getOrDefault(0))
                            AudioSync.scheduleOnce(this)
                        } else toast("Import impossible : " + (result.exceptionOrNull()?.message ?: "Erreur"))
                    }
                }
            }
            return
        }
        if (requestCode != exportRequestCode) return
        val id = pendingExportId
        pendingExportId = null
        val uri: Uri = data?.data ?: return
        if (resultCode != RESULT_OK || id == null) return
        thread(name = "dictee-export") {
            val result = runCatching {
                val item = store.get(id) ?: error("Enregistrement introuvable")
                require(item.segments.isNotEmpty()) { "Audio indisponible" }
                contentResolver.openOutputStream(uri, "w")?.use { output ->
                    if (item.segments.size == 1) {
                        val file = store.segmentFile(item, 0)
                        require(file.isFile) { "Fichier audio manquant" }
                        file.inputStream().use { it.copyTo(output) }
                    } else {
                        ZipOutputStream(output).use { zip ->
                            item.segments.indices.forEach { index ->
                                val file = store.segmentFile(item, index)
                                require(file.isFile) { "Fragment audio manquant" }
                                zip.putNextEntry(ZipEntry("part-%03d.m4a".format(Locale.ROOT, index + 1)))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                } ?: error("Destination inaccessible")
            }
            runOnUiThread {
                toast(if (result.isSuccess) "Audio enregistré dans le dossier choisi"
                else "Export impossible : " + (result.exceptionOrNull()?.message ?: "Erreur inconnue"))
            }
        }
    }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); player?.release(); player = null; super.onDestroy() }
}