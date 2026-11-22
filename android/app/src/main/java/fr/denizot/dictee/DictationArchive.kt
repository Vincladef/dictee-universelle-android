package fr.denizot.dictee

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * One-time import from the former debug APK's private files, exported over ADB.
 * Never overwrites a local session. On invalid archives, nothing is committed.
 */
class DictationArchive(private val context: Context) {
    private val safeEntry = Regex("^(?:dictations/)?(d_[0-9]+_[a-f0-9]{8})/(metadata[.]json|part-[0-9]{3}[.]m4a)$")
    private val audioName = Regex("^part-[0-9]{3}[.]m4a$")
    private val maxEntryBytes = 10_000_000L
    private val maxTotalBytes = 1_000_000_000L
    private val maxEntries = 5_000

    fun importFrom(uri: Uri): Int {
        val stage = File(context.cacheDir, "dictee-import-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val seen = mutableSetOf<String>()
            var total = 0L
            var count = 0
            val input = context.contentResolver.openInputStream(uri) ?: error("Archive illisible")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) { zip.closeEntry(); continue }
                    val match = safeEntry.matchEntire(entry.name)
                        ?: error("Fichier inattendu dans la sauvegarde : " + entry.name.take(90))
                    require(seen.add(entry.name)) { "Nom de fichier dupliqué dans l'archive" }
                    require(++count <= maxEntries) { "Archive contenant trop de fichiers" }
                    val dir = File(stage, match.groupValues[1]).apply { mkdirs() }
                    val file = File(dir, match.groupValues[2])
                    var fileSize = 0L
                    file.outputStream().buffered().use { output ->
                        val bytes = ByteArray(32_768)
                        while (true) {
                            val length = zip.read(bytes)
                            if (length < 0) break
                            fileSize += length
                            total += length
                            require(fileSize <= maxEntryBytes && total <= maxTotalBytes) {
                                "Sauvegarde trop volumineuse"
                            }
                            output.write(bytes, 0, length)
                        }
                    }
                    zip.closeEntry()
                }
            }
            val folders = stage.listFiles().orEmpty().filter { it.isDirectory }
            require(folders.isNotEmpty()) { "Aucune dictée reconnue dans cette archive" }
            for (folder in folders) {
                val metadata = File(folder, "metadata.json")
                require(metadata.isFile && metadata.length() <= 200_000) {
                    "Métadonnées absentes ou invalides : " + folder.name
                }
                val json = JSONObject(metadata.readText())
                require(json.optString("id") == folder.name && json.optLong("createdAt") > 0) {
                    "Identification de dictée invalide"
                }
                val parts = json.optJSONArray("segments")
                if (parts != null) {
                    require(parts.length() <= 1_000) { "Enregistrement contenant trop de parties" }
                    for (index in 0 until parts.length()) {
                        val filename = parts.optString(index)
                        require(audioName.matches(filename) && File(folder, filename).isFile) {
                            "Un fragment audio est manquant dans la sauvegarde"
                        }
                    }
                }
            }
            val target = File(context.filesDir, "dictations").apply { mkdirs() }
            var imported = 0
            for (folder in folders) {
                val output = File(target, folder.name)
                if (output.exists()) continue // Preserve local originals unconditionally.
                require(folder.renameTo(output)) { "Impossible de restaurer " + folder.name }
                imported++
            }
            return imported
        } finally {
            stage.deleteRecursively()
        }
    }
}
