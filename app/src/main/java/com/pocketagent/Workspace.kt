package com.pocketagent

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.preference.PreferenceManager
import java.io.File

/**
 * The folder that agents and shells start in, expressed as a path *inside* Ubuntu.
 * Ubuntu paths live in the app's private rootfs; /sdcard and /storage paths are the phone's
 * shared storage, which proot bind-mounts at the same location.
 */
object Workspace {
    const val DEFAULT = "/root/projects"
    private const val KEY = "workspace"

    class Location(val label: String, val guestPath: String)

    fun locations(c: Context): List<Location> = listOf(
        Location("Ubuntu home · ~/projects", DEFAULT),
        Location("Ubuntu home · ~", "/root"),
        Location("Phone · Pocket-CLI folder (no permission needed)", appExternalGuestPath(c)),
        Location("Phone · Documents", "/sdcard/Documents"),
        Location("Phone · Download", "/sdcard/Download"),
        Location("Phone · storage root", "/sdcard"),
    )

    /** /sdcard/Android/data/<pkg>/files: writable without any permission and visible to file managers. */
    fun appExternalGuestPath(c: Context): String = "/sdcard/Android/data/${c.packageName}/files"

    fun current(c: Context): String =
        PreferenceManager.getDefaultSharedPreferences(c).getString(KEY, DEFAULT) ?: DEFAULT

    fun set(c: Context, guestPath: String) {
        PreferenceManager.getDefaultSharedPreferences(c).edit().putString(KEY, normalize(guestPath)).apply()
    }

    fun normalize(p: String): String {
        val t = p.trim().replace(Regex("/+"), "/").trimEnd('/')
        return when {
            t.isEmpty() -> "/"
            t.startsWith("/") -> t
            t.startsWith("~") -> "/root" + t.removePrefix("~")
            else -> "$DEFAULT/$t"
        }
    }

    fun isPhonePath(guestPath: String) = guestPath.startsWith("/sdcard") || guestPath.startsWith("/storage")

    /** Host-side file for a guest path. */
    fun hostFile(c: Context, guestPath: String): File =
        if (isPhonePath(guestPath)) File(guestPath) else File(Env.rootfs(c), guestPath.trimStart('/'))

    fun exists(c: Context, guestPath: String) = hostFile(c, guestPath).isDirectory

    fun needsAllFilesAccess(c: Context, guestPath: String): Boolean {
        if (!isPhonePath(guestPath)) return false
        if (guestPath.startsWith(appExternalGuestPath(c))) return false
        return Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()
    }

    /** Valid folder name: no path separators, not hidden, not "." or "..". */
    fun isValidName(name: String) =
        name.isNotBlank() && !name.contains('/') && !name.contains('\\') && name != "." && name != ".." && name.length <= 128

    /** Creates the folder (parents included). Returns null on success or a human-readable error. */
    fun create(c: Context, guestPath: String): String? {
        val p = normalize(guestPath)
        if (needsAllFilesAccess(c, p)) {
            return "Creating folders under $p needs \"All files access\". Grant it in Settings → Allow access to phone storage, or use the Pocket-CLI folder instead."
        }
        if (isPhonePath(p) && p.startsWith(appExternalGuestPath(c))) c.getExternalFilesDir(null) // make sure the base exists
        val f = hostFile(c, p)
        if (f.isDirectory) return null
        return try {
            if (f.mkdirs() || f.isDirectory) null else "Could not create $p"
        } catch (e: Exception) {
            "Could not create $p: ${e.message}"
        }
    }

    fun children(c: Context, guestPath: String): List<String> =
        hostFile(c, guestPath).listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.map { it.name }?.sorted() ?: emptyList()
}
