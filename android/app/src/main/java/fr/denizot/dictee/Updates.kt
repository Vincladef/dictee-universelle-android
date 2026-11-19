package fr.denizot.dictee

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vérification périodique, téléchargement et validation de l'APK.
 * L'installation reste soumise à confirmation par Android.
 */
object Updates {
    private const val MANIFEST_URL = "https://raw.githubusercontent.com/Vincladef/dictee-universelle-updates/main/latest.json"
    private const val RELEASE_PREFIX = "https://github.com/Vincladef/dictee-universelle-updates/releases/download/"
    private const val CHANNEL = "dictee-updates"
    private const val JOB_ID = 5319
    private const val NOTIF_ID = 312
    private const val CHECK_INTERVAL = 6L * 60L * 60L * 1000L
    private const val MAX_APK_BYTES = 40_000_000L
    private val busy = AtomicBoolean(false)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dictee-updates", Context.MODE_PRIVATE)
    private fun directory(ctx: Context) = File(ctx.filesDir, "updates").apply { mkdirs() }

    private fun versionCode(ctx: Context): Long {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    fun ready(ctx: Context): Pair<String, File>? {
        val p = prefs(ctx)
        val code = p.getLong("code", 0)
        val version = p.getString("version", "") ?: ""
        val file = File(directory(ctx), "dictee-v${code}.apk")
        return if (code > versionCode(ctx) && file.isFile && file.length() > 100_000) Pair(version, file) else null
    }

    fun schedule(ctx: Context) {
        val scheduler = ctx.getSystemService(JobScheduler::class.java)
        scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, UpdateCheckJob::class.java))
            .setPeriodic(12L * 60L * 60L * 1000L)
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
    }

    fun check(ctx: Context, force: Boolean = false, onDone: ((String) -> Unit)? = null) {
        if (!busy.compareAndSet(false, true)) {
            onDone?.invoke("Vérification déjà en cours")
            return
        }
        val app = ctx.applicationContext
        Thread({
            val result = try { fetchUpdate(app, force) }
            catch (e: Exception) { "Mise à jour indisponible : " + (e.message ?: "connexion") }
            finally { busy.set(false) }
            Handler(Looper.getMainLooper()).post { onDone?.invoke(result) }
        }, "ota-check").start()
    }

    private fun fetchUpdate(ctx: Context, force: Boolean): String {
        val p = prefs(ctx)
        if (!force && System.currentTimeMillis() - p.getLong("lastCheck", 0) < CHECK_INTERVAL) {
            return if (ready(ctx) != null) "Mise à jour prête à installer" else "Vérification automatique activée"
        }
        val connection = URL(MANIFEST_URL).openConnection() as HttpURLConnection
        val data = try {
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            require(connection.responseCode == 200) { "Serveur de versions inaccessible" }
            val bytes = connection.inputStream.use { stream ->
                val buffer = ByteArray(8193)
                var offset = 0
                while (offset < buffer.size) {
                    val n = stream.read(buffer, offset, buffer.size - offset)
                    if (n == -1) break
                    offset += n
                }
                require(offset <= 8192) { "Manifeste non valide" }
                JSONObject(String(buffer, 0, offset, Charsets.UTF_8))
            }
            bytes
        } finally { connection.disconnect() }

        val code = data.optLong("versionCode", 0L)
        val version = data.optString("versionName").take(30)
        if (code <= versionCode(ctx)) {
            p.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
            return "Application à jour"
        }
        val link = data.optString("apkUrl")
        val checksum = data.optString("sha256").lowercase()
        require(link.startsWith(RELEASE_PREFIX) && Regex(
            """^https://github[.]com/Vincladef/dictee-universelle-updates/releases/download/v[0-9]+[.][0-9]+[.][0-9]+/dictee-universelle[.]apk$"""
        ).matches(link)) { "Source de mise à jour non approuvée" }
        require(Regex("[a-f0-9]{64}").matches(checksum)) { "Empreinte SHA-256 manquante" }

        val dest = File(directory(ctx), "dictee-v${code}.apk")
        if (!dest.isFile || hash(dest) != checksum) {
            val temp = File(directory(ctx), "download.tmp")
            temp.delete()
            try {
                val conn = URL(link).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    require(conn.responseCode == 200) { "Téléchargement impossible" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(temp).use { output ->
                            val buf = ByteArray(32768)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                require(total <= MAX_APK_BYTES) { "Fichier trop volumineux" }
                                output.write(buf, 0, n)
                                digest.update(buf, 0, n)
                            }
                            output.fd.sync()
                        }
                    }
                    require(total > 100_000 && hex(digest.digest()) == checksum) { "Téléchargement corrompu" }
                    if (dest.exists()) dest.delete()
                    require(temp.renameTo(dest)) { "Écriture impossible" }
                } finally { conn.disconnect() }
            } finally { temp.delete() }
        }
        p.edit().putLong("lastCheck", System.currentTimeMillis())
            .putLong("code", code).putString("version", version).apply()
        directory(ctx).listFiles()?.filter { it != dest && it.name.endsWith(".apk") }?.forEach { it.delete() }
        notifyUpdate(ctx, version)
        return "Version ${version} téléchargée ; confirmation Android nécessaire"
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(32768)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest())
    }

    private fun notifyUpdate(ctx: Context, version: String) {
        val manager = ctx.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "Mises à jour du clavier", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pending = PendingIntent.getActivity(ctx, 1,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIF_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Dictée universelle ${version}")
            .setContentText("Nouvelle version récupérée ; toucher pour installer")
            .setContentIntent(pending).setAutoCancel(true).build())
    }

    fun install(activity: Activity): String {
        val item = ready(activity) ?: return "Aucune mise à jour disponible"
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")))
            return "Autorisez la mise à jour, puis appuyez à nouveau sur Installer"
        }
        return try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", item.second)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
            "Confirmez le remplacement dans la fenêtre Android"
        } catch (e: Exception) { "Installation impossible : " + (e.message ?: "erreur") }
    }
}

class UpdateCheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Updates.check(this, force = true) { jobFinished(params, false) }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean = false
}
