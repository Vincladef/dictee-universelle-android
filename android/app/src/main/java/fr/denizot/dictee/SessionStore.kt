package fr.denizot.dictee

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Private app storage only, no database or cloud audio backup. */
data class DictationSession(
    val id: String,
    val createdAt: Long,
    var source: String,
    var state: String = "recording",
    var durationMs: Long = 0,
    var transcript: String = "",
    var error: String = "",
    val segments: MutableList<String> = mutableListOf(),
    val segmentTexts: MutableList<String> = mutableListOf()
)

class SessionStore(private val context: Context) {
    private val root = File(context.filesDir, "dictations").apply { mkdirs() }
    fun folder(session: DictationSession): File = File(root, session.id).apply { mkdirs() }
    fun newSession(source: String): DictationSession {
        val item = DictationSession("d_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().take(8), System.currentTimeMillis(), source)
        save(item)
        return item
    }
    fun save(item: DictationSession) {
        val json = JSONObject().apply {
            put("id", item.id); put("createdAt", item.createdAt); put("source", item.source)
            put("state", item.state); put("durationMs", item.durationMs)
            put("transcript", item.transcript); put("error", item.error)
            put("segments", JSONArray(item.segments)); put("segmentTexts", JSONArray(item.segmentTexts))
        }
        val dir = folder(item)
        val temp = File(dir, "metadata.tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        val target = File(dir, "metadata.json")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }
    fun get(id: String): DictationSession? {
        if (!id.matches(Regex("d_[0-9]+_[a-f0-9]{8}"))) return null
        val file = File(File(root, id), "metadata.json")
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            fun strings(key: String): MutableList<String> {
                val a = json.optJSONArray(key) ?: JSONArray()
                return MutableList(a.length()) { a.optString(it) }
            }
            DictationSession(
                json.getString("id"), json.getLong("createdAt"), json.optString("source", "app"),
                json.optString("state", "interrupted"), json.optLong("durationMs"),
                json.optString("transcript"), json.optString("error"), strings("segments"), strings("segmentTexts")
            )
        }.getOrNull()
    }
    fun all(): List<DictationSession> = root.listFiles().orEmpty()
        .filter { it.isDirectory }.mapNotNull { get(it.name) }.sortedByDescending { it.createdAt }
    fun delete(item: DictationSession) { folder(item).deleteRecursively() }
    fun latest(): DictationSession? = all().firstOrNull()
    fun segmentFile(item: DictationSession, index: Int) = File(folder(item), "part-%03d.m4a".format(index))
}

object DictationSettings {
    const val DEFAULT_ENDPOINT = "https://atelier-bd-chi.vercel.app/api/android-dictation"
    private const val NAME = "dictation-settings"
    fun endpoint(context: Context): String = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        .getString("endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
    fun token(context: Context): String = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        .getString("token", "") ?: ""
    fun save(context: Context, endpoint: String, token: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putString("endpoint", endpoint.trim()).putString("token", token.trim()).apply()
    }
}