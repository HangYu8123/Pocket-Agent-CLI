package com.pocketagent

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Downloads the Ubuntu base rootfs, extracts it (preserving symlinks and modes) and
 * writes the configuration files the environment needs to work under proot.
 */
class RootfsInstaller(private val c: Context) {

    suspend fun install(report: (label: String, fraction: Float) -> Unit) = withContext(Dispatchers.IO) {
        val rootfs = Env.rootfs(c)
        val tarball = File(c.cacheDir, "ubuntu-base.tar.gz")
        if (!tarball.exists()) download(Env.ubuntuUrl(), tarball, report)
        report("Removing old files", 0f)
        deleteTree(rootfs)
        rootfs.mkdirs()
        try {
            extract(tarball, rootfs, report)
        } catch (e: Exception) {
            tarball.delete() // corrupt download? force re-download next time
            throw e
        }
        report("Configuring", 1f)
        configure(rootfs)
        Env.rootfsMarker(c).writeText(Env.ubuntuUrl() + "\n")
        tarball.delete()
    }

    private suspend fun download(url: String, dest: File, report: (String, Float) -> Unit) = withContext(Dispatchers.IO) {
        val tmp = File(dest.path + ".part")
        var current = url
        var conn: HttpURLConnection
        var hops = 0
        while (true) {
            conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", "PocketCLI/1.0")
            }
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) { current = conn.getHeaderField("Location"); conn.disconnect(); continue }
            if (code != 200) throw IOException("HTTP $code downloading $current")
            break
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    ensureActive()
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (done - lastReport > (1 shl 18)) {
                        lastReport = done
                        val mb = done / 1e6
                        val label = if (total > 0) "Downloading Ubuntu 24.04 (%.1f / %.1f MB)".format(mb, total / 1e6)
                        else "Downloading Ubuntu 24.04 (%.1f MB)".format(mb)
                        report(label, if (total > 0) done.toFloat() / total else -1f)
                    }
                }
            }
        }
        if (total > 0 && tmp.length() != total) throw IOException("Download incomplete (${tmp.length()} of $total bytes)")
        if (!tmp.renameTo(dest)) throw IOException("Could not move download into place")
    }

    private class Counting(s: InputStream) : FilterInputStream(s) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    private suspend fun extract(tarball: File, dest: File, report: (String, Float) -> Unit) = withContext(Dispatchers.IO) {
        val total = tarball.length().coerceAtLeast(1)
        val counting = Counting(BufferedInputStream(FileInputStream(tarball), 1 shl 16))
        val destCanon = dest.canonicalPath
        val hardlinks = ArrayList<Pair<File, String>>()
        val dirModes = ArrayList<Pair<File, Int>>()
        var files = 0
        TarArchiveInputStream(GzipCompressorInputStream(counting)).use { tar ->
            while (true) {
                ensureActive()
                val e = tar.nextEntry ?: break
                val name = e.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty() || name == ".") continue
                val out = File(dest, name)
                if (!out.canonicalPath.startsWith(destCanon)) continue // path traversal guard
                when {
                    e.isDirectory -> { out.mkdirs(); dirModes += out to e.mode }
                    e.isSymbolicLink -> {
                        out.parentFile?.mkdirs(); out.delete()
                        try { Os.symlink(e.linkName, out.absolutePath) } catch (ex: Exception) { throw IOException("symlink $name: ${ex.message}") }
                    }
                    e.isLink -> hardlinks += out to e.linkName.removePrefix("./")
                    e.isFile -> {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tar.copyTo(it, 1 shl 16) }
                        chmodQuiet(out, e.mode or 0x180 /* u+rw */)
                    }
                    else -> { /* device nodes, fifos: not creatable, not needed */ }
                }
                if (++files % 200 == 0) report("Extracting Ubuntu ($files files)", counting.count.toFloat() / total)
            }
        }
        for ((out, target) in hardlinks) {
            val src = File(dest, target)
            if (!src.exists()) continue
            out.parentFile?.mkdirs()
            src.copyTo(out, overwrite = true)
            chmodQuiet(out, 0x1ED /* 0755 */)
        }
        for ((dir, mode) in dirModes) chmodQuiet(dir, mode or 0x1C0 /* u+rwx */)
        report("Extracting Ubuntu ($files files)", 1f)
    }

    private fun configure(rootfs: File) {
        fun put(path: String, content: String, mode: Int = 0x1A4 /* 0644 */) {
            val f = File(rootfs, path)
            f.parentFile?.mkdirs()
            if (f.exists() || Files.isSymbolicLink(f.toPath())) f.delete()
            f.writeText(content)
            chmodQuiet(f, mode)
        }
        put("etc/resolv.conf", "nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        put("etc/hosts", "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        put("etc/hostname", "pocketagent\n")
        put("etc/apt/apt.conf.d/99pocketagent",
            "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\nDpkg::Options { \"--force-confdef\"; \"--force-confold\"; };\n")
        put("etc/profile.d/pocketagent.sh",
            """
            export BROWSER=xdg-open
            export EDITOR=nano
            export PAGER=less
            export npm_config_fund=false
            export npm_config_update_notifier=false
            export PIP_BREAK_SYSTEM_PACKAGES=1
            export PYTHONUNBUFFERED=1
            [ -d /usr/local/bin ] && case ":${'$'}PATH:" in *:/usr/local/bin:*) ;; *) export PATH=/usr/local/bin:${'$'}PATH ;; esac

            """.trimIndent())
        // Ubuntu 24.04 marks its Python as "externally managed"; on a phone a plain
        // `pip install` should just work.
        put("etc/pip.conf", "[global]\nbreak-system-packages = true\n")
        put("usr/local/bin/xdg-open",
            """
            #!/bin/sh
            # Pocket-CLI shim: hand URLs to the Android side, which opens them in the browser.
            for u in "${'$'}@"; do printf '%s\n' "${'$'}u" >> ${Env.GUEST_BRIDGE}/open_url; done
            echo "Opening in Android browser: ${'$'}*" >&2
            exit 0

            """.trimIndent(), 0x1ED)
        for (alias in listOf("sensible-browser", "www-browser", "x-www-browser", "gnome-open", "open")) {
            val f = File(rootfs, "usr/local/bin/$alias")
            f.delete()
            try { Os.symlink("xdg-open", f.absolutePath) } catch (_: Exception) {}
        }
        put("root/.hushlogin", "")
        put("root/.profile", "[ -f ~/.bashrc ] && . ~/.bashrc\n")
        put("root/.bashrc",
            """
            # Pocket-CLI default shell configuration
            [ -z "${'$'}PS1" ] && return
            export PS1='\[\e[1;36m\]\u@pocket\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\]\${'$'} '
            alias ll='ls -la --color=auto'
            alias ls='ls --color=auto'
            alias cc='claude'
            if [ -z "${'$'}POCKETAGENT_WELCOMED" ]; then
              export POCKETAGENT_WELCOMED=1
              echo "Ubuntu $(. /etc/os-release && echo ${'$'}VERSION_ID) on Android. Try: claude, codex, python3, git, apt."
            fi

            """.trimIndent())
        File(rootfs, "root/projects").mkdirs()
        // Codex's Linux sandbox relies on Landlock + seccomp, which proot cannot provide, so
        // command execution would fail. The whole Ubuntu userland already lives inside the
        // app's private storage, so let Codex run commands directly.
        if (!File(rootfs, "root/.codex/config.toml").exists()) {
            put("root/.codex/config.toml",
                """
                # Written by Pocket-CLI. Landlock is unavailable under proot, so Codex's
                # built-in sandbox cannot start; the Ubuntu environment itself is isolated.
                sandbox_mode = "danger-full-access"
                approval_policy = "on-request"

                """.trimIndent())
        }
        File(rootfs, "tmp").mkdirs().also { chmodQuiet(File(rootfs, "tmp"), 0x3FF /* 1777 */) }
    }

    companion object {
        /** Deletes a tree without following symlinks (rootfs trees contain many). */
        fun deleteTree(root: File) {
            if (!root.exists() && !Files.isSymbolicLink(root.toPath())) return
            Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
                }
            })
        }
    }
}
