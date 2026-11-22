package fr.denizot.dictee

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Explicit opt-in. No audio is uploaded until the user enables cloud sync. */
object SyncConfig {
    private const val PREF = "dictee-private-sync"
    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun url(context: Context): String = prefs(context).getString("url", "https://dictee-universelle-web.vercel.app/api/library") ?: ""
    fun token(context: Context): String = prefs(context).getString("token", "") ?: ""
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", false)
    fun save(context: Context, url: String, token: String) {
        prefs(context).edit().putString("url", url.trim()).putString("token", token.trim()).apply()
    }
    fun enable(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("enabled", value)
            .remove("scheduling_error").apply()
    }
    fun schedulingError(context: Context): String =
        prefs(context).getString("scheduling_error", "") ?: ""
    fun suspendAfterSchedulingError(context: Context) {
        // Never allow an optional network feature to crash the keyboard or main screen.
        prefs(context).edit()
            .putBoolean("enabled", false)
            .putString("scheduling_error",
                "Synchronisation suspendue : Android n'a pas accepté sa planification.")
            .apply()
    }
    fun fingerprint(context: Context, key: String) = prefs(context).getString("sent:" + key, "")
    fun markSent(context: Context, key: String, value: String) {
        prefs(context).edit().putString("sent:" + key, value).apply()
    }
}

object AudioSync {
    private const val JOB_PERIODIC = 2208
    private const val JOB_ONCE = 2209
    private const val SIX_HOURS = 6L * 60L * 60L * 1000L
    fun cancel(context: Context) {
        // A user choosing to stop sync must never lose access to local recordings.
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.cancel(JOB_PERIODIC)
            scheduler.cancel(JOB_ONCE)
        } catch (_: RuntimeException) {
            // Failed cancellation is non-fatal; the scheduled job also checks enabled.
        }
    }
    private fun scheduleSafely(context: Context, info: JobInfo): Boolean {
        return try {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS) true
            else {
                SyncConfig.suspendAfterSchedulingError(context)
                false
            }
        } catch (_: RuntimeException) {
            // Includes SecurityException on Android 14+ if ACCESS_NETWORK_STATE is absent.
            SyncConfig.suspendAfterSchedulingError(context)
            false
        }
    }
    fun schedulePeriodic(context: Context): Boolean {
        if (!SyncConfig.enabled(context)) return false
        val info = JobInfo.Builder(JOB_PERIODIC, ComponentName(context, AudioSyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setPeriodic(SIX_HOURS)
            .build()
        return scheduleSafely(context, info)
    }
    fun scheduleOnce(context: Context): Boolean {
        if (!SyncConfig.enabled(context)) return false
        val info = JobInfo.Builder(JOB_ONCE, ComponentName(context, AudioSyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setMinimumLatency(1000L)
            .build()
        return scheduleSafely(context, info)
    }
    /** Upload finalized fragments first; publish metadata only when every fragment succeeded. */
    fun sync(context: Context, canContinue: () -> Boolean = { true }, report: (String) -> Unit = {}): Int {
        if (!SyncConfig.enabled(context)) return 0
        val endpoint = SyncConfig.url(context)
        val token = SyncConfig.token(context)
        require(endpoint.startsWith("https://") && URL(endpoint).protocol == "https") {
            "Adresse de synchronisation HTTPS requise"
        }
        require(token.length >= 32) { "Code privé invalide" }
        val store = SessionStore(context)
        var completed = 0
        val sessions = store.all().filter {
            it.segments.isNotEmpty() && it.state in setOf("ready", "done", "failed", "interrupted")
        }
        for (item in sessions) {
            if (!canContinue()) break
            for (index in item.segments.indices) {
                if (!canContinue()) return completed
                val file: File = store.segmentFile(item, index)
                require(file.isFile && file.length() in 128..3_300_000) {
                    "Fragment audio absent ou trop volumineux"
                }
                val key = item.id + ":part:" + index
                val fingerprint = file.length().toString() + ":" + file.lastModified()
                if (SyncConfig.fingerprint(context, key) != fingerprint) {
                    report("Envoi de l'audio " + (index + 1) + "/" + item.segments.size)
                    post(endpoint + "?action=upload-audio&id=" + item.id + "&part=" + index,
                        token, "audio/mp4", file)
                    SyncConfig.markSent(context, key, fingerprint)
                }
            }
            val payload = JSONObject().apply {
                put("id", item.id)
                put("createdAt", item.createdAt)
                put("durationMs", item.durationMs)
                put("state", item.state)
                put("source", item.source)
                put("segmentsCount", item.segments.size)
                put("transcript", item.transcript)
            }.toString()
            val key = item.id + ":metadata"
            val fingerprint = payload.hashCode().toString()
            if (SyncConfig.fingerprint(context, key) != fingerprint) {
                report("Synchronisation de la dictée du " + item.createdAt)
                post(endpoint + "?action=upsert", token, "application/json", payload)
                SyncConfig.markSent(context, key, fingerprint)
                completed++
            }
        }
        report("Synchronisation terminée · " + completed + " mise(s) à jour")
        return completed
    }
    private fun post(endpoint: String, token: String, contentType: String, file: File) {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer " + token)
            connection.setRequestProperty("Content-Type", contentType)
            connection.setFixedLengthStreamingMode(file.length())
            connection.outputStream.use { output -> file.inputStream().use { it.copyTo(output, 32_768) } }
            verify(connection)
        } finally { connection.disconnect() }
    }
    private fun post(endpoint: String, token: String, contentType: String, json: String) {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer " + token)
            connection.setRequestProperty("Content-Type", contentType)
            val bytes = json.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            verify(connection)
        } finally { connection.disconnect() }
    }
    private fun verify(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code !in 200..299) {
            val details = connection.errorStream?.bufferedReader()?.use { it.readText().take(220) } ?: ""
            error("Serveur HTTP " + code + ": " + details)
        }
    }
}

class AudioSyncJob : JobService() {
    @Volatile private var cancelled = false
    override fun onStartJob(params: JobParameters): Boolean {
        cancelled = false
        thread(name = "dictee-sync") {
            var retry = false
            try { AudioSync.sync(this, { !cancelled }) }
            catch (_: Exception) { retry = true }
            jobFinished(params, retry && !cancelled)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        cancelled = true
        return SyncConfig.enabled(this)
    }
}
