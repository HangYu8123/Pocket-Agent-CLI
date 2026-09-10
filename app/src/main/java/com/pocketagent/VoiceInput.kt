package com.pocketagent

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Voice dictation with several engines:
 *  - SYSTEM: the phone's speech dialog (RecognizerIntent) or, failing that, a RecognitionService
 *    driven directly (works when a recognizer service exists without a dialog);
 *  - WHISPER: fully offline recognition with the bundled whisper.cpp and a downloaded model;
 *  - KEYBOARD: the keyboard's own microphone key (works with Gboard, Sogou, Baidu, Samsung…).
 * "auto" prefers the system engine when one exists and falls back to Whisper.
 * Must be constructed before the activity is started (e.g. as a field or in onCreate).
 */
class VoiceInput(
    private val activity: AppCompatActivity,
    private val onText: (String) -> Unit,
    private val onStatus: (String?) -> Unit,
) {
    enum class Engine { SYSTEM, WHISPER, KEYBOARD }

    // Lazy: this object is created in the Activity's field initialisers, before it has a Context.
    private val prefs by lazy { Prefs(activity) }
    private val whisper by lazy { WhisperEngine(activity) }
    private var recognizer: SpeechRecognizer? = null
    private var recorder: Recorder? = null
    private var job: Job? = null
    private var pendingAfterPermission: (() -> Unit)? = null

    private val dialogLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == AppCompatActivity.RESULT_OK && !text.isNullOrBlank()) onText(text.trim())
    }

    private val micPermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val next = pendingAfterPermission; pendingAfterPermission = null
        if (granted) next?.invoke() else onStatus("Microphone permission denied")
    }

    // ------------------------------------------------------------------ availability

    fun hasSystemDialog() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(activity.packageManager) != null
    fun hasSystemService() = SpeechRecognizer.isRecognitionAvailable(activity) ||
        (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity))
    fun hasSystemEngine() = hasSystemDialog() || hasSystemService()

    val isRecording get() = recorder?.isRunning == true

    /** Mic button: start dictation with the configured engine, or stop an in-progress recording. */
    fun start() {
        if (isRecording) { stopRecordingAndTranscribe(); return }
        when (prefs.voiceEngine) {
            "system" -> if (hasSystemEngine()) startSystem() else showHelp()
            "whisper" -> startWhisper()
            "keyboard" -> startKeyboardVoice()
            else -> if (hasSystemEngine()) startSystem() else startWhisper()
        }
    }

    /** Long-press on the mic: choose the engine explicitly (and remember it). */
    fun chooseEngine() {
        val items = arrayOf(
            "Phone speech service" + if (hasSystemEngine()) "" else "  (not available on this phone)",
            "Offline Whisper (no Google needed)" + (if (whisper.isDownloaded(WhisperEngine.Model.of(prefs.whisperModel))) "" else "  · downloads a model"),
            "Keyboard voice typing",
            "Automatic (phone service, else Whisper)",
            "Help…",
        )
        AlertDialog.Builder(activity).setTitle("Dictation engine")
            .setItems(items) { _, i ->
                when (i) {
                    0 -> { prefs.voiceEngine = "system"; start() }
                    1 -> { prefs.voiceEngine = "whisper"; start() }
                    2 -> { prefs.voiceEngine = "keyboard"; start() }
                    3 -> { prefs.voiceEngine = "auto"; start() }
                    else -> showHelp()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ system engine

    private fun baseIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag())
        putExtra(RecognizerIntent.EXTRA_PROMPT, activity.getString(R.string.voice_prompt))
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun languageTag(): String {
        val l = prefs.voiceLanguage
        return if (l == "auto") Locale.getDefault().toLanguageTag() else l
    }

    private fun startSystem() {
        if (hasSystemDialog()) {
            try { dialogLauncher.launch(baseIntent()); return } catch (_: ActivityNotFoundException) {}
        }
        if (hasSystemService()) withMic { startService() } else showHelp()
    }

    private fun withMic(block: () -> Unit) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) block()
        else { pendingAfterPermission = block; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    private fun startService() {
        stopService()
        val r = try {
            if (Build.VERSION.SDK_INT >= 31 && !SpeechRecognizer.isRecognitionAvailable(activity) &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
            ) SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            else SpeechRecognizer.createSpeechRecognizer(activity)
        } catch (e: Exception) { onStatus("Speech recognizer unavailable: ${e.message}"); return }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { onStatus("Listening… speak now") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { onStatus("Processing…") }
            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!p.isNullOrBlank()) onStatus("… $p")
            }
            override fun onResults(results: Bundle?) {
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                onStatus(null)
                if (!best.isNullOrBlank()) onText(best.trim())
                stopService()
            }
            override fun onError(error: Int) {
                onStatus(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap the mic and try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service needs a network connection"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Language not supported by the speech service"
                    else -> "Speech recognition error $error"
                })
                stopService()
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(baseIntent().apply { putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) })
    }

    private fun stopService() {
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    // ------------------------------------------------------------------ Whisper (offline)

    private fun startWhisper() {
        val model = WhisperEngine.Model.of(prefs.whisperModel)
        if (!whisper.isDownloaded(model)) {
            AlertDialog.Builder(activity)
                .setTitle("Download speech model")
                .setMessage("Offline dictation needs the Whisper \"${model.label}\" model, downloaded once (${model.mb} MB). " +
                    "You can pick a different size under Settings → Whisper model.")
                .setPositiveButton("Download") { _, _ -> downloadThenRecord(model) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        withMic { record(model) }
    }

    private fun downloadThenRecord(model: WhisperEngine.Model) {
        job?.cancel()
        job = activity.lifecycleScope.launch {
            try {
                onStatus("Downloading ${model.file}… 0%")
                whisper.download(model) { f -> activity.runOnUiThread { onStatus("Downloading ${model.file}… ${(f * 100).toInt()}%") } }
                onStatus(null)
                withMic { record(model) }
            } catch (e: Exception) {
                onStatus("Download failed: ${e.message}")
            }
        }
    }

    private fun record(model: WhisperEngine.Model) {
        stopService()
        val rec = Recorder(
            onLevel = { rms -> activity.runOnUiThread { if (isRecording) onStatus("Listening… tap the mic to stop " + bar(rms)) } },
            onAutoStop = { activity.runOnUiThread { stopRecordingAndTranscribe(model) } },
        )
        if (!rec.start()) { onStatus("Could not open the microphone"); return }
        recorder = rec
        onStatus("Listening… tap the mic to stop")
        // Load the model while the user is talking so transcription starts immediately.
        job?.cancel()
        job = activity.lifecycleScope.launch(Dispatchers.IO) {
            try { whisper.ensureLoaded(model) } catch (e: Exception) { withContext(Dispatchers.Main) { onStatus("Model load failed: ${e.message}") } }
        }
    }

    private fun bar(rms: Float): String {
        val n = (rms * 60).toInt().coerceIn(0, 12)
        return "▮".repeat(n) + "▯".repeat(12 - n)
    }

    private fun stopRecordingAndTranscribe(model: WhisperEngine.Model = WhisperEngine.Model.of(prefs.whisperModel)) {
        val rec = recorder ?: return
        recorder = null
        transcribeAndInsert(rec.stop(), model)
    }

    /**
     * Debug builds: run the exact dictation pipeline (transcribe → clean → banner → insert into
     * the terminal) on a 16 kHz mono 16-bit WAV instead of microphone audio.
     */
    fun debugDictateFromWav(path: String) {
        val bytes = java.io.File(path).readBytes()
        val pcm = FloatArray((bytes.size - 44) / 2) { i ->
            val lo = bytes[44 + i * 2].toInt() and 0xff
            val hi = bytes[45 + i * 2].toInt()
            ((hi shl 8) or lo) / 32768f
        }
        val model = WhisperEngine.Model.of(prefs.whisperModel)
        if (!whisper.isDownloaded(model)) {
            activity.lifecycleScope.launch {
                try { whisper.download(model) {} } catch (e: Exception) { onStatus("Download failed: $e"); return@launch }
                transcribeAndInsert(pcm, model)
            }
        } else transcribeAndInsert(pcm, model)
    }

    private fun transcribeAndInsert(pcm: FloatArray, model: WhisperEngine.Model) {
        if (pcm.size < WhisperEngine.SAMPLE_RATE / 2) { onStatus("Too short. Tap the mic and speak."); return }
        onStatus("Transcribing ${pcm.size / WhisperEngine.SAMPLE_RATE}s of audio…")
        val lang = prefs.voiceLanguage
        activity.lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            val text = try {
                withContext(Dispatchers.IO) {
                    whisper.ensureLoaded(model)
                    whisper.transcribe(pcm, lang)
                }
            } catch (e: Exception) { onStatus("Transcription failed: ${e.message}"); return@launch }
            onStatus(null)
            val cleaned = text.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), "").trim() // drop [MUSIC]-style tags
            if (BuildConfig.DEBUG) debugDump(pcm, cleaned, System.currentTimeMillis() - t0)
            if (cleaned.isBlank()) {
                var peak = 0f
                for (v in pcm) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
                onStatus(
                    if (peak < 0.01f) "Microphone captured silence (level %.3f). Check that no other app holds the mic, then try again.".format(peak)
                    else "Nothing recognized (%ds, level %.2f). Speak closer to the mic, or pick the language in Settings.".format(pcm.size / WhisperEngine.SAMPLE_RATE, peak)
                )
            } else onText(cleaned)
        }
    }

    /** Debug builds: keep the last recording as a WAV and log level/result so tests can verify the mic path. */
    private fun debugDump(pcm: FloatArray, text: String, ms: Long) {
        try {
            var sum = 0.0; var peak = 0f
            for (v in pcm) { sum += v * v; if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v) }
            val rms = kotlin.math.sqrt(sum / pcm.size.coerceAtLeast(1))
            val f = java.io.File(activity.getExternalFilesDir(null), "last_dictation.wav")
            java.io.DataOutputStream(java.io.BufferedOutputStream(java.io.FileOutputStream(f))).use { o ->
                val dataLen = pcm.size * 2
                fun le32(v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff); o.write((v shr 16) and 0xff); o.write((v shr 24) and 0xff) }
                fun le16(v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff) }
                o.writeBytes("RIFF"); le32(36 + dataLen); o.writeBytes("WAVE"); o.writeBytes("fmt "); le32(16); le16(1); le16(1)
                le32(WhisperEngine.SAMPLE_RATE); le32(WhisperEngine.SAMPLE_RATE * 2); le16(2); le16(16); o.writeBytes("data"); le32(dataLen)
                for (v in pcm) le16((v.coerceIn(-1f, 1f) * 32767).toInt())
            }
            android.util.Log.i("PocketWhisper", "DICTATION samples=${pcm.size} rms=%.4f peak=%.3f ms=$ms text=\"$text\" wav=${f.absolutePath}".format(rms, peak))
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ keyboard voice typing

    private fun startKeyboardVoice() {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val focused = activity.currentFocus
        if (focused != null) imm.showSoftInput(focused, InputMethodManager.SHOW_IMPLICIT)
        AlertDialog.Builder(activity)
            .setTitle("Keyboard voice typing")
            .setMessage("Use the microphone key on your keyboard: it types speech straight into the terminal. " +
                "If your current keyboard has no mic key, switch to one that does (Gboard, Sogou, Baidu, Samsung Keyboard…).")
            .setPositiveButton("Switch keyboard") { _, _ -> try { imm.showInputMethodPicker() } catch (_: Exception) {} }
            .setNeutralButton("Keyboard settings") { _, _ ->
                try { activity.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } catch (_: Exception) {}
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------------ help

    fun showHelp() {
        val status = "On this phone: speech dialog ${yn(hasSystemDialog())}, speech service ${yn(hasSystemService())}, " +
            "offline Whisper model ${yn(whisper.isDownloaded(WhisperEngine.Model.of(prefs.whisperModel)))}.\n\n"
        AlertDialog.Builder(activity)
            .setTitle("Voice input")
            .setMessage(status + helpText())
            .setPositiveButton("Use offline Whisper") { _, _ -> prefs.voiceEngine = "whisper"; startWhisper() }
            .setNeutralButton("Get Google speech") { _, _ -> openStore(activity, "com.google.android.googlequicksearchbox") }
            .setNegativeButton("Keyboard mic") { _, _ -> startKeyboardVoice() }
            .show()
    }

    private fun yn(b: Boolean) = if (b) "yes" else "no"

    fun stop() {
        stopService()
        recorder?.let { it.stop(); recorder = null }
        job?.cancel()
    }

    companion object {
        fun helpText() =
            "Three ways to dictate:\n" +
            "• Phone speech service: needs the Google app or \"Speech Recognition & Synthesis\" installed (Play Store).\n" +
            "• Offline Whisper: built into Pocket-CLI, no Google services, downloads a 32–190 MB model once.\n" +
            "• Keyboard mic: Gboard, Sogou, Baidu and Samsung keyboards type speech straight into the terminal."

        /** Opens the Play Store listing, falling back to the web page. */
        fun openStore(c: Context, pkg: String) {
            try {
                c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                try {
                    c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        }
    }
}
