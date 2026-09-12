package com.pocketagent

import android.content.Context
import android.os.Build
import java.io.File

/** Well-known paths of the Linux environment on the Android side. */
object Env {
    private const val UBUNTU_BASE =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-"

    /** Ubuntu architecture matching the ABI we will run proot from. */
    fun ubuntuArch(): String = when (nativeAbi()) {
        "x86_64" -> "amd64"
        else -> "arm64"
    }

    fun ubuntuUrl(): String = "$UBUNTU_BASE${ubuntuArch()}.tar.gz"

    /** The ABI Android chose for our native libs (first supported 64-bit ABI we ship). */
    fun nativeAbi(): String =
        Build.SUPPORTED_64_BIT_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" } ?: ""

    fun isSupportedAbi() = nativeAbi().isNotEmpty()

    /** Guest path where [bridge] is bind-mounted. */
    const val GUEST_BRIDGE = "/pocketagent"

    fun rootfs(c: Context) = File(c.filesDir, "ubuntu")
    fun bridge(c: Context) = File(c.filesDir, "bridge")
    fun fakeProc(c: Context) = File(bridge(c), "proc")
    fun shm(c: Context) = File(bridge(c), "shm")
    fun prootTmp(c: Context) = File(c.filesDir, "proot-tmp")
    fun rootfsMarker(c: Context) = File(rootfs(c), ".pocketagent-rootfs-ok")
    fun bootstrapMarker(c: Context) = File(bridge(c), ".bootstrap_done")
    fun openUrlFile(c: Context) = File(bridge(c), "open_url")
    fun nativeDir(c: Context) = File(c.applicationInfo.nativeLibraryDir)

    fun hasRootfs(c: Context) = rootfsMarker(c).exists()
    fun isBootstrapped(c: Context) = bootstrapMarker(c).exists()
    fun isReady(c: Context) = hasRootfs(c) && isBootstrapped(c)
}

enum class Mode(val key: String, val title: String, val command: String, val cwd: String = "/root/projects") {
    // Pre-flight through the bootstrap: if the CLI is missing or its launcher is dangling
    // (a half-finished install or update), it is reinstalled before launch instead of the
    // session dying with "claude: not found" (exit 127).
    CLAUDE("claude", "Claude Code", "bash ${Env.GUEST_BRIDGE}/bootstrap.sh --ensure claude && exec claude"),
    CODEX("codex", "Codex", "bash ${Env.GUEST_BRIDGE}/bootstrap.sh --ensure codex && exec codex"),
    SHELL("shell", "Ubuntu", ""),
    SETUP("setup", "Setup", "bash ${Env.GUEST_BRIDGE}/bootstrap.sh", "/root"),
    UPDATE("update", "Update CLIs", "bash ${Env.GUEST_BRIDGE}/bootstrap.sh --update", "/root");

    companion object {
        const val EXTRA = "mode"
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: SHELL
    }
}
