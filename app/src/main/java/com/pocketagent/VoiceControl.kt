package com.pocketagent

import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Home-screen voice commands: "open Claude", "open Codex", "open terminal", "create a new
 * folder" (asks for the name, creates it under the default projects folder, then asks which
 * agent to open there). Speaks its questions and listens for one answer at a time.
 */
class VoiceControl(private val a: MainActivity, private val status: (String?) -> Unit) {

    private enum class Stage { COMMAND, FOLDER_NAME, WHICH_AGENT }

    private val prefs = Prefs(a)
    private val handler = Handler(Looper.getMainLooper())
    private val speaker by lazy { Speaker(a, prefs.voiceLanguage) }
    private var listener: Listener? = null
    private var stage = Stage.COMMAND
    private var retries = 0
    private var folderPath: String? = null
    private val timeout = Runnable {
        stopListening()
        status("Didn't hear anything. Tap the button to try again.")
    }

    val isActive get() = listener?.isRunning == true || speaker.isSpeaking

    /** Entry point from the button or the wake word. */
    fun start(fromWake: Boolean) {
        if (!Env.isReady(a)) { say("Ubuntu is not installed yet. Install it first.", null); return }
        if (isActive) { cancel(); return }
        retries = 0
        a.withMic {
            Listener.ensureModel(a, model(), status) {
                if (fromWake) listen(Stage.COMMAND, "Yes?")
                else listen(Stage.COMMAND, null)
            }
        }
    }

    /** Debug builds: act as if [text] had been heard at the current stage (`--es debug_say "…"`). */
    fun debugHear(text: String) {
        handler.removeCallbacks(timeout)
        listener?.stop()
        onHeard(text)
    }

    fun cancel() {
        handler.removeCallbacks(timeout)
        stopListening()
        speaker.stop()
        status(null)
    }

    fun release() { cancel(); listener?.release(); speaker.shutdown() }

    private fun model() = WhisperEngine.Model.of(prefs.whisperModel)

    private fun listen(next: Stage, prompt: String?) {
        stage = next
        if (prompt != null) say(prompt) { openMic() } else openMic()
    }

    private fun openMic() {
        val l = listener ?: Listener(a, model(), prefs.voiceLanguage, prompt = "Open Claude Code. Open Codex. Open the terminal. Create a new folder.").also { listener = it }
        l.onText = { t -> handler.removeCallbacks(timeout); l.stop(); onHeard(t) }
        l.onError = { e -> status(e) }
        if (!l.start()) return
        status(when (stage) {
            Stage.COMMAND -> "Listening… say “open Claude”, “open Codex”, “open terminal” or “create a new folder”"
            Stage.FOLDER_NAME -> "Listening… say the folder name"
            Stage.WHICH_AGENT -> "Listening… say “Claude”, “Codex”, “terminal” or “cancel”"
        })
        handler.postDelayed(timeout, LISTEN_TIMEOUT_MS)
    }

    private fun stopListening() { listener?.stop() }

    private fun say(text: String, then: (() -> Unit)?) {
        if (BuildConfig.DEBUG) android.util.Log.i("VoiceControl", "VOICE_SAY: $text")
        status(text)
        speaker.speak(text) { then?.invoke() }
    }

    private fun onHeard(text: String) {
        status("Heard: $text")
        when (stage) {
            Stage.COMMAND -> command(VoiceCommands.parse(text), text)
            Stage.FOLDER_NAME -> createFolder(text)
            Stage.WHICH_AGENT -> when (VoiceCommands.parse(text)) {
                is VoiceCommands.Cmd.OpenClaude -> launch(Mode.CLAUDE)
                is VoiceCommands.Cmd.OpenCodex -> launch(Mode.CODEX)
                is VoiceCommands.Cmd.OpenShell -> launch(Mode.SHELL)
                else -> say("Okay. The folder is selected as the working folder.", null)
            }
        }
    }

    private fun command(cmd: VoiceCommands.Cmd, raw: String) {
        when (cmd) {
            is VoiceCommands.Cmd.OpenClaude -> launch(Mode.CLAUDE)
            is VoiceCommands.Cmd.OpenCodex -> launch(Mode.CODEX)
            is VoiceCommands.Cmd.OpenShell -> launch(Mode.SHELL)
            is VoiceCommands.Cmd.NewFolder ->
                if (cmd.name == null) listen(Stage.FOLDER_NAME, "What should the folder be called?") else createFolder(cmd.name)
            is VoiceCommands.Cmd.HandsFree -> {
                val on = cmd.on ?: !prefs.handsFreeDefault
                prefs.handsFreeDefault = on
                say(if (on) "Hands-free mode is on. Sessions will listen and read replies aloud." else "Hands-free mode is off.", null)
            }
            is VoiceCommands.Cmd.Settings -> { status(null); a.startActivity(Intent(a, SettingsActivity::class.java)) }
            is VoiceCommands.Cmd.Cancel -> say("Okay.", null)
            is VoiceCommands.Cmd.Help -> say(VoiceCommands.HELP, null)
            else -> {
                if (retries++ < 1) listen(Stage.COMMAND, "I heard: $raw. Try: open Claude, open Codex, open terminal, or create a new folder.")
                else say("I didn't understand. Tap the voice button to try again.", null)
            }
        }
    }

    private fun createFolder(spoken: String) {
        val name = VoiceCommands.folderName(spoken)
        if (!Workspace.isValidName(name)) {
            if (retries++ < 1) listen(Stage.FOLDER_NAME, "That's not a valid name. What should the folder be called?")
            else say("Cancelled.", null)
            return
        }
        val path = Workspace.normalize("${Workspace.DEFAULT}/$name")
        val existed = Workspace.exists(a, path)
        val err = Workspace.create(a, path)
        if (err != null) { say("Could not create the folder. $err", null); return }
        Workspace.set(a, path)
        folderPath = path
        a.refreshWorkspace()
        listen(Stage.WHICH_AGENT, (if (existed) "Folder ${spokenName(name)} already existed and is now selected. " else "Created folder ${spokenName(name)}. ") + "Open Claude Code, Codex, or the terminal there?")
    }

    private fun spokenName(name: String) = name.replace('-', ' ').replace('_', ' ')

    private fun launch(mode: Mode) {
        say("Opening ${mode.title}.") {
            status(null)
            TerminalActivity.launch(a, mode, handsFree = prefs.handsFreeDefault || prefs.voiceLaunchHandsFree)
        }
    }

    companion object { const val LISTEN_TIMEOUT_MS = 15_000L }
}
