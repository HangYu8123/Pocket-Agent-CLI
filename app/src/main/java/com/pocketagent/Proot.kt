package com.pocketagent

import android.content.Context
import android.system.Os
import java.io.File

class Launch(val exe: String, val cwd: String, val args: Array<String>, val env: Array<String>, val guestCwd: String)

/** Builds the proot command line that enters the Ubuntu rootfs. */
object Proot {

    /** Refreshes bridge files that ship with the APK (safe to call on every launch). */
    fun prepareBridge(c: Context) {
        val bridge = Env.bridge(c)
        bridge.mkdirs()
        Env.shm(c).mkdirs()
        Env.prootTmp(c).mkdirs()
        Env.fakeProc(c).mkdirs()
        c.assets.open("bootstrap.sh").use { input ->
            File(bridge, "bootstrap.sh").outputStream().use { input.copyTo(it) }
        }
        copyAssetTree(c, "skills", File(bridge, "skills"))
        val openUrl = Env.openUrlFile(c)
        if (!openUrl.exists()) openUrl.writeText("")
        FakeProc.write(Env.fakeProc(c))
    }

    /** Recursively copies an assets folder (used for the bundled skills the bootstrap installs). */
    private fun copyAssetTree(c: Context, assetPath: String, dest: File) {
        val kids = c.assets.list(assetPath) ?: return
        if (kids.isEmpty()) { // a file
            dest.parentFile?.mkdirs()
            c.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (k in kids) copyAssetTree(c, "$assetPath/$k", File(dest, k))
    }

    /** Working directory for this launch: the user's workspace for agents/shell, /root for maintenance. */
    fun resolveCwd(c: Context, mode: Mode): String {
        if (mode.isMaintenance) {
            File(Env.rootfs(c), mode.cwd.trimStart('/')).mkdirs()
            return mode.cwd
        }
        val ws = Workspace.current(c)
        if (Workspace.exists(c, ws) || Workspace.create(c, ws) == null) return ws
        File(Env.rootfs(c), Workspace.DEFAULT.trimStart('/')).mkdirs()
        return Workspace.DEFAULT
    }

    fun build(c: Context, mode: Mode): Launch {
        val native = Env.nativeDir(c)
        val rootfs = Env.rootfs(c)
        val prefs = Prefs(c)
        val guestCwd = resolveCwd(c, mode)
        val args = mutableListOf(
            "--kill-on-exit",
            "--link2symlink",
            "-0",
            "-r", rootfs.absolutePath,
            "--kernel-release=6.6.0-pocketagent",
            "-w", guestCwd,
        )
        fun bind(host: String, guest: String = host) {
            if (File(host).exists()) { args += "-b"; args += "$host:$guest" }
        }
        bind("/dev")
        bind("/proc")
        bind("/sys")
        bind("/dev/urandom", "/dev/random")
        bind(Env.shm(c).absolutePath, "/dev/shm")
        for (f in FakeProc.FILES) bind(File(Env.fakeProc(c), f).absolutePath, "/proc/$f")
        bind(Env.bridge(c).absolutePath, Env.GUEST_BRIDGE)
        bind("/sdcard")
        bind("/storage")

        args += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "SHELL=/bin/bash",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "TMPDIR=/tmp",
            "BROWSER=xdg-open",
            "POCKETAGENT=1",
            "/bin/bash", "-l",
        )
        if (mode.command.isNotEmpty()) { args += "-c"; args += mode.command }

        val env = mutableListOf(
            "PROOT_TMP_DIR=${Env.prootTmp(c).absolutePath}",
            "PROOT_LOADER=${File(native, "libproot_loader.so").absolutePath}",
            "PROOT_LOADER_32=${File(native, "libproot_loader32.so").absolutePath}",
            "LD_LIBRARY_PATH=${native.absolutePath}",
            "HOME=${c.filesDir.absolutePath}",
            "TMPDIR=${c.cacheDir.absolutePath}",
            "PATH=/system/bin:/system/xbin",
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
        )
        if (prefs.prootNoSeccomp) env += "PROOT_NO_SECCOMP=1"

        return Launch(File(native, "libproot.so").absolutePath, c.filesDir.absolutePath, args.toTypedArray(), env.toTypedArray(), guestCwd)
    }
}

/**
 * Android forbids apps from reading /proc/stat & friends. Many tools (node, top, python's
 * os.getloadavg) expect them, so we bind plausible static files over them (same trick as
 * termux's proot-distro).
 */
object FakeProc {
    val FILES = listOf("stat", "version", "loadavg", "uptime", "vmstat")

    fun write(dir: File) {
        File(dir, "stat").writeText(
            """
            cpu  10132153 290696 3084719 46828483 16683 0 25195 0 175628 0
            cpu0 1393280 32966 572056 13343292 6130 0 17875 0 23933 0
            cpu1 1252562 34047 424631 13337054 5155 0 3703 0 25047 0
            cpu2 1260640 33741 402457 13334521 3135 0 1878 0 24748 0
            cpu3 1300043 32769 406738 13333015 2263 0 1739 0 24648 0
            intr 10000000 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
            ctxt 500000000
            btime 1700000000
            processes 300000
            procs_running 2
            procs_blocked 0
            softirq 100000000 0 0 0 0 0 0 0 0 0 0

            """.trimIndent()
        )
        File(dir, "version").writeText("Linux version 6.6.0-pocketagent (build@pocketagent) (gcc version 13.2.0) #1 SMP PREEMPT\n")
        File(dir, "loadavg").writeText("0.12 0.07 0.05 1/420 1337\n")
        File(dir, "uptime").writeText("12345.67 40000.00\n")
        File(dir, "vmstat").writeText(
            """
            nr_free_pages 200000
            nr_zone_inactive_anon 10000
            nr_zone_active_anon 50000
            nr_zone_inactive_file 40000
            nr_zone_active_file 40000
            nr_zone_unevictable 0
            nr_zone_write_pending 0
            nr_mlock 0
            nr_free_cma 0
            pgpgin 100000
            pgpgout 100000
            pswpin 0
            pswpout 0
            pgfault 1000000
            pgmajfault 1000

            """.trimIndent()
        )
    }
}

internal fun chmodQuiet(f: File, mode: Int) {
    try { Os.chmod(f.absolutePath, mode) } catch (_: Exception) {}
}
