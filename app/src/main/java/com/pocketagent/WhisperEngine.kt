package com.pocketagent

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/** JNI surface of the bundled whisper.cpp build (see app/src/main/cpp). */
object WhisperLib {
    init { System.loadLibrary("pocketwhisper") }
    external fun initContext(path: String): Long
    external fun freeContext(ctx: Long)
    external fun transcribeBytes(ctx: Long, pcm: FloatArray, language: String, threads: Int, translate: Boolean, prompt: String): ByteArray
    /** Decodes whisper's raw UTF-8; invalid sequences (hallucinated tokens) become U+FFFD instead of crashing. */
    fun transcribe(ctx: Long, pcm: FloatArray, language: String, threads: Int, translate: Boolean, prompt: String): String =
        String(transcribeBytes(ctx, pcm, language, threads, translate, prompt), Charsets.UTF_8).replace("\uFFFD", "")
    external fun systemInfo(): String
}

/** Offline dictation: model download, model loading and transcription with whisper.cpp. */
class WhisperEngine(private val c: Context) {

    enum class Model(val key: String, val file: String, val label: String, val mb: Int) {
        TINY("tiny", "ggml-tiny-q5_1.bin", "Tiny · fastest, 32 MB", 32),
        BASE("base", "ggml-base-q5_1.bin", "Base · balanced, 60 MB", 60),
        SMALL("small", "ggml-small-q5_1.bin", "Small · most accurate, 190 MB", 190);

        companion object { fun of(k: String?) = entries.firstOrNull { it.key == k } ?: BASE }
    }

    fun modelsDir(): File = File(c.filesDir, "models").apply { mkdirs() }
    fun modelFile(m: Model): File = File(modelsDir(), m.file)
    fun isDownloaded(m: Model): Boolean = modelFile(m).length() > 1_000_000

    suspend fun download(m: Model, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val dest = modelFile(m)
        val tmp = File(dest.path + ".part")
        val conn = (URL(BASE_URL + m.file).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PocketCLI/1.0")
        }
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} downloading ${m.file}")
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    ensureActive()
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) progress(done.toFloat() / total)
                }
            }
        }
        if (total > 0 && tmp.length() != total) { tmp.delete(); throw IOException("Model download incomplete") }
        if (!tmp.renameTo(dest)) throw IOException("Could not move model into place")
    }

    /** Loads the model into memory (kept across sessions until a different model is requested). */
    fun ensureLoaded(m: Model) {
        synchronized(WhisperEngine) {
            if (ctx != 0L && loadedModel == m) return
            if (ctx != 0L) { WhisperLib.freeContext(ctx); ctx = 0L }
            val f = modelFile(m)
            if (!f.exists()) throw IOException("Model not downloaded")
            ctx = WhisperLib.initContext(f.absolutePath)
            if (ctx == 0L) throw IOException("Could not load ${m.file}")
            loadedModel = m
        }
    }

    fun transcribe(pcm: FloatArray, language: String, translate: Boolean = false, prompt: String? = null): String {
        synchronized(WhisperEngine) {
            if (ctx == 0L) throw IOException("Model not loaded")
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            return WhisperLib.transcribe(ctx, pcm, language, threads, translate, prompt ?: promptFor(language)).trim()
        }
    }

    /** Vocabulary hint for the decoder; the words a coding agent hears most. */
    private fun promptFor(language: String): String = when (language) {
        "zh" -> "写一个 Python 脚本，运行代码，git 提交，npm 安装，终端命令，Claude Code，Codex。"
        "ja" -> "Python スクリプトを書いて、コードを実行、git、npm、ターミナル、Claude Code、Codex。"
        "ko" -> "Python 스크립트를 작성하고 코드를 실행, git, npm, 터미널, Claude Code, Codex."
        else -> "Write a Python script, run the code, git commit, npm install, terminal command, Claude Code, Codex."
    }

    fun unload() {
        synchronized(WhisperEngine) { if (ctx != 0L) { WhisperLib.freeContext(ctx); ctx = 0L; loadedModel = null } }
    }

    companion object {
        const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
        const val SAMPLE_RATE = 16_000
        @Volatile private var ctx: Long = 0L
        @Volatile private var loadedModel: Model? = null
    }
}

/**
 * Records 16 kHz mono PCM from the microphone until [stop] is called or the speaker has been
 * silent for a while after talking. Audio is kept in memory (at most [maxSeconds]).
 */
class Recorder(
    private val onLevel: (rms: Float) -> Unit,
    private val onAutoStop: () -> Unit,
    private val maxSeconds: Int = 90,
) {
    private var thread: Thread? = null
    private val chunks = ArrayList<ShortArray>()
    @Volatile private var running = false
    val isRunning get() = running

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(WhisperEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, WhisperEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, 16_000))
        } catch (e: Exception) { return false }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        chunks.clear()
        running = true
        thread = Thread {
            rec.startRecording()
            val frame = ShortArray(WhisperEngine.SAMPLE_RATE / 10) // 100 ms
            var samples = 0L
            var spoke = false
            var silentMs = 0
            while (running && samples < maxSeconds.toLong() * WhisperEngine.SAMPLE_RATE) {
                val n = rec.read(frame, 0, frame.size)
                if (n <= 0) break
                chunks += frame.copyOf(n); samples += n
                var sum = 0.0
                for (i in 0 until n) { val v = frame[i] / 32768.0; sum += v * v }
                val rms = sqrt(sum / n).toFloat()
                onLevel(rms)
                if (rms > SPEECH_RMS) { spoke = true; silentMs = 0 } else if (spoke) silentMs += 100
                if (spoke && silentMs >= AUTO_STOP_SILENCE_MS) { running = false; onAutoStop() }
            }
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }.apply { start() }
        return true
    }

    /** Stops recording and returns the audio as floats in [-1, 1]. */
    fun stop(): FloatArray {
        running = false
        thread?.join(2000); thread = null
        val total = chunks.sumOf { it.size }
        val out = FloatArray(total)
        var i = 0
        for (ch in chunks) for (s in ch) out[i++] = s / 32768f
        chunks.clear()
        return out
    }

    companion object {
        const val SPEECH_RMS = 0.02f
        const val AUTO_STOP_SILENCE_MS = 1800
    }
}
