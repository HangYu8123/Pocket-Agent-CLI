package com.pocketagent

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Keeps the microphone open and hands over one PCM buffer per spoken phrase: audio is
 * gated by energy, so a phrase starts when the level rises above [SPEECH_RMS] (with a short
 * pre-roll) and ends after [endSilenceMs] of quiet. Silence never produces a segment, which
 * keeps the (expensive) transcription idle while nobody talks.
 */
class SegmentRecorder(
    private val onLevel: (rms: Float) -> Unit,
    private val onSegment: (FloatArray) -> Unit,
    private val endSilenceMs: Int = 1000,
    private val maxSegmentSec: Int = 45,
    private val prerollMs: Int = 400,
) {
    @Volatile private var running = false
    /** While true, audio is discarded (used while text-to-speech plays so it is not transcribed). */
    @Volatile var muted = false
    private var thread: Thread? = null
    val isRunning get() = running

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun start(): Boolean {
        val sr = WhisperEngine.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, sr))
        } catch (e: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        running = true
        thread = Thread {
            rec.startRecording()
            val frameMs = 50
            val frame = ShortArray(sr * frameMs / 1000)
            val preroll = ArrayDeque<ShortArray>()
            val segment = ArrayList<ShortArray>()
            var speaking = false
            var silentMs = 0
            var speechMs = 0
            var segSamples = 0
            fun flush() {
                if (speechMs >= MIN_SPEECH_MS) {
                    val out = FloatArray(segSamples)
                    var i = 0
                    for (ch in segment) for (s in ch) out[i++] = s / 32768f
                    onSegment(out)
                }
                segment.clear(); speaking = false; silentMs = 0; speechMs = 0; segSamples = 0
            }
            while (running) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) break
                if (muted) { segment.clear(); preroll.clear(); speaking = false; speechMs = 0; segSamples = 0; continue }
                var sum = 0.0
                for (i in 0 until n) { val v = frame[i] / 32768.0; sum += v * v }
                val rms = sqrt(sum / n).toFloat()
                onLevel(rms)
                val loud = rms > SPEECH_RMS
                if (!speaking) {
                    preroll.addLast(frame.copyOf(n))
                    while (preroll.size > prerollMs / frameMs) preroll.removeFirst()
                    if (loud) {
                        speaking = true; silentMs = 0; speechMs = frameMs
                        for (p in preroll) { segment += p; segSamples += p.size }
                        preroll.clear()
                    }
                } else {
                    segment += frame.copyOf(n); segSamples += n
                    if (loud) { silentMs = 0; speechMs += frameMs } else silentMs += frameMs
                    if (silentMs >= endSilenceMs || segSamples >= maxSegmentSec * sr) flush()
                }
            }
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }.apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(2000); thread = null
    }

    companion object {
        const val SPEECH_RMS = Recorder.SPEECH_RMS
        /** A phrase must contain this much above-threshold audio to be transcribed at all. */
        const val MIN_SPEECH_MS = 200
    }
}

/**
 * Always-on speech-to-text on top of [SegmentRecorder] and the offline Whisper engine:
 * every spoken phrase arrives as a line of text on the main thread. Used for the wake word,
 * home-screen voice commands and hands-free dictation.
 */
class Listener(
    private val c: Context,
    private val model: WhisperEngine.Model,
    private val language: String,
    private val prompt: String? = null,
    private val endSilenceMs: Int = 1000,
) {
    private val whisper = WhisperEngine(c)
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var rec: SegmentRecorder? = null

    /** Called on the main thread with cleaned text; never with a blank string. */
    var onText: ((String) -> Unit)? = null
    var onLevel: ((Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val isRunning get() = rec?.isRunning == true
    var muted: Boolean
        get() = rec?.muted == true
        set(v) { rec?.muted = v }

    fun start(): Boolean {
        if (isRunning) return true
        if (!whisper.isDownloaded(model)) { onError?.invoke("Speech model not downloaded"); return false }
        exec.execute { try { whisper.ensureLoaded(model) } catch (e: Exception) { main.post { onError?.invoke("Model load failed: ${e.message}") } } }
        val r = SegmentRecorder(
            onLevel = { rms -> onLevel?.let { cb -> main.post { cb(rms) } } },
            onSegment = { pcm -> exec.execute { transcribe(pcm) } },
            endSilenceMs = endSilenceMs,
        )
        if (!r.start()) { onError?.invoke("Could not open the microphone"); return false }
        rec = r
        return true
    }

    private fun transcribe(pcm: FloatArray) {
        if (!isRunning) return
        // whisper.cpp refuses clips shorter than a second; pad with silence.
        val min = WhisperEngine.SAMPLE_RATE * 6 / 5
        val audio = if (pcm.size >= min) pcm else pcm.copyOf(min)
        val text = try {
            whisper.ensureLoaded(model)
            whisper.transcribe(audio, language, prompt = prompt)
        } catch (e: Exception) { Log.w(TAG, "transcribe failed", e); return }
        val cleaned = text.replace(Regex("\\[[^\\]]*\\]|\\([^)]*\\)"), "").trim()
        if (cleaned.isBlank() || isHallucination(cleaned)) return
        Log.i(TAG, "heard: $cleaned")
        if (isRunning) main.post { onText?.invoke(cleaned) }
    }

    fun stop() { rec?.stop(); rec = null }
    fun release() { stop(); exec.shutdownNow() }

    companion object {
        private const val TAG = "PocketListener"
        // What Whisper tends to print for breaths, clicks and silence.
        private val NOISE = setOf("you", "thank you", "thanks", "thank you for watching", "bye", "the end",
            "okay", "ok", "um", "uh", "hmm", "so", "oh", "yeah", "mm", "please subscribe", "thanks for watching")

        fun isHallucination(text: String): Boolean {
            val n = VoiceCommands.normalize(text)
            return n.length < 2 || n in NOISE || n.startsWith("subtitles by") || n.startsWith("subscribe to")
        }

        /** Makes sure [model] is on disk, downloading it (with a confirmation) first; then runs [then]. */
        fun ensureModel(a: AppCompatActivity, model: WhisperEngine.Model, status: (String?) -> Unit, then: () -> Unit) {
            val engine = WhisperEngine(a)
            if (engine.isDownloaded(model)) { then(); return }
            AlertDialog.Builder(a)
                .setTitle("Download speech model")
                .setMessage("Voice control uses the offline Whisper \"${model.label}\" model, downloaded once (${model.mb} MB).")
                .setPositiveButton("Download") { _, _ ->
                    a.lifecycleScope.launch {
                        try {
                            status("Downloading ${model.file}… 0%")
                            engine.download(model) { f -> a.runOnUiThread { status("Downloading ${model.file}… ${(f * 100).toInt()}%") } }
                            status(null)
                            then()
                        } catch (e: Exception) { status("Download failed: ${e.message}") }
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> status(null) }
                .show()
        }
    }
}

/** Text-to-speech with a completion callback per request; long texts are split for the engine. */
class Speaker(c: Context, private val language: String) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var failed = false
    private val queue = ArrayDeque<Pair<String, (() -> Unit)?>>()
    private var current: Pair<String, (() -> Unit)?>? = null
    private var seq = 0
    private val done = HashMap<String, () -> Unit>()

    private val tts: TextToSpeech = TextToSpeech(c.applicationContext) { status ->
        main.post {
            if (BuildConfig.DEBUG) Log.i(TAG, "TTS_INIT status=$status")
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                val loc = if (language == "auto") Locale.getDefault() else Locale.forLanguageTag(language)
                val r = tts.setLanguage(loc)
                if (BuildConfig.DEBUG) Log.i(TAG, "TTS_LANG $loc -> $r engine=${tts.defaultEngine}")
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { if (BuildConfig.DEBUG) Log.i(TAG, "TTS_START $id") }
                    override fun onDone(id: String?) { if (BuildConfig.DEBUG) Log.i(TAG, "TTS_DONE $id"); main.post { finished(id) } }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { Log.w(TAG, "TTS_ERROR $id"); main.post { finished(id) } }
                    override fun onError(id: String?, code: Int) { Log.w(TAG, "TTS_ERROR $id code=$code"); main.post { finished(id) } }
                })
                pump()
            } else {
                failed = true
                Log.w(TAG, "TTS_INIT_FAILED status=$status")
                // Without an engine every request completes immediately so callers still continue.
                pump()
            }
        }
    }

    val isSpeaking get() = current != null || queue.isNotEmpty()

    /** Speaks [text] after anything already queued; [onDone] fires (main thread) when this text is finished or skipped. */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val t = text.trim()
        if (t.isEmpty()) { onDone?.invoke(); return }
        queue.addLast(t to onDone)
        pump()
    }

    private fun pump() {
        if (current != null || queue.isEmpty()) return
        if (!ready && !failed) return
        val item = queue.removeFirst()
        current = item
        if (failed) { main.post { finished(null) }; return }
        val parts = chunk(item.first)
        val id = "pcli-${seq++}"
        done[id] = { }
        parts.forEachIndexed { i, p ->
            val pid = if (i == parts.lastIndex) id else "$id-$i"
            val rc = tts.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, pid)
            if (BuildConfig.DEBUG) Log.i(TAG, "TTS_SPEAK $pid rc=$rc chars=${p.length}")
        }
    }

    private fun finished(id: String?) {
        if (id != null && id.contains('-') && id.count { it == '-' } > 1) return // intermediate chunk
        val item = current ?: return
        if (id != null && done.remove(id) == null && !failed) return // stale
        current = null
        item.second?.invoke()
        pump()
    }

    fun stop() {
        queue.clear()
        val item = current
        current = null
        done.clear()
        try { tts.stop() } catch (_: Exception) {}
        item?.second?.invoke()
    }

    fun shutdown() { stop(); try { tts.shutdown() } catch (_: Exception) {} }

    companion object {
        private const val TAG = "PocketSpeaker"
        private const val MAX = 3500 // TextToSpeech.getMaxSpeechInputLength() is 4000 on all current engines

        fun chunk(text: String): List<String> {
            if (text.length <= MAX) return listOf(text)
            val out = ArrayList<String>()
            var rest = text
            while (rest.length > MAX) {
                var cut = rest.lastIndexOf(". ", MAX)
                if (cut < MAX / 2) cut = rest.lastIndexOf(' ', MAX)
                if (cut < MAX / 2) cut = MAX
                out += rest.substring(0, cut + 1).trim()
                rest = rest.substring(cut + 1)
            }
            if (rest.isNotBlank()) out += rest.trim()
            return out
        }
    }
}
