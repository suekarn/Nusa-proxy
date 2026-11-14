package com.github.kr328.clash

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.ConfigFilesDesign
import com.github.kr328.clash.design.dialog.requestModelTextInput
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.util.fileName
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import com.github.kr328.clash.design.R as DesignR

/**
 * Penjelajah berkas untuk folder profil aktif: lihat, buat, ubah nama, hapus, impor, ekspor,
 * dan sunting berkas YAML (config, proxy_provider, rule_provider, dst).
 */
class ConfigFilesActivity : BaseActivity<ConfigFilesDesign>() {
    companion object {
        const val MAIN_CONFIG = "config.yaml"

        private const val MAX_EDIT_BYTES = 2L * 1024 * 1024

        /** Folder yang disiapkan otomatis karena umum dipakai `path:` provider. */
        private val SEED_DIRS = listOf("proxy_provider", "rule_provider")
    }

    override suspend fun main() {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) {
            Toast.makeText(this, DesignR.string.no_profile_selected, Toast.LENGTH_LONG).show()

            return finish()
        }

        val root = importedDir.resolve(active.uuid.toString())
        val design = ConfigFilesDesign(this)
        val stack = ArrayList<String>()

        withContext(Dispatchers.IO) {
            SEED_DIRS.forEach { root.resolve(it).mkdirs() }
        }

        setContentDesign(design)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                design.requests.trySend(ConfigFilesDesign.Request.Back)
            }
        })

        design.refresh(root, stack)

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> design.refresh(root, stack)
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    try {
                        handle(design, root, stack, it)
                    } catch (e: Exception) {
                        design.showExceptionToast(e)
                    }

                    design.refresh(root, stack)
                }
            }
        }
    }

    private fun currentDir(root: File, stack: List<String>): File =
        stack.fold(root) { file, name -> file.resolve(name) }

    private suspend fun ConfigFilesDesign.refresh(root: File, stack: List<String>) {
        val dir = currentDir(root, stack)
        val entries = withContext(Dispatchers.IO) { listEntries(dir, stack.isEmpty()) }

        setEntries(stack.joinToString("/"), entries)
    }

    private fun listEntries(dir: File, atRoot: Boolean): List<ConfigFilesDesign.Entry> {
        val children = dir.listFiles() ?: return emptyList()

        return children
            .filter { !it.name.startsWith(".") && !(atRoot && it.name == "ui") }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .map { ConfigFilesDesign.Entry(it.name, it.isDirectory, it.length(), it.lastModified()) }
    }

    private suspend fun handle(
        design: ConfigFilesDesign,
        root: File,
        stack: ArrayList<String>,
        request: ConfigFilesDesign.Request,
    ) {
        val dir = currentDir(root, stack)

        when (request) {
            ConfigFilesDesign.Request.Back -> {
                if (stack.isEmpty()) {
                    finish()
                } else {
                    stack.removeAt(stack.size - 1)
                }
            }
            ConfigFilesDesign.Request.NewFolder -> {
                val name = askName(DesignR.string.cfg_new_folder, "") ?: return
                val target = dir.resolve(name)

                if (target.exists()) {
                    design.showToast(DesignR.string.cfg_exists, ToastDuration.Short)

                    return
                }

                withContext(Dispatchers.IO) { target.mkdirs() }
            }
            ConfigFilesDesign.Request.NewFile -> {
                var name = askName(DesignR.string.cfg_new_file, "") ?: return

                if (!name.contains('.')) name += ".yaml"

                val target = dir.resolve(name)

                if (target.exists()) {
                    design.showToast(DesignR.string.cfg_exists, ToastDuration.Short)

                    return
                }

                val template = chooseTemplate() ?: return
                val relative = (stack + name).joinToString("/")

                withContext(Dispatchers.IO) {
                    target.writeText(FileTemplates.build(template, relative))
                }

                startActivity(
                    FileEditorActivity::class.intent.putExtra(FileEditorActivity.EXTRA_PATH, relative)
                )
            }
            ConfigFilesDesign.Request.ImportFile -> {
                val uri: Uri? = startActivityForResult(ActivityResultContracts.GetContent(), "*/*")

                if (uri != null) {
                    val name = uniqueName(dir, safeName(displayName(uri)))

                    withContext(Dispatchers.IO) {
                        contentResolver.openInputStream(uri)?.use { input ->
                            dir.resolve(name).outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
            }
            is ConfigFilesDesign.Request.Open -> {
                val entry = request.entry

                if (entry.isDirectory) {
                    stack.add(entry.name)

                    return
                }

                if (stack.isEmpty() && entry.name == MAIN_CONFIG) {
                    // config utama punya editor sendiri (validasi + pembungkus proxy + blok iklan)
                    startActivity(ConfigEditorActivity::class.intent)

                    return
                }

                val file = dir.resolve(entry.name)
                val editable = withContext(Dispatchers.IO) { isEditable(file) }

                if (!editable) {
                    design.showToast(DesignR.string.cfg_not_editable, ToastDuration.Long)

                    return
                }

                startActivity(
                    FileEditorActivity::class.intent.putExtra(
                        FileEditorActivity.EXTRA_PATH,
                        (stack + entry.name).joinToString("/")
                    )
                )
            }
            is ConfigFilesDesign.Request.Rename -> {
                val entry = request.entry

                if (stack.isEmpty() && entry.name == MAIN_CONFIG) {
                    design.showToast(DesignR.string.cfg_protected, ToastDuration.Long)

                    return
                }

                val name = askName(DesignR.string.cfg_rename, entry.name) ?: return
                val target = dir.resolve(name)

                if (target.exists()) {
                    design.showToast(DesignR.string.cfg_exists, ToastDuration.Short)

                    return
                }

                withContext(Dispatchers.IO) { dir.resolve(entry.name).renameTo(target) }
            }
            is ConfigFilesDesign.Request.Delete -> {
                val entry = request.entry

                if (stack.isEmpty() && entry.name == MAIN_CONFIG) {
                    design.showToast(DesignR.string.cfg_protected, ToastDuration.Long)

                    return
                }

                if (!confirm(getString(DesignR.string.cfg_delete_confirm, entry.name))) return

                withContext(Dispatchers.IO) { dir.resolve(entry.name).deleteRecursively() }
            }
            is ConfigFilesDesign.Request.Export -> {
                val entry = request.entry
                val uri: Uri? = startActivityForResult(
                    ActivityResultContracts.CreateDocument("text/plain"),
                    entry.name
                )

                if (uri != null) {
                    withContext(Dispatchers.IO) {
                        contentResolver.openOutputStream(uri)?.use { output ->
                            dir.resolve(entry.name).inputStream().use { input -> input.copyTo(output) }
                        }
                    }
                }
            }
        }
    }

    private fun isSafeName(name: String): Boolean {
        return name.isNotBlank() &&
                name != "." &&
                name != ".." &&
                !name.contains('/') &&
                !name.contains('\\') &&
                !name.contains('\u0000')
    }

    /** Nama baru dari pengguna, atau null bila dibatalkan / tidak valid / tidak berubah dari [initial]. */
    private suspend fun askName(title: Int, initial: String): String? {
        val result = requestModelTextInput(
            initial = initial,
            title = getText(title),
            hint = getText(DesignR.string.file_name),
            error = getText(DesignR.string.invalid_file_name),
            validator = { isSafeName(it.trim()) },
        ).trim()

        if (result.isEmpty() || result == initial) return null

        return result
    }

    private fun safeName(raw: String?): String {
        val cleaned = raw?.replace('/', '_')?.replace('\\', '_')?.trim()

        return if (cleaned != null && isSafeName(cleaned)) cleaned else "imported.txt"
    }

    private fun uniqueName(dir: File, name: String): String {
        if (!dir.resolve(name).exists()) return name

        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1

        while (dir.resolve("$base ($index)$ext").exists()) index++

        return "$base ($index)$ext"
    }

    private fun displayName(uri: Uri): String? {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                        if (column >= 0) return cursor.getString(column)
                    }
                }
        } catch (e: Exception) {
            // jatuh ke nama dari URI
        }

        return uri.fileName
    }

    /** Berkas teks berukuran wajar: bukan biner (tidak ada byte NUL di awal berkas). */
    private fun isEditable(file: File): Boolean {
        if (!file.isFile || file.length() > MAX_EDIT_BYTES) return false

        val head = ByteArray(8192)
        val read = file.inputStream().use { it.read(head) }

        for (i in 0 until maxOf(read, 0)) {
            if (head[i].toInt() == 0) return false
        }

        return true
    }

    private suspend fun confirm(message: CharSequence): Boolean {
        return suspendCancellableCoroutine { continuation ->
            AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(DesignR.string.ok) { _, _ ->
                    if (continuation.isActive) continuation.resume(true)
                }
                .setNegativeButton(DesignR.string.cancel) { _, _ ->
                    if (continuation.isActive) continuation.resume(false)
                }
                .setOnCancelListener {
                    if (continuation.isActive) continuation.resume(false)
                }
                .show()
        }
    }

    private suspend fun chooseTemplate(): Int? {
        return suspendCancellableCoroutine { continuation ->
            val items = arrayOf(
                getString(DesignR.string.cfg_template_empty),
                getString(DesignR.string.cfg_template_proxy_provider),
                getString(DesignR.string.cfg_template_rule_provider),
            )

            AlertDialog.Builder(this)
                .setTitle(DesignR.string.cfg_template_title)
                .setItems(items) { _, which ->
                    if (continuation.isActive) continuation.resume(which)
                }
                .setOnCancelListener {
                    if (continuation.isActive) continuation.resume(null)
                }
                .show()
        }
    }
}
