package com.pocketagent

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.pocketagent.databinding.ActivityTerminalBinding
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.RandomAccessFile

class TerminalActivity : AppCompatActivity(), TerminalViewClient, TerminalSessionClient {

    private lateinit var b: ActivityTerminalBinding
    private lateinit var mode: Mode
    private val prefs by lazy { Prefs(this) }
    private var service: TerminalService? = null
    private var session: TerminalSession? = null
    private val handler = Handler(Looper.getMainLooper())

    private var ctrlDown = false
    private var altDown = false
    private var ctrlKey: TextView? = null
    private var altKey: TextView? = null
    private var lastUrl: String? = null
    private var openUrlOffset = -1L
    private var urlScanPending = false
    private var endedDialogShown = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as TerminalService.LocalBinder).service
            attach()
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    private val voice = VoiceInput(
        this,
        onText = { insertText(it, prefs.voiceAutoEnter) },
        onStatus = { status ->
            if (status == null) b.voiceBanner.visibility = View.GONE
            else { b.voiceBanner.text = status; b.voiceBanner.visibility = View.VISIBLE }
        },
    )

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(b.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        mode = Mode.of(intent.getStringExtra(Mode.EXTRA))
        b.title.text = mode.title

        b.terminalView.setTerminalViewClient(this)
        b.terminalView.setIsTerminalViewKeyLoggingEnabled(false)
        // setTextSize() must come first: it creates the renderer that setTypeface() reads.
        applyFontSize()
        b.terminalView.setTypeface(Typeface.MONOSPACE)
        buildExtraKeys()

        b.btnMic.setOnClickListener { startVoice() }
        b.btnMic.setOnLongClickListener { voice.chooseEngine(); true }
        b.btnKeyboard.setOnClickListener { toggleKeyboard() }
        b.btnPaste.setOnClickListener { pasteClipboard() }
        b.btnLink.setOnClickListener { lastUrl?.let { openUrl(it) } }
        b.btnMenu.setOnClickListener { showMenu(it) }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        val svc = Intent(this, TerminalService::class.java)
        ContextCompat.startForegroundService(this, svc)
        bindService(svc, conn, Context.BIND_AUTO_CREATE)

        if (BuildConfig.DEBUG) intent.getStringExtra("debug_transcribe")?.let { debugTranscribe(it) }
        // `--es debug_dictate file.wav`: full dictation pipeline with a WAV standing in for the mic.
        if (BuildConfig.DEBUG) intent.getStringExtra("debug_dictate")?.let { path ->
            handler.postDelayed({ voice.debugDictateFromWav(path) }, 4000) // after the session is attached
        }
    }

    /**
     * Debug builds only: `am start ... --es debug_transcribe /path/file.wav` transcribes a 16 kHz
     * mono 16-bit WAV with the offline engine and logs the result (used by automated tests).
     */
    private fun debugTranscribe(path: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                val bytes = java.io.File(path).readBytes()
                val pcm = FloatArray((bytes.size - 44) / 2) { i ->
                    val lo = bytes[44 + i * 2].toInt() and 0xff
                    val hi = bytes[45 + i * 2].toInt()
                    ((hi shl 8) or lo) / 32768f
                }
                val engine = WhisperEngine(this@TerminalActivity)
                val model = WhisperEngine.Model.of(prefs.whisperModel)
                if (!engine.isDownloaded(model)) engine.download(model) {}
                engine.ensureLoaded(model)
                val t0 = System.currentTimeMillis()
                val text = engine.transcribe(pcm, prefs.voiceLanguage)
                "DEBUG_TRANSCRIBE ok (${System.currentTimeMillis() - t0} ms, ${pcm.size / WhisperEngine.SAMPLE_RATE}s): $text"
            } catch (e: Exception) { "DEBUG_TRANSCRIBE failed: $e" }
            Log.i(TAG, result)
            runOnUiThread { b.voiceBanner.text = result; b.voiceBanner.visibility = View.VISIBLE }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newMode = Mode.of(intent.getStringExtra(Mode.EXTRA))
        if (newMode != mode) {
            mode = newMode
            b.title.text = mode.title
            endedDialogShown = false
            attach()
        }
    }

    private fun attach() {
        val svc = service ?: return
        val existed = svc.isRunning(mode)
        val s = try { svc.getOrCreate(mode) } catch (e: Exception) {
            Log.e(TAG, "failed to start session", e)
            AlertDialog.Builder(this).setTitle("Could not start session").setMessage(e.toString())
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }.show()
            return
        }
        session = s
        svc.uiClient = this
        b.terminalView.attachSession(s)
        b.terminalView.onScreenUpdated()
        lastUrl = null
        b.btnLink.visibility = View.GONE
        b.terminalView.requestFocus()
        if (mode != Mode.SETUP && mode != Mode.UPDATE) {
            b.terminalView.postDelayed({ showKeyboard() }, 300)
            if (existed) checkWorkspaceChanged(svc, s)
        }
    }

    /** The user picked a different working folder while this session was already running. */
    private fun checkWorkspaceChanged(svc: TerminalService, s: TerminalSession) {
        val have = svc.cwdOf(mode) ?: return
        val want = Workspace.current(this)
        if (have == want) return
        val builder = AlertDialog.Builder(this)
            .setTitle("Working folder changed")
            .setMessage("This ${mode.title} session started in\n$have\n\nbut the working folder is now\n$want")
            .setPositiveButton("Restart here") { _, _ -> restartSession() }
            .setNegativeButton("Keep session", null)
        if (mode == Mode.SHELL) builder.setNeutralButton("cd there") { _, _ -> s.write("cd '${want.replace("'", "'\\''")}'\r") }
        builder.show()
    }

    override fun onResume() {
        super.onResume()
        service?.uiClient = this
        session?.let { b.terminalView.onScreenUpdated() }
        openUrlOffset = Env.openUrlFile(this).length() // ignore anything queued before we were visible
        handler.post(urlPoll)
    }

    override fun onPause() {
        handler.removeCallbacks(urlPoll)
        super.onPause()
    }

    override fun onDestroy() {
        voice.stop()
        handler.removeCallbacksAndMessages(null)
        service?.let { if (it.uiClient === this) it.uiClient = null }
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI helpers

    private fun applyFontSize() {
        val px = (prefs.fontSizeSp * resources.displayMetrics.scaledDensity).toInt().coerceAtLeast(8)
        b.terminalView.setTextSize(px)
    }

    private fun showKeyboard() {
        b.terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(b.terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun toggleKeyboard() {
        b.terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.toggleSoftInputFromWindow(b.terminalView.windowToken, 0, 0)
    }

    private fun insertText(text: String, pressEnter: Boolean) {
        val s = session ?: return
        val emu = s.emulator
        if (emu != null) emu.paste(text) else s.write(text)
        if (pressEnter) s.write("\r")
        b.voiceBanner.text = "Inserted: $text"
        b.voiceBanner.visibility = View.VISIBLE
        handler.postDelayed({ b.voiceBanner.visibility = View.GONE }, 2500)
    }

    private fun pasteClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
        session?.emulator?.paste(text)
    }

    private fun startVoice() = voice.start()

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Restart session")
        m.menu.add(0, 2, 1, "Kill session")
        m.menu.add(0, 3, 2, "Font size +")
        m.menu.add(0, 4, 3, "Font size -")
        m.menu.add(0, 5, 4, if (b.extraKeysScroll.visibility == View.VISIBLE) "Hide extra keys" else "Show extra keys")
        m.menu.add(0, 7, 5, getString(R.string.open_working_folder))
        m.menu.add(0, 6, 6, "Settings")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> restartSession()
                2 -> { service?.kill(mode); finish() }
                3 -> { prefs.fontSizeSp += 1; applyFontSize() }
                4 -> { prefs.fontSizeSp -= 1; applyFontSize() }
                5 -> b.extraKeysScroll.visibility = if (b.extraKeysScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                6 -> startActivity(Intent(this, SettingsActivity::class.java))
                7 -> FolderBrowser.open(this, service?.cwdOf(mode) ?: Workspace.current(this))
            }
            true
        }
        m.show()
    }

    private fun restartSession() {
        service?.kill(mode)
        endedDialogShown = false
        attach()
    }

    // ---------------------------------------------------------------- extra keys

    private fun buildExtraKeys() {
        val row = b.extraKeys
        row.removeAllViews()
        fun key(label: String, repeat: Boolean = false, onTap: () -> Unit): TextView {
            val tv = TextView(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(this@TerminalActivity, R.color.fg))
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setBackgroundResource(R.drawable.bg_key)
                setPadding(dp(11), dp(8), dp(11), dp(8))
                isClickable = true
                isFocusable = false
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(3), 0, dp(3), 0)
            tv.layoutParams = lp
            if (repeat) {
                var repeater: Runnable? = null
                tv.setOnTouchListener { v, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.isPressed = true; haptic(v); onTap()
                            val r = object : Runnable { override fun run() { onTap(); handler.postDelayed(this, 70) } }
                            repeater = r
                            handler.postDelayed(r, 400)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.isPressed = false
                            repeater?.let { handler.removeCallbacks(it) }
                            if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                        }
                    }
                    true
                }
            } else {
                tv.setOnClickListener { haptic(it); onTap() }
            }
            row.addView(tv)
            return tv
        }
        fun code(keyCode: Int): () -> Unit = { b.terminalView.handleKeyCode(keyCode, 0) }
        fun text(t: String): () -> Unit = { session?.write(t) }

        key("ESC", onTap = code(KeyEvent.KEYCODE_ESCAPE))
        key("TAB", onTap = code(KeyEvent.KEYCODE_TAB))
        ctrlKey = key("CTRL") { ctrlDown = !ctrlDown; ctrlKey?.isActivated = ctrlDown }
        altKey = key("ALT") { altDown = !altDown; altKey?.isActivated = altDown }
        key("^C", onTap = text("\u0003"))
        key("↑", repeat = true, onTap = code(KeyEvent.KEYCODE_DPAD_UP))
        key("↓", repeat = true, onTap = code(KeyEvent.KEYCODE_DPAD_DOWN))
        key("←", repeat = true, onTap = code(KeyEvent.KEYCODE_DPAD_LEFT))
        key("→", repeat = true, onTap = code(KeyEvent.KEYCODE_DPAD_RIGHT))
        key("-", onTap = text("-"))
        key("/", onTap = text("/"))
        key("|", onTap = text("|"))
        key("~", onTap = text("~"))
        key("HOME", onTap = code(KeyEvent.KEYCODE_MOVE_HOME))
        key("END", onTap = code(KeyEvent.KEYCODE_MOVE_END))
        key("PGUP", onTap = code(KeyEvent.KEYCODE_PAGE_UP))
        key("PGDN", onTap = code(KeyEvent.KEYCODE_PAGE_DOWN))
        key("^D", onTap = text("\u0004"))
        key("^Z", onTap = text("\u001a"))
        key("^L", onTap = text("\u000c"))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun haptic(v: View) {
        if (prefs.hapticKeys) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun clearStickyModifiers() {
        if (ctrlDown || altDown) {
            ctrlDown = false; altDown = false
            ctrlKey?.isActivated = false; altKey?.isActivated = false
        }
    }

    // ---------------------------------------------------------------- links

    private val urlPoll = object : Runnable {
        override fun run() {
            drainOpenUrlFile()
            handler.postDelayed(this, 700)
        }
    }

    /** The in-guest xdg-open shim appends URLs to bridge/open_url; open any new ones. */
    private fun drainOpenUrlFile() {
        val f = Env.openUrlFile(this)
        val len = f.length()
        if (openUrlOffset < 0) openUrlOffset = len
        if (len <= openUrlOffset) { if (len < openUrlOffset) openUrlOffset = len; return }
        val chunk = try {
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(openUrlOffset)
                val buf = ByteArray((len - openUrlOffset).toInt().coerceAtMost(65536))
                val n = raf.read(buf)
                String(buf, 0, n.coerceAtLeast(0))
            }
        } catch (e: Exception) { return }
        openUrlOffset = len
        val urls = chunk.lines().map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }
        urls.lastOrNull()?.let { lastUrl = it; b.btnLink.visibility = View.VISIBLE; openUrl(it) }
    }

    private fun scheduleUrlScan() {
        if (urlScanPending) return
        urlScanPending = true
        handler.postDelayed({
            urlScanPending = false
            val emu = session?.emulator ?: return@postDelayed
            val text = try { emu.screen.transcriptTextWithFullLinesJoined } catch (e: Exception) { return@postDelayed }
            val tail = if (text.length > 6000) text.substring(text.length - 6000) else text
            val found = URL_RE.findAll(tail).lastOrNull()?.value?.trimEnd('.', ',', ')', ']', '\'', '"') ?: return@postDelayed
            if (found != lastUrl) { lastUrl = found; b.btnLink.visibility = View.VISIBLE }
        }, 800)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", url))
            Toast.makeText(this, "No browser found; link copied to clipboard", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- TerminalSessionClient

    override fun onTextChanged(s: TerminalSession) {
        if (s !== session) return
        b.terminalView.onScreenUpdated()
        scheduleUrlScan()
    }

    override fun onTitleChanged(s: TerminalSession) {
        if (s !== session) return
        val t = s.title
        b.title.text = if (t.isNullOrBlank()) mode.title else "${mode.title} · $t"
    }

    override fun onSessionFinished(s: TerminalSession) {
        if (s !== session || endedDialogShown || isFinishing) return
        endedDialogShown = true
        b.terminalView.onScreenUpdated()
        val exit = s.exitStatus
        val msg = when {
            mode == Mode.SETUP && exit == 0 -> "Setup finished successfully. You can now launch Claude Code or Codex."
            mode == Mode.SETUP -> "Setup exited with status $exit. Scroll up to read the error, then restart to retry (already-completed steps are fast)."
            else -> "The process exited with status $exit."
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.session_ended)
            .setMessage(msg)
            .setPositiveButton(R.string.restart) { _, _ -> restartSession() }
            .setNegativeButton(R.string.close) { _, _ -> service?.kill(mode); finish() }
            .setNeutralButton("Keep open", null)
            .show()
    }

    override fun onCopyTextToClipboard(s: TerminalSession, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    override fun onPasteTextFromClipboard(s: TerminalSession) { pasteClipboard() }
    override fun onBell(s: TerminalSession) { b.terminalView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    override fun onColorsChanged(s: TerminalSession) { b.terminalView.onScreenUpdated() }
    override fun onTerminalCursorStateChange(state: Boolean) { b.terminalView.setTerminalCursorBlinkerState(state, true) }
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String, msg: String) { Log.e(tag, msg) }
    override fun logWarn(tag: String, msg: String) { Log.w(tag, msg) }
    override fun logInfo(tag: String, msg: String) { Log.i(tag, msg) }
    override fun logDebug(tag: String, msg: String) { Log.d(tag, msg) }
    override fun logVerbose(tag: String, msg: String) { Log.v(tag, msg) }
    override fun logStackTraceWithMessage(tag: String, msg: String, e: Exception) { Log.e(tag, msg, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "", e) }

    // ---------------------------------------------------------------- TerminalViewClient

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            prefs.fontSizeSp += if (scale > 1f) 1 else -1
            applyFontSize()
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) { showKeyboard() }
    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = prefs.charBasedInput
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, s: TerminalSession): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER && !s.isRunning) { restartSession(); return true }
        return false
    }

    /**
     * TerminalViewClient.onKeyUp has the same signature as Activity.onKeyUp, so this single
     * override serves both. Delegating to the Activity keeps Back working (Activity turns the
     * BACK key-up into onBackPressed), while other keys fall through to the terminal.
     */
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = super<AppCompatActivity>.onKeyUp(keyCode, e)
    override fun onLongPress(e: MotionEvent) = false
    override fun readControlKey() = ctrlDown
    override fun readAltKey() = altDown
    override fun readShiftKey() = false
    override fun readFnKey() = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, s: TerminalSession): Boolean {
        if (this.ctrlDown || altDown) handler.post { clearStickyModifiers() }
        return false
    }

    override fun onEmulatorSet() { b.terminalView.onScreenUpdated() }

    companion object {
        private const val TAG = "TerminalActivity"
        private val URL_RE = Regex("""https?://[^\s"'<>]+""")

        fun launch(c: Context, mode: Mode) {
            c.startActivity(Intent(c, TerminalActivity::class.java).putExtra(Mode.EXTRA, mode.key))
        }
    }
}
