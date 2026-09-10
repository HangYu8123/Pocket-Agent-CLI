package com.pocketagent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File

/**
 * Lets the user look inside the working folder: an in-app list of folders and files (tap a
 * folder to enter it, a file to open it with another app), plus "Open in Files app" for
 * folders on phone storage, which the system file manager can show directly.
 */
object FolderBrowser {
    private const val AUTHORITY = "com.pocketagent.files"

    fun open(activity: Activity, guestPath: String = Workspace.current(activity)) {
        show(activity, Workspace.normalize(guestPath), Workspace.normalize(guestPath))
    }

    private fun show(activity: Activity, path: String, root: String) {
        val dir = Workspace.hostFile(activity, path)
        if (!dir.isDirectory) {
            Toast.makeText(activity, "Folder not found: $path", Toast.LENGTH_SHORT).show(); return
        }
        val entries = (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (Workspace.isPhonePath(path)) {
            labels += "📂  ${activity.getString(R.string.open_in_files_app)}"
            actions += { openInFilesApp(activity, path) }
        }
        if (path != root && path != "/") {
            labels += "⬆️  .."
            actions += { show(activity, path.substringBeforeLast('/').ifEmpty { "/" }, root) }
        }
        if (entries.isEmpty()) {
            labels += "(empty folder)"
            actions += {}
        }
        for (f in entries) {
            if (f.isDirectory) {
                labels += "📁  ${f.name}"
                actions += { show(activity, "$path/${f.name}", root) }
            } else {
                labels += "📄  ${f.name}   ${size(f.length())}"
                actions += { openFile(activity, f, "$path/${f.name}") }
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(path)
            .setItems(labels.toTypedArray()) { _, i -> actions[i]() }
            .setNeutralButton("Copy path") { _, _ ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("path", path))
                Toast.makeText(activity, "Copied $path", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** Asks the system file manager to show a phone-storage folder. */
    fun openInFilesApp(activity: Activity, guestPath: String) {
        val rel = guestPath.removePrefix("/sdcard").removePrefix("/storage/emulated/0").trimStart('/')
        val docId = if (rel.isEmpty()) "primary:" else "primary:$rel"
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "resource/folder"),
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri),
        )
        for (i in attempts) {
            try { activity.startActivity(i); return } catch (_: ActivityNotFoundException) {}
        }
        Toast.makeText(activity, "No file manager app found", Toast.LENGTH_SHORT).show()
    }

    /** Opens a file with whatever app handles its type (text editor, image viewer, …). */
    private fun openFile(activity: Activity, f: File, guestPath: String) {
        val ext = f.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext in CODE_EXT || ext.isEmpty()) "text/plain" else "application/octet-stream"
        val uri: Uri = try { FileProvider.getUriForFile(activity, AUTHORITY, f) } catch (e: Exception) {
            Toast.makeText(activity, "Cannot share $guestPath: ${e.message}", Toast.LENGTH_SHORT).show(); return
        }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(Intent.createChooser(view, f.name))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "No app can open ${f.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun size(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "%.1f MB".format(b / 1048576.0)
    }

    private val CODE_EXT = setOf("py", "js", "ts", "kt", "java", "c", "cpp", "h", "rs", "go", "rb", "sh", "md", "txt", "json", "yaml", "yml", "toml", "ini", "cfg", "csv", "log", "html", "css", "xml", "sql")
}
