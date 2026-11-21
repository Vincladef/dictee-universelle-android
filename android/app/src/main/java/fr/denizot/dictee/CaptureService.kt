package fr.denizot.dictee

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.KeyEvent
import java.io.File

/** Service audio unique : PAUSE conserve la session, STOP sauvegarde sans transcrire. */
class CaptureService : Service() {
    companion object {
        const val START = "fr.denizot.dictee.START"
        const val PAUSE = "fr.denizot.dictee.PAUSE"
        const val RESUME = "fr.denizot.dictee.RESUME"
        const val STOP = "fr.denizot.dictee.STOP"
        const val CHANNEL = "microphone"
        const val NOTIFICATION_ID = 81

        @Volatile var active = false
            private set
        @Volatile var paused = false
            private set
        @Volatile var sessionId: String? = null
            private set
        @Volatile var startedElapsedMs = 0L
            private set
        @Volatile var pausedElapsedMs = 0L
            private set
        @Volatile var pauseStartedElapsedMs = 0L
            private set
        @Volatile var lastError = ""
            private set

        fun recordedDurationMs(): Long {
            if (!active || startedElapsedMs == 0L) return 0L
            val now = if (paused) pauseStartedElapsedMs else SystemClock.elapsedRealtime()
            return (now - startedElapsedMs - pausedElapsedMs).coerceAtLeast(0L)
        }
    }

    private val store by lazy { SessionStore(this) }
    private var current: DictationSession? = null
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var terminating = false
    private var mediaSession: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Dictée en cours", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun pending(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, CaptureService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val isPaused = paused
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (isPaused) "Dictée en pause" else "Dictée en cours")
            .setContentText(
                if (isPaused) "Reprendre ou terminer depuis la notification"
                else "L'enregistrement continue avec l'écran verrouillé"
            )
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                    if (isPaused) "Reprendre" else "Pause",
                    if (isPaused) pending(RESUME, 2) else pending(PAUSE, 1)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next, "Arrêter et sauvegarder", pending(STOP, 3)
                ).build()
            )
            .build()
    }

    /**
     * MediaSession receives wired/Bluetooth headset transport controls even when the
     * keyboard is hidden and the screen is locked. The service remains the only
     * authority for the recorder state. A music player may take media-key priority.
     */
    private fun createMediaSession() {
        if (mediaSession != null) return
        mediaSession = MediaSession(this, "Dictee microphone").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val key = if (Build.VERSION.SDK_INT >= 33) {
                        mediaButtonIntent.getParcelableExtra(
                            Intent.EXTRA_KEY_EVENT, KeyEvent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    } ?: return super.onMediaButtonEvent(mediaButtonIntent)
                    val supported = key.keyCode == KeyEvent.KEYCODE_HEADSETOOK ||
                        key.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                        key.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
                        key.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                    if (!supported) return super.onMediaButtonEvent(mediaButtonIntent)
                    // A press is a pair of ACTION_DOWN / ACTION_UP. Never toggle twice.
                    if (key.action != KeyEvent.ACTION_DOWN || key.repeatCount > 0) return true
                    when (key.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> resumeRecording()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> pauseRecording()
                        else -> if (paused) resumeRecording() else pauseRecording()
                    }
                    return true
                }

                override fun onPlay() = resumeRecording()
                override fun onPause() = pauseRecording()
            })
            isActive = true
        }
        updateMediaSessionState()
    }

    private fun updateMediaSessionState() {
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE
                )
                .setState(
                    if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (paused) 0f else 1f
                )
                .build()
        )
    }

    private fun releaseMediaSession() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (!active && !terminating) startRecording(intent.getStringExtra("source") ?: "app")
            PAUSE -> pauseRecording()
            RESUME -> resumeRecording()
            STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    @Synchronized private fun startRecording(source: String) {
        if (active) return
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                lastError = "Autorisation microphone manquante"
                stopSelf()
                return
            }
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            lastError = ""
            terminating = false
            paused = false
            pausedElapsedMs = 0L
            pauseStartedElapsedMs = 0L
            current = store.newSession(source)
            sessionId = current?.id
            startedElapsedMs = SystemClock.elapsedRealtime()
            startPart()
            active = true
            createMediaSession()
            updateNotification()
        } catch (e: Exception) {
            lastError = "Enregistrement impossible : " + (e.message ?: "erreur")
            current?.let { it.state = "interrupted"; it.error = lastError; store.save(it) }
            releaseRecorder()
            releaseMediaSession()
            clearStatus()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @Synchronized private fun pauseRecording() {
        if (!active || paused || terminating) return
        try {
            recorder?.pause() ?: error("Enregistreur indisponible")
            pauseStartedElapsedMs = SystemClock.elapsedRealtime()
            paused = true
            updateMediaSessionState()
            current?.let { it.state = "paused"; store.save(it) }
            updateNotification()
        } catch (e: Exception) {
            lastError = "Pause impossible : " + (e.message ?: "erreur")
        }
    }

    @Synchronized private fun resumeRecording() {
        if (!active || !paused || terminating) return
        try {
            recorder?.resume() ?: error("Enregistreur indisponible")
            pausedElapsedMs += (SystemClock.elapsedRealtime() - pauseStartedElapsedMs).coerceAtLeast(0)
            pauseStartedElapsedMs = 0L
            paused = false
            updateMediaSessionState()
            current?.let { it.state = "recording"; store.save(it) }
            updateNotification()
        } catch (e: Exception) {
            lastError = "Reprise impossible : " + (e.message ?: "erreur")
        }
    }

    private fun startPart() {
        val session = current ?: return
        val file = store.segmentFile(session, session.segments.size)
        currentFile = file
        val media = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
            else @Suppress("DEPRECATION") MediaRecorder()
        media.setAudioSource(MediaRecorder.AudioSource.MIC)
        media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        media.setAudioChannels(1)
        media.setAudioSamplingRate(16000)
        media.setAudioEncodingBitRate(64000)
        media.setOutputFile(file.absolutePath)
        media.setMaxDuration(180000)
        media.setOnInfoListener { _, event, _ ->
            if (event == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) rotatePart()
        }
        media.prepare()
        media.start()
        recorder = media
    }

    private fun releaseRecorder() {
        val media = recorder
        recorder = null
        try { media?.stop() } catch (_: Exception) { currentFile?.delete() }
        try { media?.reset(); media?.release() } catch (_: Exception) {}
    }

    @Synchronized private fun rotatePart() {
        if (terminating) return
        try {
            finalizePart()
            startPart()
            if (paused) recorder?.pause()
        } catch (e: Exception) {
            lastError = "Interruption du microphone : " + (e.message ?: "erreur")
            current?.error = lastError
            stopRecording(interrupted = true)
        }
    }

    private fun finalizePart() {
        val file = currentFile
        releaseRecorder()
        if (file?.isFile == true && file.length() >= 128) {
            current?.let { it.segments.add(file.name); store.save(it) }
        } else file?.delete()
        currentFile = null
    }

    @Synchronized private fun stopRecording(interrupted: Boolean = false) {
        if (terminating) return
        terminating = true
        val duration = recordedDurationMs()
        try {
            finalizePart()
            current?.let { session ->
                session.durationMs = duration
                session.state = if (interrupted || session.segments.isEmpty()) "interrupted" else "ready"
                if (session.segments.isEmpty() && session.error.isBlank()) session.error = "Aucun audio exploitable"
                store.save(session)
            }
        } catch (e: Exception) {
            lastError = "Sauvegarde incomplète : " + (e.message ?: "erreur")
            current?.let { it.state = "interrupted"; it.error = lastError; store.save(it) }
        } finally {
            releaseMediaSession()
            clearStatus()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun clearStatus() {
        active = false
        paused = false
        sessionId = null
        startedElapsedMs = 0L
        pausedElapsedMs = 0L
        pauseStartedElapsedMs = 0L
        current = null
    }

    override fun onDestroy() {
        if (active && !terminating) stopRecording(interrupted = true)
        releaseMediaSession()
        super.onDestroy()
    }
}
