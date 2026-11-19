package fr.denizot.dictee

import java.util.Locale

/** Suggestions et corrections élémentaires, locales et hors ligne. Jamais de texte envoyé au serveur. */
object KeyboardLexicon {
    private val fr = Locale.FRENCH
    private val words = listOf(
        "à", "avec", "ainsi", "alors", "après", "avant", "avoir", "aujourd'hui",
        "bonjour", "bonsoir", "bon", "bonne", "beaucoup", "bien", "bientôt",
        "car", "cela", "ce", "cette", "ces", "chez", "comme", "comment", "comprendre",
        "dans", "de", "des", "donc", "demain", "depuis", "dire", "déjà",
        "elle", "elles", "encore", "entre", "en", "ensuite", "est", "et", "être",
        "faire", "faut", "fois", "français", "france", "grand", "grande",
        "hier", "ici", "il", "ils", "important", "intéressant", "j'ai", "jamais",
        "je", "jour", "journée", "juste", "jusqu'à", "la", "le", "les", "leur",
        "lui", "là", "maintenant", "mais", "merci", "même", "moins", "mon",
        "notre", "nous", "nouveau", "nouvelle", "non", "oui", "où", "on",
        "par", "parce", "parfois", "pas", "peut", "peut-être", "plus", "pour",
        "pourquoi", "prendre", "puis", "quand", "que", "quel", "quelle", "qui",
        "quelque", "rien", "revoir", "répondre", "sans", "se", "sera", "ses",
        "si", "son", "sont", "souvent", "sur", "suis", "super", "suite",
        "temps", "tes", "texte", "ton", "toujours", "tout", "tous", "très",
        "tu", "un", "une", "utiliser", "va", "vais", "vraiment", "vers", "vie",
        "voici", "voilà", "voir", "vous", "votre", "y", "ça", "ça", "était",
        "étude", "écrire", "écrire", "équipe", "évidemment", "également"
    ).distinct()

    private val safeCorrections = mapOf(
        "bonjoru" to "bonjour",
        "bonnjour" to "bonjour",
        "mercie" to "merci",
        "commment" to "comment",
        "comemnt" to "comment",
        "vriament" to "vraiment",
        "aujoudhui" to "aujourd'hui",
        "aujourdhui" to "aujourd'hui",
        "pourqoui" to "pourquoi",
        "peutt" to "peut",
        "cest" to "c'est",
        "detre" to "d'être"
    )

    fun currentWord(text: String): String {
        if (text.isEmpty()) return ""
        return text.takeLast(80).takeLastWhile { it.isLetter() || it == '\'' || it == '’' || it == '-' }
    }

    fun candidates(prefix: String, limit: Int = 3): List<String> {
        val query = prefix.lowercase(fr).trim()
        if (query.length < 2 || query.length > 50) return emptyList()
        val valid = words.filter { it.startsWith(query) && it != query }
        return valid.sortedWith(compareBy<String> { it.length }.thenBy { it }).take(limit)
    }

    fun correction(word: String): String? {
        val corrected = safeCorrections[word.lowercase(fr)] ?: return null
        return if (word.firstOrNull()?.isUpperCase() == true)
            corrected.replaceFirstChar { it.uppercase(fr) } else corrected
    }
}
