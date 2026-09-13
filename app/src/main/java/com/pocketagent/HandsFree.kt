package com.pocketagent

import android.os.Handler
import android.os.Looper
import com.termux.terminal.TerminalSession

/**
 * Hands-free mode for a terminal session: the microphone stays open, everything said is
 * typed into the agent's input box, a trailing "send to Claude Code" / "send to Codex"
 * presses Enter, and once the agent's output has stopped changing the new text is read aloud.
 */
class HandsFree(
    private val a: TerminalActivity,
    private val prefs: Prefs,
    private val banner: (String?) -> Unit,
    private val session: () -> TerminalSession?,
    private val insert: (String) -> Unit,
) {
    var enabled = false
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val speaker by lazy { Speaker(a, prefs.voiceLanguage) }
    private var listener: Listener? = null
    private var lastTranscript = ""
    private var lastSent: String? = null
    private var lastSpoken = ""
    private var sentOnce = false
    private var pending = false
    private var checkQueued = false
    private var lastHash = 0
    private val settle = Runnable { readNewOutput() }
    // Codex and Claude Code redraw several times a second even when nothing changes (cursor,
    // render tick), so the settle timer is only restarted when the transcript content differs.
    private val check = Runnable {
        checkQueued = false
        if (!enabled) return@Runnable
        val h = transcript().hashCode()
        if (h == lastHash) return@Runnable
        lastHash = h
        pending = true
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, SETTLE_MS)
    }

    fun toggle() { if (enabled) disable() else enable() }

    /** [readCurrent]: the session was just started, so read its first screen once it settles. */
    fun enable(readCurrent: Boolean = false) {
        if (enabled) return
        a.withMic {
            Listener.ensureModel(a, model(), banner) {
                enabled = true
                lastTranscript = if (readCurrent) "" else transcript()
                lastHash = if (readCurrent) 0 else lastTranscript.hashCode()
                if (readCurrent) onOutputChanged()
                startListening()
                banner("Hands-free: listening. End with “send to Claude Code” or “send to Codex”. Tap here to stop reading.")
            }
        }
    }

    fun disable() {
        if (!enabled) return
        enabled = false
        handler.removeCallbacks(settle)
        handler.removeCallbacks(check); checkQueued = false
        listener?.release(); listener = null
        speaker.stop()
        banner(null)
    }

    fun release() { disable(); speaker.shutdown() }

    /** Debug builds: act as if [text] had been heard (`am start … --es debug_hear "…"`), no microphone needed. */
    fun debugHear(text: String) {
        if (!enabled) { enabled = true; lastTranscript = transcript() }
        onUtterance(text)
    }

    /** Tapping the banner: stop reading (and, if nothing is being read, show the status again). */
    fun stopReading() {
        if (speaker.isSpeaking) speaker.stop() else if (enabled) banner("Hands-free: listening")
    }

    /** A different session is now on screen: read only what appears from here on. */
    fun onSessionChanged() {
        if (!enabled) return
        handler.removeCallbacks(settle)
        handler.removeCallbacks(check); checkQueued = false
        speaker.stop()
        lastTranscript = transcript()
        lastHash = lastTranscript.hashCode()
        lastSent = null
        sentOnce = false
        pending = false
    }

    /** Called from the terminal's onTextChanged (very frequently); content is compared at most every 250 ms. */
    fun onOutputChanged() {
        if (!enabled || checkQueued) return
        checkQueued = true
        handler.postDelayed(check, CHECK_MS)
    }

    // ---------------------------------------------------------------- listening

    private fun model() = WhisperEngine.Model.of(prefs.whisperModel)

    private fun startListening() {
        val l = Listener(a, model(), prefs.voiceLanguage, endSilenceMs = 1400)
        l.onText = { onUtterance(it) }
        l.onError = { banner(it) }
        if (l.start()) listener = l else enabled = false
    }

    private fun onUtterance(text: String) {
        // Control words only when they are the whole phrase; "stop reading the file, send to codex" is dictation.
        if (VoiceCommands.isShortCommand(text)) when (val c = VoiceCommands.parse(text)) {
            is VoiceCommands.Cmd.StopReading -> { speaker.stop(); return }
            is VoiceCommands.Cmd.Repeat -> { if (lastSpoken.isNotBlank()) speak(lastSpoken); return }
            is VoiceCommands.Cmd.Escape -> { session()?.write(""); banner("Escape sent"); return }
            is VoiceCommands.Cmd.HandsFree -> if (c.on != true) { disable(); speaker.speak("Hands-free off"); return }
            else -> {}
        }
        val (body, send) = VoiceCommands.splitSend(text)
        if (body.isBlank() && !send) return
        if (body.isNotBlank()) insert(body)
        if (send) {
            lastSent = body.ifBlank { lastSent }
            sentOnce = true
            lastTranscript = transcript()
            session()?.write("\r")
            banner("Sent. Listening…")
        } else banner("Typed: $body  (say “send to Claude Code” to send)")
    }

    // ---------------------------------------------------------------- reading

    // transcriptText joins soft-wrapped rows but, unlike transcriptTextWithFullLinesJoined, does
    // not glue a row that happens to be exactly full to the next one ("paid" + "plan" → "paidplan").
    private fun transcript(): String = try {
        session()?.emulator?.screen?.transcriptText ?: ""
    } catch (e: Exception) { android.util.Log.w("HandsFree", "transcript failed", e); "" }

    private fun readNewOutput() {
        if (!enabled || !pending) return
        pending = false
        val now = transcript()
        val fresh = extractNew(lastTranscript, now, lastSent)
        if (BuildConfig.DEBUG) android.util.Log.i("HandsFree", "settle: old=${lastTranscript.length} new=${now.length} fresh=${fresh.length} session=${session() != null} emu=${session()?.emulator != null}")
        if (BuildConfig.DEBUG) debugDump(lastTranscript, now, fresh)
        lastTranscript = now
        if (fresh.isBlank()) return
        // Before the first message the screen only holds the agent's welcome banner; keep that short.
        speak(if (sentOnce) fresh else fresh.takeLast(600))
    }

    /** Debug builds: keep the last diff inputs next to last_dictation.wav for inspection. */
    private fun debugDump(old: String, now: String, fresh: String) {
        try {
            val dir = a.getExternalFilesDir(null) ?: return
            java.io.File(dir, "handsfree_old.txt").writeText(old)
            java.io.File(dir, "handsfree_new.txt").writeText(now)
            java.io.File(dir, "handsfree_spoken.txt").writeText(fresh)
        } catch (_: Exception) {}
    }

    private fun speak(text: String) {
        if (BuildConfig.DEBUG) android.util.Log.i("HandsFree", "HANDSFREE_SPEAK: $text")
        lastSpoken = text
        listener?.muted = true // otherwise the phone would transcribe its own voice
        banner("Reading… tap here or say “stop reading” to stop")
        speaker.speak(text) {
            handler.postDelayed({ listener?.muted = false }, 400)
            if (enabled) banner("Hands-free: listening")
        }
    }

    companion object {
        /** Output must stay unchanged this long before it is read; Claude Code's spinner redraws far more often. */
        const val SETTLE_MS = 2500L
        const val CHECK_MS = 250L

        private val FRAME = Regex("[│┃║╭╮╰╯─━═┌┐└┘├┤┬┴┼▌▐█▄▀▏▕╌╍┄┅]+")
        private val URL = Regex("""https?://[^\s"'<>]+""")
        /** Continuation rows of a wrapped URL, hashes, tokens: long runs with URL punctuation or digits. */
        private val GIBBERISH = Regex("""\S{18,}""")
        private val PROMPT = Regex("""^\S+@\S+:\S*[#$] ?$""")
        private val BULLET = Regex("^[\\s⏺●•◦▪▸›>*✻✶✳✢·⎿✔✓✗☐☒→⇒\\-]+")
        private val NOISE = Regex(
            "(\\? for shortcuts|esc to interrupt|shift\\+tab|bypass permissions|auto-accept|plan mode|⏵⏵|tokens? used|context left|" +
            "ctrl\\+[a-z]|/help|type your|try \"|^thinking|^working|% context|press enter|to cycle|^\\d+ tokens|" +
            "cross-session messaging|welcome to claude code|welcome to codex|/model to change|for commands|esc esc|↑ \\d|↓ \\d|" +
            "^\\S+ing…$|^\\S+ing\\.\\.\\.$)", RegexOption.IGNORE_CASE)

        fun cleanLine(l: String): String {
            var s = BULLET.replace(FRAME.replace(URL.replace(l, " link "), " "), " ")
            s = GIBBERISH.replace(s) { m -> if (m.value.any { it.isDigit() || it in "&=%/?#_" }) " " else m.value }
            return s.replace(Regex("\\s+"), " ").trim()
        }

        fun keep(l: String): Boolean {
            if (l.length < 2) return false
            if (l.count { it.isLetter() } < 2) return false
            if (PROMPT.matches(l)) return false
            return !NOISE.containsMatchIn(l)
        }

        /**
         * Text that appeared since [old]: the lines after the longest common prefix of the two
         * transcripts, minus terminal chrome, minus anything that was already on screen (TUI
         * apps redraw their last lines on every update), minus the echo of the user's message.
         */
        fun extractNew(old: String, now: String, sent: String?): String {
            val a = old.lines()
            val b = now.lines()
            var i = 0
            while (i < a.size && i < b.size && a[i] == b[i]) i++
            val seen = HashSet<String>()
            for (l in a.takeLast(400)) seen += cleanLine(l)
            val echo = sent?.trim()?.take(40)?.lowercase()
            val out = ArrayList<String>()
            for (l in b.subList(i, b.size)) {
                val c = cleanLine(l)
                if (!keep(c) || c in seen) continue
                if (echo != null && echo.length >= 4 && c.lowercase().contains(echo)) continue
                seen += c
                out += c
            }
            return out.joinToString(" ")
        }
    }
}
