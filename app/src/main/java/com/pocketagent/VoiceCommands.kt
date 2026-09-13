package com.pocketagent

/** Turns transcribed speech into app commands. Matching is deliberately loose: Whisper hears "Claude" as "cloud" a lot. */
object VoiceCommands {

    sealed class Cmd {
        object OpenClaude : Cmd()
        object OpenCodex : Cmd()
        object OpenShell : Cmd()
        /** [name] is null when the user did not say one ("create a new folder"). */
        class NewFolder(val name: String?) : Cmd()
        /** [on] is null for a plain toggle. */
        class HandsFree(val on: Boolean?) : Cmd()
        object Settings : Cmd()
        object Cancel : Cmd()
        object Help : Cmd()
        object StopReading : Cmd()
        object Repeat : Cmd()
        object Escape : Cmd()
        class Unknown(val text: String) : Cmd()
    }

    const val DEFAULT_WAKE = "hey pat"

    private val CLAUDE = setOf("claude", "claud", "cloud", "clod", "clawed", "klaud", "klaude", "claudia", "clause", "claw", "clyde", "claude's")
    private val CODEX = setOf("codex", "kodex", "codecs", "codex's", "codeex", "codax", "cortex")
    private val SHELL = setOf("terminal", "shell", "ubuntu", "bash", "linux", "console")
    private val FOLDER = setOf("folder", "directory", "project", "repo", "repository", "workspace")
    private val CREATE = setOf("create", "make", "new", "add", "start")

    /** Lower-case, punctuation removed, single spaces. */
    fun normalize(s: String): String =
        s.lowercase().replace('’', '\'').replace(Regex("[^\\p{L}\\p{N}']+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun words(s: String) = normalize(s).split(' ').filter { it.isNotEmpty() }

    fun mentionsClaude(w: List<String>) = w.any { it in CLAUDE } || w.windowed(2).any { it[0] == "cloud" && it[1] == "code" }
    fun mentionsCodex(w: List<String>) = w.any { it in CODEX } || w.windowed(2).any { (it[0] == "code" || it[0] == "co") && (it[1] == "x" || it[1] == "ex" || it[1] == "decks" || it[1] == "dex") }
    fun mentionsShell(w: List<String>) = w.any { it in SHELL }

    fun parse(text: String): Cmd {
        val n = normalize(text)
        val w = n.split(' ').filter { it.isNotEmpty() }
        if (w.isEmpty()) return Cmd.Unknown(text)

        if (Regex("\\b(stop|quit|end) (reading|talking|speaking)\\b|\\b(be quiet|shut up|silence)\\b").containsMatchIn(n)) return Cmd.StopReading
        if (Regex("\\b(read (it |that )?again|repeat( that)?|say (it |that )?again)\\b").containsMatchIn(n)) return Cmd.Repeat
        if (Regex("\\b(hands? ?free|handsfree)\\b").containsMatchIn(n)) {
            val off = Regex("\\b(off|disable|stop|exit|leave|end|no)\\b").containsMatchIn(n)
            val on = Regex("\\b(on|enable|start|enter|yes)\\b").containsMatchIn(n)
            return Cmd.HandsFree(if (off) false else if (on) true else null)
        }
        if (Regex("^(escape|press escape|interrupt|cancel that)$").matches(n)) return Cmd.Escape
        if (Regex("^(cancel|never ?mind|nothing|stop|no|forget it|go back)( thanks| thank you)?$").matches(n)) return Cmd.Cancel
        if (Regex("\\b(help|what can i say|commands)\\b").containsMatchIn(n)) return Cmd.Help
        if (Regex("\\b(settings|preferences|options)\\b").containsMatchIn(n)) return Cmd.Settings

        val wantsFolder = w.any { it in FOLDER } && (w.any { it in CREATE } || w.first() == "folder")
        if (wantsFolder) return Cmd.NewFolder(folderNameIn(n))

        if (mentionsClaude(w)) return Cmd.OpenClaude
        if (mentionsCodex(w)) return Cmd.OpenCodex
        if (mentionsShell(w)) return Cmd.OpenShell
        return Cmd.Unknown(text)
    }

    /** "create a folder called my app" → "my app"; null when no name was given. */
    private fun folderNameIn(n: String): String? {
        val m = Regex("\\b(?:called|named|name it|call it|name|titled)\\s+(.+)$").find(n) ?: return null
        val name = m.groupValues[1].trim()
        return name.ifBlank { null }
    }

    /** Spoken words → a safe folder name: "My Cool App" → "my-cool-app". */
    fun folderName(spoken: String): String {
        val n = normalize(spoken).replace("'", "")
            .removePrefix("call it ").removePrefix("name it ").removePrefix("called ").removePrefix("named ")
        return n.trim().replace(' ', '-').trim('-')
    }

    /**
     * Splits hands-free dictation into the text to type and whether to press Enter:
     * "fix the failing test send to claude code" → ("fix the failing test", true).
     * The send phrase must come at the end of the utterance.
     */
    fun splitSend(text: String): Pair<String, Boolean> {
        val n = text.trim()
        val re = Regex("[\\s,.!?]*\\b(send|sent|sand|sen|submit|enter)(\\s+(it|this|that|message|them))?(\\s+(to|too|two|2)\\s+(claude|claud|cloud|clod|klaud|klaude)(\\s+code)?|\\s+(to|too|two|2)\\s+(codex|kodex|codecs|code\\s*x|cortex)|\\s+(to|too|two|2)\\s+(the\\s+)?(terminal|shell|agent))?[\\s.!?]*$", RegexOption.IGNORE_CASE)
        val m = re.find(n) ?: return n to false
        // Bare "send"/"enter" only counts as a command when it ends a longer phrase or stands alone.
        val body = n.substring(0, m.range.first).trim()
        return body to true
    }

    // ------------------------------------------------------------------ wake word

    /** True when [text] contains [phrase] ("hey pat"), allowing one substitution per short word. */
    fun matchesWake(text: String, phrase: String = DEFAULT_WAKE): Boolean {
        val want = words(phrase)
        if (want.isEmpty()) return false
        val have = words(text)
        if (have.isEmpty()) return false
        // Joined form ("heypat") for engines that glue short words together.
        if (have.any { close(it, want.joinToString("")) }) return true
        for (i in 0..have.size - want.size) {
            var ok = true
            for (j in want.indices) if (!close(have[i + j], want[j])) { ok = false; break }
            if (ok) return true
        }
        return false
    }

    private fun close(a: String, b: String): Boolean {
        if (a == b) return true
        val tol = if (b.length <= 3) 1 else if (b.length <= 6) 2 else 3
        return levenshtein(a, b) <= tol
    }

    fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            cur.copyInto(prev)
        }
        return prev[b.length]
    }

    const val HELP =
        "Say: open Claude, open Codex, open terminal, create a new folder, hands-free on or off, settings, or cancel. " +
        "In hands-free mode, end what you say with: send to Claude Code, or send to Codex. " +
        "While it reads, say stop reading."
}
