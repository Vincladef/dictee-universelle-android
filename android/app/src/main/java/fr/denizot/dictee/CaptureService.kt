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
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import java.io.File

/** A user-started microphone foreground service; audio keeps recording after screen lock. */
class CaptureService : Service() {
    companion object {
        const val START = "fr.denizot.dictee.START"
        const val STOP = "fr.denizot.dictee.STOP"
        const val CHANNEL = "microphone"
        const val NOTIFICATION_ID = 81
        @Volatile var active = false
        @Volatile var lastError = ""
    }
    private val store by lazy { SessionStore(this) }
    private var current: DictationSession? = null
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var startedElapsed: Long = 0
    private var terminating = false

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Enregistrement audio", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CaptureService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Dictée en cours").setContentText("L'enregistrement continue écran verrouillé")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Terminer", stop).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stopRecording()
            START -> if (!active) startRecording(intent.getStringExtra("source") ?: "app")
        }
        return if (active) START_NOT_STICKY else START_NOT_STICKY
    }
    private fun startRecording(source: String) {
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                lastError = "Autorisation microphone manquante"; stopSelf(); return
            }
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(NOTIFICATION_ID, notification())
            lastError = ""; terminating = false
            current = store.newSession(source)
            startedElapsed = SystemClock.elapsedRealtime()
            startPart()
            active = true
        } catch (e: Exception) {
            lastError = "Enregistrement impossible : ${e.message}"
            current?.let { it.state = "interrupted"; it.error = lastError; store.save(it) }
            releaseRecorder(); active = false; stopSelf()
        }
    }
    private fun startPart() {
        val session = current ?: return
        val file = store.segmentFile(session, session.segments.size)
        currentFile = file
        val media = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        media.setAudioSource(MediaRecorder.AudioSource.MIC)
        media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        media.setAudioChannels(1)
        media.setAudioSamplingRate(16000)
        media.setAudioEncodingBitRate(64000)
        media.setOutputFile(file.absolutePath)
        media.setMaxDuration(180000) // ~1.5 MB audio/segment, under Vercel's request limit.
        media.setOnInfoListener { _, what, _ ->
            if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) rotatePart()
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
        } catch (e: Exception) {
            lastError = "Interruption du microphone : ${e.message}"
            current?.let { it.error = lastError }
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
        try {
            finalizePart()
            current?.let { session ->
                session.durationMs = SystemClock.elapsedRealtime() - startedElapsed
                session.state = if (interrupted || session.segments.isEmpty()) "interrupted" else "ready"
                if (session.segments.isEmpty() && session.error.isEmpty()) session.error = "Aucun segment exploitable"
                store.save(session)
            }
        } catch (e: Exception) {
            lastError = "Finalisation incomplète : ${e.message}"
            current?.let { it.state = "interrupted"; it.error = lastError; store.save(it) }
        } finally {
            active = false; current = null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    override fun onDestroy() {
        if (active && !terminating) stopRecording(interrupted = true)
        super.onDestroy()
    }
}