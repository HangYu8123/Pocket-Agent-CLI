package com.pocketagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Plain FrameLayout host for the preference fragment.
        val host = android.widget.FrameLayout(this).apply { id = CONTAINER_ID }
        setContentView(host)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(host) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(CONTAINER_ID, Fragment()).commit()
        }
    }

    class Fragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val ctx = requireContext()

            findPreference<Preference>("update_clis")?.setOnPreferenceClickListener {
                if (Env.isReady(ctx)) TerminalActivity.launch(ctx, Mode.UPDATE)
                else Toast.makeText(ctx, R.string.status_not_installed, Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<Preference>("install_skills")?.setOnPreferenceClickListener {
                if (Env.isReady(ctx)) TerminalActivity.launch(ctx, Mode.SKILLS)
                else Toast.makeText(ctx, R.string.status_not_installed, Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<Preference>("voice_commands_help")?.setOnPreferenceClickListener {
                AlertDialog.Builder(ctx).setTitle("Voice commands")
                    .setMessage("Home screen (tap Voice command, or say \"${Prefs(ctx).wakePhrase}\" with the wake word on):\n" +
                        "• \"open Claude\" / \"open Codex\" / \"open terminal\"\n" +
                        "• \"create a new folder\" (asks for the name, creates it under ~/projects, then asks which agent to open)\n" +
                        "• \"hands-free on\" / \"hands-free off\", \"settings\", \"cancel\"\n\n" +
                        "Hands-free mode (terminal menu, or automatic for voice-launched sessions):\n" +
                        "• Everything you say is typed into the agent. End with \"send to Claude Code\" or \"send to Codex\" to press Enter.\n" +
                        "• When the agent stops writing, its new output is read aloud. Say \"stop reading\", \"read again\", \"escape\" or \"hands-free off\".\n\n" +
                        "Wake word: works while the phone is unlocked, with the screen off too. On Android 10+ grant \"Display over other apps\" so the app can open itself; " +
                        "otherwise a notification appears instead. Xiaomi/HyperOS also needs \"Display pop-up windows while running in the background\" in the app's permissions.")
                    .setPositiveButton(android.R.string.ok, null).show()
                true
            }
            findPreference<SwitchPreferenceCompat>("wake_word")?.setOnPreferenceChangeListener { pref, value ->
                if (value == true) { enableWakeWord(pref as SwitchPreferenceCompat); false } // switched on once everything is granted
                else { WakeWordService.stop(ctx); true }
            }
            findPreference<Preference>("wake_phrase")?.setOnPreferenceChangeListener { _, value ->
                val ok = value.toString().isNotBlank()
                // The running detector reads the phrase at start; bounce it so the new phrase takes effect.
                if (ok && WakeWordService.isRunning) { WakeWordService.stop(ctx); requireView().postDelayed({ WakeWordService.start(ctx) }, 500) }
                ok
            }
            findPreference<Preference>("storage_access")?.setOnPreferenceClickListener {
                if (Build.VERSION.SDK_INT >= 30) {
                    if (Environment.isExternalStorageManager()) {
                        Toast.makeText(ctx, "Already granted. /sdcard is visible inside Ubuntu.", Toast.LENGTH_SHORT).show()
                    } else {
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                        } catch (e: Exception) {
                            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                } else {
                    requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
                }
                true
            }
            findPreference<Preference>("reinstall")?.setOnPreferenceClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("Reinstall Ubuntu?")
                    .setMessage("All sessions will be stopped and everything inside the Linux environment (including /root and your login sessions) will be deleted.")
                    .setPositiveButton("Delete") { _, _ -> wipe() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            findPreference<Preference>("voice_help")?.setOnPreferenceClickListener {
                val hasDialog = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(ctx.packageManager) != null
                val hasService = android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)
                val onDevice = Build.VERSION.SDK_INT >= 31 && android.speech.SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
                val engine = WhisperEngine(ctx)
                val models = WhisperEngine.Model.entries.filter { engine.isDownloaded(it) }.joinToString { it.key }
                val status = "On this phone:\n" +
                    "• System speech dialog: ${if (hasDialog) "available" else "missing"}\n" +
                    "• Speech recognition service: ${if (hasService) "available" else "missing"}\n" +
                    "• On-device recognizer: ${if (onDevice) "available" else "missing"}\n" +
                    "• Offline Whisper models downloaded: ${models.ifEmpty { "none" }}\n\n"
                AlertDialog.Builder(ctx)
                    .setTitle("Voice input")
                    .setMessage(status + VoiceInput.helpText())
                    .setPositiveButton("Get Google app") { _, _ -> VoiceInput.openStore(ctx, "com.google.android.googlequicksearchbox") }
                    .setNeutralButton("Get Speech Services") { _, _ -> VoiceInput.openStore(ctx, "com.google.android.tts") }
                    .setNegativeButton("Keyboard settings") { _, _ ->
                        try { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } catch (_: Exception) {}
                    }
                    .show()
                true
            }
            findPreference<Preference>("whisper_delete")?.setOnPreferenceClickListener {
                val engine = WhisperEngine(ctx)
                val files = engine.modelsDir().listFiles()?.toList() ?: emptyList()
                if (files.isEmpty()) { Toast.makeText(ctx, "No models downloaded", Toast.LENGTH_SHORT).show(); return@setOnPreferenceClickListener true }
                AlertDialog.Builder(ctx)
                    .setTitle("Delete speech models?")
                    .setMessage(files.joinToString("\n") { "${it.name} (${it.length() / 1_000_000} MB)" })
                    .setPositiveButton("Delete") { _, _ ->
                        engine.unload(); files.forEach { it.delete() }
                        Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            findPreference<Preference>("phantom_help")?.setOnPreferenceClickListener {
                val cmds = "adb shell \"settings put global settings_enable_monitor_phantom_procs false\"\n" +
                    "adb shell \"/system/bin/device_config put activity_manager max_phantom_processes 2147483647\"\n" +
                    "adb shell \"/system/bin/device_config set_sync_disabled_for_tests persistent\""
                AlertDialog.Builder(ctx)
                    .setTitle("Background process limit")
                    .setMessage("Android 12 and newer kill app child processes when more than 32 exist or when the app is in the background for a while. " +
                        "The foreground notification helps, and you can also disable battery optimisation for Pocket-CLI. " +
                        "For a permanent fix, enable USB debugging and run from a computer:\n\n$cmds\n\nNo root required.")
                    .setPositiveButton("Copy commands") { _, _ ->
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("adb", cmds))
                    }
                    .setNeutralButton("Battery settings") { _, _ ->
                        try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            findPreference<Preference>("about")?.setOnPreferenceClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("Pocket-CLI")
                    .setMessage("Runs Claude Code and Codex inside an Ubuntu 24.04 userland on Android, without root.\n\n" +
                        "Built with:\n" +
                        "• proot (GPL-2.0), Termux build with Android patches\n" +
                        "• talloc (LGPL-3.0)\n" +
                        "• libandroid-shmem (MIT), Termux\n" +
                        "• Termux terminal-emulator and terminal-view (GPL-3.0)\n" +
                        "• Apache Commons Compress (Apache-2.0)\n" +
                        "• Ubuntu base image (see Ubuntu licensing)\n\n" +
                        "Claude Code is a product of Anthropic; Codex is a product of OpenAI. Use of each requires your own account and acceptance of their terms.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }
        }

        private var pendingWake: SwitchPreferenceCompat? = null
        private val micPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            val p = pendingWake; pendingWake = null
            if (granted && p != null) enableWakeWord(p)
            else Toast.makeText(requireContext(), "Microphone permission is required for the wake word", Toast.LENGTH_LONG).show()
        }

        /** Turning the wake word on: microphone permission → Whisper Tiny model → overlay permission (optional) → start. */
        private fun enableWakeWord(pref: SwitchPreferenceCompat) {
            val ctx = requireContext()
            if (!WakeWordService.hasMic(ctx)) { pendingWake = pref; micPermission.launch(android.Manifest.permission.RECORD_AUDIO); return }
            val engine = WhisperEngine(ctx)
            val model = WakeWordService.MODEL
            if (!engine.isDownloaded(model)) {
                val dialog = AlertDialog.Builder(ctx).setTitle("Downloading speech model").setMessage("${model.file} (${model.mb} MB)…").setCancelable(false).show()
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        engine.download(model) { f -> requireActivity().runOnUiThread { dialog.setMessage("${model.file}… ${(f * 100).toInt()}%") } }
                        dialog.dismiss(); enableWakeWord(pref)
                    } catch (e: Exception) {
                        dialog.dismiss(); Toast.makeText(ctx, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                return
            }
            Prefs(ctx).wakeWordEnabled = true
            pref.isChecked = true
            WakeWordService.start(ctx)
            if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(ctx)) {
                AlertDialog.Builder(ctx).setTitle("Let Pocket-CLI open itself?")
                    .setMessage("Android only lets an app come to the front from the background when it may \"display over other apps\". " +
                        "Without it, saying \"${Prefs(ctx).wakePhrase}\" shows a notification you tap instead.\n\nOn Xiaomi/HyperOS also allow \"Display pop-up windows while running in the background\".")
                    .setPositiveButton("Open settings") { _, _ ->
                        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))) } catch (_: Exception) {}
                    }
                    .setNegativeButton("Use notification", null).show()
            }
        }

        private fun wipe() {
            val ctx = requireContext().applicationContext
            val dialog = AlertDialog.Builder(requireContext()).setMessage("Deleting…").setCancelable(false).show()
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    TerminalService.instance?.let { svc ->
                        withContext(Dispatchers.Main) { svc.stopAll() }
                    }
                    RootfsInstaller.deleteTree(Env.rootfs(ctx))
                    RootfsInstaller.deleteTree(Env.bridge(ctx))
                    RootfsInstaller.deleteTree(Env.prootTmp(ctx))
                }
                dialog.dismiss()
                Toast.makeText(ctx, "Environment removed", Toast.LENGTH_SHORT).show()
                requireActivity().finish()
            }
        }
    }

    companion object {
        private val CONTAINER_ID = R.id.settings_container
    }
}
