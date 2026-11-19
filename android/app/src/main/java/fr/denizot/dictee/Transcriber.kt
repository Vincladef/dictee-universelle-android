package fr.denizot.dictee

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Runs only after recording; sends bounded compressed audio segments to the user's server. */
class Transcriber(private val context: Context) {
    private val store = SessionStore(context)

    fun transcribe(id: String, onProgress: (String) -> Unit = {}): String {
        val item = store.get(id) ?: error("Dictée introuvable")
        require(item.state != "recording") { "Terminez d'abord l'enregistrement" }
        require(item.segments.isNotEmpty()) { "Aucun enregistrement disponible" }
        val endpoint = DictationSettings.endpoint(context)
        require(endpoint.startsWith("https://", ignoreCase = true)) { "Une adresse HTTPS est obligatoire" }
        val token = DictationSettings.token(context)
        require(token.length >= 20) { "Renseignez d'abord le code d'accès au serveur dans les paramètres" }
        item.state = "transcribing"; item.error = ""; store.save(item)
        try {
            for (index in item.segments.indices) {
                if (index < item.segmentTexts.size && item.segmentTexts[index].isNotBlank()) continue
                onProgress("Transcription du segment ${index + 1}/${item.segments.size}")
                val file = store.segmentFile(item, index)
                require(file.isFile && file.length() > 128 && file.length() <= 3_000_000) {
                    "Segment ${index + 1} manquant ou trop volumineux"
                }
                val connection = (URL(endpoint).openConnection() as HttpURLConnection)
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 20000; connection.readTimeout = 60000
                    connection.doOutput = true
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.setRequestProperty("Content-Type", "audio/mp4")
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setFixedLengthStreamingMode(file.length())
                    connection.outputStream.use { output -> file.inputStream().use { it.copyTo(output, 32768) } }
                    val status = connection.responseCode
                    val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                    val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
                    if (status !in 200..299) error(json.optString("error", "Serveur HTTP $status"))
                    val text = json.optString("text").trim()
                    require(text.isNotBlank()) { "Le serveur n'a renvoyé aucun texte" }
                    while (item.segmentTexts.size <= index) item.segmentTexts.add("")
                    item.segmentTexts[index] = text
                    store.save(item) // Resume safely without billing completed segments again.
                } finally { connection.disconnect() }
            }
            item.transcript = item.segmentTexts.joinToString(" ").trim()
            item.state = "done"; store.save(item)
            onProgress("Transcription terminée")
            return item.transcript
        } catch (e: Exception) {
            item.state = "failed"; item.error = e.message ?: "Erreur de transcription"; store.save(item)
            throw e
        }
    }
}