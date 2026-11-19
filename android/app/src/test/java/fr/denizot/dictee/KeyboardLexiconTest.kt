package fr.denizot.dictee

import org.junit.Assert.*
import org.junit.Test

class KeyboardLexiconTest {
    @Test fun currentWordHandlesAccentsAndPunctuation() {
        assertEquals("bonj", KeyboardLexicon.currentWord("Salut, bonj"))
        assertEquals("aujourd'hui", KeyboardLexicon.currentWord("Et aujourd'hui"))
        assertEquals("", KeyboardLexicon.currentWord("Bonjour ! "))
    }

    @Test fun suggestionsAreLocalAndLimited() {
        val suggestions = KeyboardLexicon.candidates("bon", 3)
        assertTrue(suggestions.contains("bonjour"))
        assertTrue(suggestions.size <= 3)
        assertTrue(KeyboardLexicon.candidates("x").isEmpty())
    }

    @Test fun correctionIsConservativeAndCaseSensitive() {
        assertEquals("bonjour", KeyboardLexicon.correction("bonjoru"))
        assertEquals("Bonjour", KeyboardLexicon.correction("Bonjoru"))
        assertEquals(null, KeyboardLexicon.correction("Denizot"))
    }
}
