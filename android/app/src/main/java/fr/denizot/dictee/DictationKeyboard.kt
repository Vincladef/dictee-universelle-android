package fr.denizot.dictee

import android.Manifest
import android.content.Intent
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Clavier AZERTY ergonomique, utilisable sans dictée.
 * Le service microphone et le traitement IA sont indépendants de l'IME.
 * Toute transcription est lancée uniquement par un appui sur « Transcrire ».
 */
class DictationKeyboard : InputMethodService() {
    private val store by lazy { SessionStore(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val locale = Locale.FRENCH

    private var root: LinearLayout? = null
    private var info: TextView? = null
    private var actions: LinearLayout? = null
    private var suggestions: LinearLayout? = null
    private var keys: LinearLayout? = null
    private var recordButton: Button? = null
    private var pauseButton: Button? = null
    private var stopButton: Button? = null
    private var transcribeButton: Button? = null
    private var insertButton: Button? = null

    private enum class Page { LETTERS, SYMBOLS, EMOJI, NUMBERS }
    private enum class Shift { OFF, ONCE, LOCK }
    private var page = Page.LETTERS
    private var shift = Shift.OFF
    private var lastShiftTap = 0L
    private var startingAt = 0L
    private var finishing = false
    private var transcribing = false
    private var status = "Dictez ou écrivez votre message"
    private var lastSnapshot = ""
    private var lastSuggestions = ""
    private var pendingInsertId: String? = null
    private var inputGeneration = 0L
    private var keyPreview: PopupWindow? = null
    private val idleStatus = "Dictez ou écrivez votre message"

    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val background get() = if (dark) Color.rgb(34, 37, 44) else Color.rgb(232, 235, 241)
    private val keyColor get() = if (dark) Color.rgb(65, 68, 76) else Color.rgb(253, 253, 255)
    private val utilityColor get() = if (dark) Color.rgb(82, 87, 98) else Color.rgb(208, 215, 227)
    private val foreground get() = if (dark) Color.WHITE else Color.rgb(29, 32, 38)
    private val secondary get() = if (dark) Color.rgb(184, 192, 206) else Color.rgb(96, 105, 120)
    private val primary get() = if (dark) Color.rgb(109, 151, 241) else Color.rgb(56, 104, 198)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun bg(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(7).toFloat()
    }

    private fun line(target: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        target.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    /** Fond reactif natif : il se colore des le premier contact, sans attendre le clic. */
    private fun touchBackground(base: Int): StateListDrawable = StateListDrawable().apply {
        val pressed = when (base) {
            primary -> if (dark) Color.rgb(142, 180, 255) else Color.rgb(83, 135, 226)
            else -> if (dark) Color.rgb(108, 116, 131) else Color.rgb(182, 194, 216)
        }
        addState(intArrayOf(android.R.attr.state_pressed), bg(pressed))
        addState(intArrayOf(), bg(base))
    }

    private fun hidePreview() {
        keyPreview?.dismiss()
        keyPreview = null
    }

    private fun showPreview(anchor: View, letter: String) {
        hidePreview()
        val bubble = TextView(this).apply {
            text = letter
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = bg(primary)
        }
        val popup = PopupWindow(bubble, dp(54), dp(60), false).apply {
            isClippingEnabled = false
            elevation = dp(5).toFloat()
        }
        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val x = (location[0] + (anchor.width - dp(54)) / 2)
            .coerceIn(0, (resources.displayMetrics.widthPixels - dp(54)).coerceAtLeast(0))
        val y = (location[1] - dp(65)).coerceAtLeast(0)
        try {
            popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
            keyPreview = popup
        } catch (_: Exception) {
            popup.dismiss() // Le champ peut perdre le focus pendant un changement de clavier.
        }
    }

    private fun tactileDown(view: View, label: String, preview: Boolean = true) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        view.playSoundEffect(SoundEffectConstants.CLICK) // Suit les preferences sonores Android.
        if (preview && label.length == 1 && label[0].isLetterOrDigit()) {
            showPreview(view, label)
        }
    }

    private fun key(
        row: LinearLayout, title: String, width: Float = 1f,
        special: Boolean = false, accent: Boolean = false, height: Int = 47,
        action: () -> Unit
    ): Button {
        val button = Button(this).apply {
            text = title
            contentDescription = title
            isAllCaps = false
            textSize = when {
                title.length > 11 -> 10.5f
                title.length > 6 -> 12f
                title.length > 3 -> 14f
                else -> 19f
            }
            setTextColor(this@DictationKeyboard.foreground)
            minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0)
            backgroundTintList = null
            background = touchBackground(if (accent) primary else if (special) utilityColor else keyColor)
            if (accent) setTextColor(Color.WHITE)
            stateListAnimator = null
            elevation = 0f
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> tactileDown(view, title)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> hidePreview()
                }
                false // Laisse Android declencher le clic et les actions d'accessibilite.
            }
            setOnClickListener { action() }
        }
        row.addView(button, LinearLayout.LayoutParams(0, dp(height), width).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        })
        return button
    }

    private fun title(value: String, target: LinearLayout) = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(secondary)
        setPadding(dp(9), dp(2), dp(5), dp(3))
        target.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun status(value: String) {
        status = value
        info?.text = value
        info?.visibility = if (value == idleStatus) View.GONE else View.VISIBLE
    }

    private fun restoreIdleStatus() {
        handler.postDelayed({
            if (!CaptureService.active && !transcribing &&
                status.startsWith("Texte inséré")) {
                status(idleStatus)
                updateControls()
            }
        }, 2200L)
    }

    /** Passwords are typeable; only reading, learning and voice insertion are blocked. */
    private fun sensitive(): Boolean {
        val editor = currentInputEditorInfo ?: return false
        val kind = editor.inputType and InputType.TYPE_MASK_CLASS
        val variation = editor.inputType and InputType.TYPE_MASK_VARIATION
        return (kind == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun editable() = currentInputConnection
    private fun enterText(value: String) {
        editable()?.commitText(value, 1)
        if (value == "\n" && page == Page.LETTERS && !sensitive() && shift != Shift.LOCK) {
            shift = Shift.ONCE
            buildKeys()
        }
        refreshSuggestions()
    }

    override fun onCreateInputView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(this@DictationKeyboard.background)
            setPadding(dp(3), dp(2), dp(3), dp(4))
        }
        root = container
        store.recoverAbandonedSessions()
        info = TextView(this).apply {
            text = status
            textSize = 11f
            setTextColor(secondary)
            setPadding(dp(9), 0, dp(6), dp(3))
            maxLines = 1
            visibility = View.GONE
        }
        container.addView(info)
        actions = line(container)
        buildActions()
        suggestions = line(container)
        keys = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(keys)
        buildKeys()
        updateControls()
        return container
    }

    private fun buildActions() {
        val bar = actions ?: return
        bar.removeAllViews()
        recordButton = key(bar, "🎙", 1.1f, accent = true, height = 43) { startRecording() }
            .apply { contentDescription = "Demarrer la dictee"; tooltipText = "Dicter" }
        pauseButton = key(bar, "⏸", 1f, special = true, height = 43) {
            if (CaptureService.paused) command(CaptureService.RESUME)
            else command(CaptureService.PAUSE)
        }.apply { contentDescription = "Mettre en pause ou reprendre"; tooltipText = "Pause / Reprendre" }
        stopButton = key(bar, "■", 1f, special = true, height = 43) {
            finishing = true
            status("Sauvegarde de l'audio…")
            command(CaptureService.STOP)
        }.apply { contentDescription = "Arreter et sauvegarder l'audio"; tooltipText = "Arreter" }
        transcribeButton = key(bar, "✍", 1.2f, accent = true, height = 43) {
            transcribeLatest()
        }.apply { contentDescription = "Transcrire et inserer automatiquement"; tooltipText = "Transcrire et inserer" }
        insertButton = key(bar, "⇥", 1f, accent = true, height = 43) {
            insertLatest()
        }.apply { contentDescription = "Inserer le texte dans le champ actif"; tooltipText = "Inserer le texte" }
        key(bar, "▤", 0.95f, special = true, height = 43) {
            val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = manager.primaryClip
            val value = if (clip != null && clip.itemCount > 0)
                clip.getItemAt(0).coerceToText(this).toString() else ""
            if (value.isNotEmpty()) enterText(value) else status("Presse-papiers vide")
        }.apply { contentDescription = "Coller depuis le presse-papiers"; tooltipText = "Presse-papiers" }
        key(bar, "⚙", 0.95f, special = true, height = 43) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.apply { contentDescription = "Parametres de dictee"; tooltipText = "Parametres" }
        key(bar, "🌐", 0.95f, special = true, height = 43) {
            try { switchToNextInputMethod(false) }
            catch (_: Exception) {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showInputMethodPicker()
            }
        }.apply { contentDescription = "Changer de clavier"; tooltipText = "Changer de clavier" }
    }

    private fun buildKeys() {
        val box = keys ?: return
        box.removeAllViews()
        when (page) {
            Page.LETTERS -> letterKeys(box)
            Page.SYMBOLS -> symbolKeys(box)
            Page.EMOJI -> emojiKeys(box)
            Page.NUMBERS -> numberKeys(box)
        }
        refreshSuggestions()
    }

    private val accents = mapOf(
        'a' to "àâäæ", 'e' to "éèêë", 'i' to "îï", 'o' to "ôöœ",
        'u' to "ùûü", 'c' to "ç", 'n' to "ñ", 'y' to "ÿ", 's' to "ß"
    )

    private fun letterKeys(box: LinearLayout) {
        val rows = listOf("azertyuiop", "qsdfghjklm", "wxcvbn")
        rows.forEachIndexed { index, letters ->
            val row = line(box)
            if (index == 2) {
                key(row, if (shift == Shift.LOCK) "⇪" else "⇧", 1.35f, special = true) {
                    val now = SystemClock.uptimeMillis()
                    shift = when {
                        shift == Shift.LOCK -> Shift.OFF
                        now - lastShiftTap < 420L -> Shift.LOCK
                        shift == Shift.ONCE -> Shift.OFF
                        else -> Shift.ONCE
                    }
                    lastShiftTap = now
                    buildKeys()
                }
            }
            letters.forEach { char ->
                val label = if (shift != Shift.OFF) char.uppercaseChar().toString() else char.toString()
                val view = key(row, label, 1f) {
                    enterText(label)
                    if (shift == Shift.ONCE) {
                        shift = Shift.OFF
                        buildKeys()
                    }
                }
                val forms = accents[char]
                if (!forms.isNullOrBlank()) view.setOnLongClickListener {
                    val menu = PopupMenu(this, view)
                    forms.forEachIndexed { i, variant ->
                        val value = if (shift == Shift.OFF) variant.toString()
                            else variant.uppercaseChar().toString()
                        menu.menu.add(0, i, i, value)
                    }
                    menu.setOnMenuItemClickListener {
                        enterText(it.title.toString())
                        if (shift == Shift.ONCE) { shift = Shift.OFF; buildKeys() }
                        true
                    }
                    menu.show()
                    true
                }
            }
            if (index == 2) addDelete(row)
        }

        val bottom = line(box)
        key(bottom, "?123", 1.4f, special = true) { page = Page.SYMBOLS; buildKeys() }
        key(bottom, "☺", 1.1f, special = true) { page = Page.EMOJI; buildKeys() }
        key(bottom, ",", 0.9f) { enterText(",") }
        val space = key(bottom, "Français", 5.1f) {}
        configureSpace(space)
        key(bottom, ".", 0.9f) { enterText(".") }
        addEnter(bottom, 1.4f)
    }

    private fun symbolKeys(box: LinearLayout) {
        listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("@", "#", "€", "_", "&", "-", "+", "(", ")", "/")
        ).forEach { characters ->
            val row = line(box)
            characters.forEach { key(row, it) { enterText(it) } }
        }
        val punctuation = line(box)
        key(punctuation, "ABC", 1.4f, special = true) { page = Page.LETTERS; buildKeys() }
        listOf("*", "\"", "'", ":", ";", "!", "?").forEach { key(punctuation, it) { enterText(it) } }
        addDelete(punctuation)
        val bottom = line(box)
        key(bottom, "ABC", 1.4f, special = true) { page = Page.LETTERS; buildKeys() }
        key(bottom, "☺", 1.1f, special = true) { page = Page.EMOJI; buildKeys() }
        key(bottom, ",") { enterText(",") }
        val space = key(bottom, "espace", 5.1f) {}
        configureSpace(space)
        key(bottom, ".") { enterText(".") }
        addEnter(bottom, 1.4f)
    }

    private fun emojiKeys(box: LinearLayout) {
        val values = listOf(
            "😀😂😊😍🥰🙂😉😅😎🤔",
            "😭😢😡😴🥳😇🙃😮🤩😘",
            "👍👎👏🙏💪👌👋❤️💙💚",
            "🎉✨🔥💯💬☀️🌈🍀🎵🎁"
        )
        // Iterate grapheme clusters rather than UTF-16 chars (emoji may use surrogate pairs).
        val emojiRows = listOf(
            listOf("😀", "😂", "😊", "😍", "🥰", "🙂", "😉", "😅", "😎", "🤔"),
            listOf("😭", "😢", "😡", "😴", "🥳", "😇", "🙃", "😮", "🤩", "😘"),
            listOf("👍", "👎", "👏", "🙏", "💪", "👌", "👋", "❤️", "💙", "💚"),
            listOf("🎉", "✨", "🔥", "💯", "💬", "☀️", "🌈", "🍀", "🎵", "🎁")
        )
        emojiRows.forEach { list ->
            val row = line(box)
            list.forEach { emoji -> key(row, emoji) { enterText(emoji) } }
        }
        val bottom = line(box)
        key(bottom, "ABC", 1.5f, special = true) { page = Page.LETTERS; buildKeys() }
        key(bottom, "?123", 1.5f, special = true) { page = Page.SYMBOLS; buildKeys() }
        val space = key(bottom, "espace", 5f) {}
        configureSpace(space)
        addDelete(bottom)
    }

    private fun numberKeys(box: LinearLayout) {
        listOf("123", "456", "789").forEach { digits ->
            val row = line(box)
            digits.forEach { key(row, it.toString()) { enterText(it.toString()) } }
        }
        val bottom = line(box)
        key(bottom, ",") { enterText(",") }
        key(bottom, "0") { enterText("0") }
        key(bottom, ".") { enterText(".") }
        addDelete(bottom)
        val secondaryRow = line(box)
        key(secondaryRow, "+") { enterText("+") }
        key(secondaryRow, "-") { enterText("-") }
        addEnter(secondaryRow)
    }

    private fun actionType(): Int {
        val info = currentInputEditorInfo ?: return EditorInfo.IME_ACTION_NONE
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EditorInfo.IME_ACTION_NONE
        return info.imeOptions and EditorInfo.IME_MASK_ACTION
    }

    private fun addEnter(row: LinearLayout, weight: Float = 1f) {
        val action = actionType()
        val label = when (action) {
            EditorInfo.IME_ACTION_SEARCH -> "⌕"
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_DONE -> "✓"
            EditorInfo.IME_ACTION_NEXT -> "→"
            EditorInfo.IME_ACTION_GO -> "Aller"
            else -> "↵"
        }
        key(row, label, weight, accent = true) {
            val connection = editable() ?: return@key
            if (action in listOf(
                    EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_DONE,
                    EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_GO,
                    EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS
                )) {
                if (!connection.performEditorAction(action)) enterText("\n")
            } else enterText("\n")
        }
    }

    private fun deleteOne() {
        val connection = editable() ?: return
        if (sensitive()) {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            return
        }
        val selected = connection.getSelectedText(0)
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
        if (!selected.isNullOrEmpty() ||
            (extracted != null && extracted.selectionStart >= 0 &&
                extracted.selectionEnd > extracted.selectionStart)) {
            connection.beginBatchEdit()
            try { connection.commitText("", 1) }
            finally { connection.endBatchEdit() }
            refreshSuggestions()
            return
        }
        val before = connection.getTextBeforeCursor(96, 0)?.toString().orEmpty()
        if (before.isEmpty()) {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        } else {
            val breaker = BreakIterator.getCharacterInstance(locale)
            breaker.setText(before)
            val boundary = breaker.preceding(before.length)
            val from = if (boundary == BreakIterator.DONE) 0 else boundary
            val count = before.codePointCount(from, before.length).coerceAtLeast(1)
            if (!connection.deleteSurroundingTextInCodePoints(count, 0)) {
                connection.deleteSurroundingText(before.length - from, 0)
            }
        }
        refreshSuggestions()
    }

    private fun deleteWordsBySwipe(snapshot: String, cursorAtDown: Int, steps: Int) {
        if (cursorAtDown < 0 || snapshot.isBlank()) return
        var idx = snapshot.length
        repeat(steps.coerceIn(1, 20)) {
            while (idx > 0 && snapshot[idx - 1].isWhitespace()) idx--
            while (idx > 0 && !snapshot[idx - 1].isWhitespace()) idx--
        }
        if (idx < snapshot.length) {
            val from = (cursorAtDown - (snapshot.length - idx)).coerceAtLeast(0)
            editable()?.setSelection(from, cursorAtDown)
        }
    }

    private fun addDelete(row: LinearLayout) {
        val button = key(row, "⌫", 1.35f, special = true) {}
        var downX = 0f
        var swiping = false
        var selectedAtDown = false
        var before = ""
        var cursorAtDown = -1
        var initialDone = false
        var repeats = 0
        val repeat = object : Runnable {
            override fun run() {
                deleteOne()
                repeats++
                handler.postDelayed(this, if (repeats > 14) 38L else 75L)
            }
        }
        val firstDelete = Runnable {
            if (!swiping) {
                deleteOne()
                initialDone = true
                handler.postDelayed(repeat, 390L)
            }
        }
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    swiping = false
                    initialDone = false
                    repeats = 0
                    val connection = editable()
                    val extracted = connection?.getExtractedText(ExtractedTextRequest(), 0)
                    cursorAtDown = if (sensitive()) -1 else extracted?.selectionEnd ?: -1
                    selectedAtDown = if (sensitive()) false else !connection?.getSelectedText(0).isNullOrEmpty()
                    before = if (sensitive()) "" else connection?.getTextBeforeCursor(280, 0)?.toString().orEmpty()
                    handler.postDelayed(firstDelete, 65L)
                    view.isPressed = true
                    tactileDown(view, "⌫", preview = false)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val left = downX - event.x
                    if (!selectedAtDown && left > dp(27) && cursorAtDown >= 0) {
                        if (!swiping) {
                            swiping = true
                            handler.removeCallbacks(firstDelete)
                            handler.removeCallbacks(repeat)
                        }
                        val words = 1 + ((left - dp(27)) / dp(28).toFloat()).toInt()
                        deleteWordsBySwipe(before, cursorAtDown, words)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(firstDelete)
                    handler.removeCallbacks(repeat)
                    view.isPressed = false
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        if (swiping && cursorAtDown >= 0) editable()?.setSelection(cursorAtDown, cursorAtDown)
                    } else if (swiping) {
                        editable()?.commitText("", 1)
                        refreshSuggestions()
                    } else if (!initialDone) deleteOne()
                    true
                }
                else -> true
            }
        }
    }

    private fun configureSpace(button: Button) {
        var x0 = 0f
        var previousStep = 0
        var moved = false
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = event.x
                    previousStep = 0
                    moved = false
                    view.isPressed = true
                    tactileDown(view, "espace", preview = false)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - x0
                    if (abs(dx) > dp(16)) moved = true
                    if (moved) {
                        val steps = (dx / dp(15).toFloat()).roundToInt()
                        val amount = (steps - previousStep).coerceIn(-10, 10)
                        repeat(abs(amount)) {
                            val direction = if (amount < 0) KeyEvent.KEYCODE_DPAD_LEFT
                                else KeyEvent.KEYCODE_DPAD_RIGHT
                            editable()?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, direction))
                            editable()?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, direction))
                        }
                        previousStep = steps
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    if (!moved) writeSpace()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { view.isPressed = false; true }
                else -> true
            }
        }
    }

    private fun writeSpace() {
        val connection = editable() ?: return
        if (sensitive()) { connection.commitText(" ", 1); return }
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            enterText(" ")
            return
        }
        val before = connection.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        val word = KeyboardLexicon.currentWord(before)
        val correction = KeyboardLexicon.correction(word)
        if (!correction.isNullOrBlank()) {
            connection.beginBatchEdit()
            try {
                val len = word.codePointCount(0, word.length)
                if (!connection.deleteSurroundingTextInCodePoints(len, 0)) {
                    connection.deleteSurroundingText(word.length, 0)
                }
                connection.commitText(correction + " ", 1)
            } finally { connection.endBatchEdit() }
            status("Correction : " + correction)
        } else {
            enterText(" ")
        }
        if (page == Page.LETTERS && !sensitive() && shift != Shift.LOCK) {
            val beforeSpace = before.trimEnd().lastOrNull()
            if (beforeSpace == '.' || beforeSpace == '!' || beforeSpace == '?') {
                shift = Shift.ONCE
                buildKeys()
            }
        }
        refreshSuggestions()
    }

    /** Suggestions utiles uniquement ; pas de ligne d'aide permanente. */
    private fun refreshSuggestions() {
        val target = suggestions ?: return
        val previousText = if (sensitive()) "" else editable()?.getTextBeforeCursor(80, 0)?.toString().orEmpty()
        val prefix = KeyboardLexicon.currentWord(previousText)
        val snapshot = if (sensitive()) "protected" else prefix.lowercase(locale)
        if (snapshot == lastSuggestions) return
        lastSuggestions = snapshot
        target.removeAllViews()
        val proposals = if (!sensitive() && prefix.length >= 2) KeyboardLexicon.candidates(prefix)
            else emptyList()
        target.visibility = if (proposals.isEmpty()) View.GONE else View.VISIBLE
        proposals.forEach { proposal ->
            val chip = key(target, proposal, 1f, height = 30) {
                val connection = editable() ?: return@key
                val before = connection.getTextBeforeCursor(80, 0)?.toString().orEmpty()
                val current = KeyboardLexicon.currentWord(before)
                val selection = connection.getSelectedText(0)
                connection.beginBatchEdit()
                try {
                    if (selection.isNullOrEmpty() && current.isNotEmpty())
                        connection.deleteSurroundingTextInCodePoints(current.codePointCount(0, current.length), 0)
                    connection.commitText(proposal + " ", 1)
                } finally { connection.endBatchEdit() }
                lastSuggestions = ""
                refreshSuggestions()
            }
            chip.textSize = 13f
            chip.setTypeface(null, Typeface.BOLD)
        }
    }

    private fun command(action: String) {
        try {
            startService(Intent(this, CaptureService::class.java).setAction(action))
        } catch (e: Exception) {
            status("Commande audio indisponible : " + (e.message ?: "erreur"))
        }
        handler.postDelayed({ updateControls() }, 150L)
    }

    private fun startRecording() {
        if (sensitive()) { status("Dictée désactivée dans les champs de mot de passe"); return }
        if (CaptureService.active || startingAt > 0L || transcribing) return
        pendingInsertId = null
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status("Autorisez une fois le microphone via Android")
            try {
                startActivity(Intent(this, MicrophonePermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                startingAt = SystemClock.elapsedRealtime()
            } catch (e: Exception) {
                status("Microphone non autorisé : " + (e.message ?: "erreur"))
            }
            return
        }
        startingAt = SystemClock.elapsedRealtime()
        try {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.START).putExtra("source", "keyboard")
            )
            status("Ouverture du microphone…")
        } catch (e: Exception) {
            startingAt = 0L
            status("Impossible d'enregistrer : " + (e.message ?: "erreur"))
        }
        updateControls()
    }

    private fun lastKeyboardSession() = store.all().firstOrNull { it.source == "keyboard" }

    /** Ne jamais inserer apres un changement de champ pendant l'appel reseau. */
    private fun insertIfStillFocused(text: String, generation: Long): Boolean {
        if (generation != inputGeneration || sensitive()) return false
        val connection = editable() ?: return false
        val ok = runCatching { connection.commitText(text, 1) }.getOrDefault(false)
        if (ok) refreshSuggestions()
        return ok
    }

    private fun transcribeLatest() {
        if (sensitive()) { status("Impossible de transcrire dans un champ protégé"); return }
        if (CaptureService.active || transcribing) return
        val item = lastKeyboardSession() ?: return
        if (item.segments.isEmpty()) { status("Enregistrement sans audio exploitable"); return }
        if (DictationSettings.token(this).length < 20) {
            status("Configurez une fois l'accès serveur avec ⚙")
            return
        }
        val originalInput = inputGeneration
        transcribing = true
        pendingInsertId = null
        status("Transcription en cours…")
        updateControls()
        thread(name = "dictee-transcription-manual") {
            try {
                val text = Transcriber(this).transcribe(item.id) { progress ->
                    handler.post { status(progress) }
                }
                handler.post {
                    transcribing = false
                    val inserted = text.isNotBlank() && insertIfStillFocused(text, originalInput)
                    pendingInsertId = if (inserted) null else item.id
                    status(if (inserted) "Texte inséré · message non envoyé"
                        else "Texte prêt · choisissez un champ et touchez ⇥")
                    updateControls()
                    if (inserted) restoreIdleStatus()
                }
            } catch (e: Exception) {
                handler.post {
                    transcribing = false
                    status("Audio conservé · transcription impossible : " + (e.message ?: "erreur"))
                    updateControls()
                }
            }
        }
    }

    /** Secours uniquement si le champ initial a disparu ou refuse l'insertion. */
    private fun insertLatest() {
        if (sensitive()) { status("Insertion désactivée dans ce champ protégé"); return }
        val item = pendingInsertId?.let { store.get(it) }
        if (item?.transcript.isNullOrBlank()) {
            status("Aucun texte en attente")
            return
        }
        val inserted = runCatching { editable()?.commitText(item!!.transcript, 1) == true }
            .getOrDefault(false)
        if (inserted) {
            pendingInsertId = null
            refreshSuggestions()
            status("Texte inséré · message non envoyé")
            restoreIdleStatus()
        } else {
            status("Sélectionnez un champ de texte pour insérer la dictée")
        }
        updateControls()
    }

    private fun updateControls() {
        val bar = actions ?: return
        if (bar.childCount == 0) return
        val active = CaptureService.active
        val isPaused = CaptureService.paused
        if (active) startingAt = 0L
        if (startingAt > 0L && SystemClock.elapsedRealtime() - startingAt > 13000L) {
            startingAt = 0L
            status(CaptureService.lastError.ifBlank { "Microphone indisponible. Vérifiez les autorisations." })
        }
        if (!active && finishing) {
            finishing = false
            status("Audio sauvegardé · choisissez Transcrire lorsque vous le souhaitez")
        }
        val latest = if (!active) lastKeyboardSession() else null
        val hasAudio = latest != null && latest.segments.isNotEmpty()
        val hasTranscript = !latest?.transcript.isNullOrBlank()
        val able = !sensitive()

        recordButton?.visibility = if (active) View.GONE else View.VISIBLE
        recordButton?.isEnabled = able && !transcribing && startingAt == 0L
        pauseButton?.visibility = if (active) View.VISIBLE else View.GONE
        pauseButton?.text = if (isPaused) "▶" else "⏸"
        pauseButton?.contentDescription = if (isPaused) "Reprendre la dictée" else "Mettre la dictée en pause"
        stopButton?.visibility = if (active) View.VISIBLE else View.GONE
        stopButton?.isEnabled = !finishing
        transcribeButton?.visibility = if (!active && hasAudio && !hasTranscript) View.VISIBLE else View.GONE
        transcribeButton?.isEnabled = able && !transcribing && latest?.state != "transcribing"
        transcribeButton?.text = if (transcribing || latest?.state == "transcribing") "…" else "✍"
        insertButton?.visibility = if (!active && pendingInsertId != null) View.VISIBLE else View.GONE
        insertButton?.isEnabled = able

        val newSnapshot = listOf(active, isPaused, latest?.id, latest?.state,
            latest?.transcript?.length, transcribing, finishing).joinToString(":")
        if (newSnapshot != lastSnapshot) {
            lastSnapshot = newSnapshot
            if (!active && !transcribing && !finishing && hasAudio && !hasTranscript &&
                status == "Dictez ou écrivez votre message") {
                status("Audio prêt · touchez Transcrire à votre rythme")
            }
        }
        if (active) {
            val elapsed = CaptureService.recordedDurationMs() / 1000L
            val timer = String.format(locale, "%02d:%02d", elapsed / 60, elapsed % 60)
            info?.text = if (isPaused) "⏸ Pause · " + timer + " · audio en sécurité"
                else "● Enregistrement · " + timer + " · écran verrouillable"
        } else {
            info?.text = status
        }
        info?.visibility = if (active || status != idleStatus) View.VISIBLE else View.GONE
    }

    private val tick = object : Runnable {
        override fun run() {
            updateControls()
            handler.postDelayed(this, if (CaptureService.active) 350L else 900L)
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputGeneration++ // Interdit une insertion automatique dans une autre zone de saisie.
        page = when (attribute?.inputType?.and(InputType.TYPE_MASK_CLASS)) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> Page.NUMBERS
            else -> Page.LETTERS
        }
        shift = Shift.OFF
        lastSuggestions = ""
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (page == Page.LETTERS && !sensitive() && shift != Shift.LOCK) {
            val caps = editable()?.getCursorCapsMode(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) ?: 0
            if (caps and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0) shift = Shift.ONCE
        }
        buildKeys()
        updateControls()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int,
        newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        handler.post { refreshSuggestions() }
    }

    override fun onWindowShown() {
        super.onWindowShown()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onWindowHidden() {
        handler.removeCallbacks(tick)
        hidePreview()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        hidePreview()
        // Un enregistrement lancé par l'utilisateur continue dans le foreground service.
        super.onDestroy()
    }
}
