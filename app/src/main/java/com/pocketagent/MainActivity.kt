package com.pocketagent

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.pocketagent.databinding.ActivityMainBinding
import com.pocketagent.databinding.ItemLauncherBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var installJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }

        setupCard(b.cardClaude, getString(R.string.launch_claude), getString(R.string.launch_claude_sub), R.color.accent_claude, Mode.CLAUDE)
        setupCard(b.cardCodex, getString(R.string.launch_codex), getString(R.string.launch_codex_sub), R.color.accent_codex, Mode.CODEX)
        setupCard(b.cardShell, getString(R.string.launch_shell), getString(R.string.launch_shell_sub), R.color.accent, Mode.SHELL)

        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnInstall.setOnClickListener { onInstallClicked() }
        b.btnChooseFolder.setOnClickListener { chooseFolder() }
        b.btnNewFolder.setOnClickListener { newFolder() }
        b.btnOpenFolder.setOnClickListener { FolderBrowser.open(this) }
        b.hint.text = "Tip: agents start in the working folder above. In the terminal, tap the microphone to dictate. " +
            "Login links from Claude Code and Codex open in your browser automatically."
    }

    // ---------------------------------------------------------------- working folder

    private fun refreshWorkspace() {
        val ws = Workspace.current(this)
        b.workspacePath.text = ws
        val note = when {
            Workspace.needsAllFilesAccess(this, ws) -> "Needs \"All files access\" (Settings → Allow access to phone storage)."
            !Workspace.exists(this, ws) -> getString(R.string.workspace_missing)
            else -> null
        }
        b.workspaceNote.text = note
        b.workspaceNote.visibility = if (note == null) View.GONE else View.VISIBLE
    }

    /** Pick a location, then drill into sub-folders or create one there. */
    private fun chooseFolder() {
        val locs = Workspace.locations(this)
        val labels = locs.map { "${it.label}\n${it.guestPath}" } + getString(R.string.workspace_custom_path)
        AlertDialog.Builder(this)
            .setTitle(R.string.workspace_pick_location)
            .setItems(labels.toTypedArray()) { _, i -> if (i < locs.size) browse(locs[i].guestPath) else customPath() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun browse(base: String) {
        val kids = Workspace.children(this, base)
        val items = listOf(getString(R.string.workspace_use_this), getString(R.string.workspace_new)) + kids.map { "📁 $it" }
        AlertDialog.Builder(this)
            .setTitle(base)
            .setItems(items.toTypedArray()) { _, i ->
                when (i) {
                    0 -> applyWorkspace(base)
                    1 -> askFolderName(base)
                    else -> browse("$base/${kids[i - 2]}")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun customPath() {
        val input = EditText(this).apply {
            hint = "/root/projects/my-app  or  /sdcard/Documents/my-app"
            setText(Workspace.current(this@MainActivity))
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.workspace_custom_path)
            .setView(padded(input))
            .setPositiveButton(android.R.string.ok) { _, _ -> applyWorkspace(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** "New folder…": pick where, then name it; the new folder becomes the working folder. */
    private fun newFolder() {
        val locs = Workspace.locations(this)
        val current = Workspace.current(this)
        val labels = listOf("Current working folder\n$current") + locs.map { "${it.label}\n${it.guestPath}" }
        AlertDialog.Builder(this)
            .setTitle(R.string.workspace_pick_location)
            .setItems(labels.toTypedArray()) { _, i -> askFolderName(if (i == 0) current else locs[i - 1].guestPath) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askFolderName(base: String) {
        val input = EditText(this).apply { hint = getString(R.string.workspace_folder_name); isSingleLine = true }
        AlertDialog.Builder(this)
            .setTitle("New folder in $base")
            .setView(padded(input))
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (!Workspace.isValidName(name)) { toast("Invalid folder name"); return@setPositiveButton }
                val path = Workspace.normalize("$base/$name")
                val err = Workspace.create(this, path)
                if (err == null) {
                    Workspace.set(this, path)
                    refreshWorkspace()
                    toast(getString(R.string.workspace_created))
                } else showFolderError(err, path)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyWorkspace(path: String) {
        val p = Workspace.normalize(path)
        if (Workspace.needsAllFilesAccess(this, p)) {
            showFolderError("Using $p needs \"All files access\" so Ubuntu can write there.", p); return
        }
        val err = if (Workspace.exists(this, p)) null else Workspace.create(this, p)
        if (err != null) { showFolderError(err, p); return }
        Workspace.set(this, p)
        refreshWorkspace()
    }

    private fun showFolderError(err: String, path: String) {
        val builder = AlertDialog.Builder(this).setTitle("Cannot use folder").setMessage(err)
            .setNegativeButton(android.R.string.cancel, null)
        if (Build.VERSION.SDK_INT >= 30 && Workspace.needsAllFilesAccess(this, path)) {
            builder.setPositiveButton("Grant access") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        }
        builder.show()
    }

    private fun padded(v: View): View = FrameLayout(this).apply {
        val p = (20 * resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, 0)
        addView(v)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun setupCard(card: ItemLauncherBinding, title: String, sub: String, color: Int, mode: Mode) {
        card.title.text = title
        card.subtitle.text = sub
        card.accent.setBackgroundColor(ContextCompat.getColor(this, color))
        card.root.setOnClickListener {
            if (!Env.isReady(this)) {
                AlertDialog.Builder(this).setMessage(R.string.status_not_installed)
                    .setPositiveButton(android.R.string.ok, null).show()
                return@setOnClickListener
            }
            TerminalActivity.launch(this, mode)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        if (installJob?.isActive == true) return
        val ready = Env.isReady(this)
        val hasRootfs = Env.hasRootfs(this)
        b.statusText.text = when {
            ready -> getString(R.string.status_ready)
            hasRootfs -> getString(R.string.status_partial)
            else -> getString(R.string.status_not_installed)
        }
        b.btnInstall.visibility = if (ready) View.GONE else View.VISIBLE
        b.btnInstall.text = getString(if (hasRootfs) R.string.resume_setup else R.string.install_env)
        b.btnInstall.isEnabled = true
        b.progressBar.visibility = View.GONE
        b.progressText.visibility = View.GONE
        b.launchers.alpha = if (ready) 1f else 0.45f
        refreshWorkspace()

        val running = TerminalService.instance?.runningModes() ?: emptySet()
        b.cardClaude.badge.visibility = if (Mode.CLAUDE.key in running) View.VISIBLE else View.GONE
        b.cardCodex.badge.visibility = if (Mode.CODEX.key in running) View.VISIBLE else View.GONE
        b.cardShell.badge.visibility = if (Mode.SHELL.key in running) View.VISIBLE else View.GONE
    }

    private fun onInstallClicked() {
        if (!Env.isSupportedAbi()) {
            AlertDialog.Builder(this).setMessage(R.string.unsupported_abi).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        if (Env.hasRootfs(this)) {
            // Rootfs present but bootstrap incomplete: just re-run the (idempotent) bootstrap.
            TerminalActivity.launch(this, Mode.SETUP)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Install Ubuntu 24.04")
            .setMessage("This downloads about 30 MB now and then installs Node.js, Python, git, Claude Code and Codex inside Ubuntu (roughly 400 MB, 5–15 minutes depending on your phone and network). Keep the app open during the download; the second phase can run in the background.")
            .setPositiveButton("Install") { _, _ -> startInstall() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startInstall() {
        b.btnInstall.isEnabled = false
        b.progressBar.visibility = View.VISIBLE
        b.progressText.visibility = View.VISIBLE
        b.progressBar.isIndeterminate = true
        b.statusText.text = "Installing Ubuntu environment…"
        installJob = lifecycleScope.launch {
            try {
                RootfsInstaller(this@MainActivity).install { label, fraction ->
                    lifecycleScope.launch(Dispatchers.Main) {
                        b.progressText.text = label
                        if (fraction < 0) b.progressBar.isIndeterminate = true
                        else { b.progressBar.isIndeterminate = false; b.progressBar.progress = (fraction * 1000).toInt() }
                    }
                }
                withContext(Dispatchers.Main) {
                    refresh()
                    TerminalActivity.launch(this@MainActivity, Mode.SETUP)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    refresh()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Installation failed")
                        .setMessage(e.toString())
                        .setPositiveButton("Retry") { _, _ -> startInstall() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }
}
