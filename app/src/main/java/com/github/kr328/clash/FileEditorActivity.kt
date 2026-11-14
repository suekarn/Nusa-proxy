package com.github.kr328.clash

import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.design.FileEditorDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.util.importedDir
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
 * Editor teks untuk satu berkas di folder profil aktif (bukan config.yaml utama;
 * itu punya [ConfigEditorActivity]). Lokasi berkas dikirim lewat [EXTRA_PATH],
 * relatif terhadap folder profil.
 */
class FileEditorActivity : BaseActivity<FileEditorDesign>() {
    companion object {
        const val EXTRA_PATH = "path"
    }

    override suspend fun main() {
        val relative = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) return finish()

        val root = importedDir.resolve(active.uuid.toString())
        val file = root.resolve(relative)

        if (!isInside(root, file) || !file.isFile) return finish()

        val design = FileEditorDesign(this, file.name)
        val text = withContext(Dispatchers.IO) { file.readText() }

        setContentDesign(design)

        design.setText(text)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                design.requests.trySend(FileEditorDesign.Request.Back)
            }
        })

        while (isActive) {
            select<Unit> {
                design.requests.onReceive {
                    when (it) {
                        FileEditorDesign.Request.Save -> {
                            try {
                                val content = design.getText()

                                withContext(Dispatchers.IO) { file.writeText(content) }

                                design.markSaved()
                                design.showToast(DesignR.string.cfg_file_saved, ToastDuration.Short)
                            } catch (e: Exception) {
                                design.showExceptionToast(e)
                            }
                        }
                        FileEditorDesign.Request.Back -> {
                            if (!design.modified || confirmDiscard()) finish()
                        }
                    }
                }
            }
        }
    }

    /** Pastikan berkas berada di dalam folder profil (cegah `../`). */
    private fun isInside(root: File, file: File): Boolean {
        return file.canonicalPath.startsWith(root.canonicalPath + File.separator)
    }

    private suspend fun confirmDiscard(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            AlertDialog.Builder(this)
                .setMessage(DesignR.string.cfg_discard)
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
}
