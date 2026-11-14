package com.github.kr328.clash

import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.design.ConfigEditorDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.File
import com.github.kr328.clash.design.R as DesignR

/**
 * Editor satu config: isi config aktif dibaca ke kolom teks, dan saat disimpan
 * dibuat profil File baru berisi teks tersebut, diaktifkan, lalu profil lama dihapus.
 */
class ConfigEditorActivity : BaseActivity<ConfigEditorDesign>() {
    /** True bila config aktif memuat blok iklan (diatur lewat menu "Block ads"). */
    private var adBlock = false

    override suspend fun main() {
        val design = ConfigEditorDesign(this)

        setContentDesign(design)

        design.setText(loadConfig())
        design.adBlockEnabled = adBlock

        while (isActive) {
            select<Unit> {
                design.requests.onReceive {
                    when (it) {
                        ConfigEditorDesign.Request.Back -> finish()
                        ConfigEditorDesign.Request.Save -> {
                            save(design)
                            Unit
                        }
                        ConfigEditorDesign.Request.Add -> showAddDialog(design)
                        ConfigEditorDesign.Request.OpenFiles ->
                            startActivity(ConfigFilesActivity::class.intent)
                        ConfigEditorDesign.Request.ToggleAdBlock -> toggleAdBlock(design)
                        ConfigEditorDesign.Request.OpenDashboard ->
                            if (clashRunning) {
                                startActivity(DashboardActivity::class.intent)
                            } else {
                                design.showToast(DesignR.string.dashboard_connect_first, ToastDuration.Long)
                            }
                    }
                }
            }
        }
    }

    private suspend fun loadConfig(): String {
        val active = withProfile { queryActive() }

        if (active == null || !active.imported) return ""

        return withContext(Dispatchers.IO) {
            val file = importedDir.resolve(active.uuid.toString()).resolve("config.yaml")

            if (file.exists()) {
                val raw = file.readText()

                adBlock = AdBlock.isEnabled(raw)

                // blok iklan dikelola aplikasi: disembunyikan dari editor, disisipkan lagi saat simpan
                ProxyLink.unwrap(AdBlock.remove(raw))
            } else {
                adBlock = false

                ""
            }
        }
    }

    /** Simpan config. Mengembalikan true bila berhasil. */
    private suspend fun save(design: ConfigEditorDesign): Boolean {
        var text = ProxyLink.wrap(design.getText())

        if (text.isBlank()) {
            design.showToast(DesignR.string.config_empty, ToastDuration.Short)

            return false
        }

        if (adBlock) {
            val withAds = AdBlock.add(text)

            if (withAds == null) {
                design.showToast(DesignR.string.cfg_adblock_unsupported, ToastDuration.Long)

                return false
            }

            text = withAds
        } else {
            text = AdBlock.remove(text)
        }

        return try {
            val pendingRoot = pendingDir
            val importedRoot = importedDir
            val keepAdList = adBlock

            withProfile {
                val old = queryActive()
                val uuid = create(Profile.Type.File, "NUSA-PROXY")
                val pending = pendingRoot.resolve(uuid.toString())

                pending.resolve("config.yaml").writeText(text)

                // berkas lain di folder profil lama (provider, aturan buatan sendiri) ikut dibawa
                if (old != null) {
                    carryOverFiles(importedRoot.resolve(old.uuid.toString()), pending, keepAdList)
                }

                commit(uuid)

                queryByUUID(uuid)?.let { setActive(it) }

                try {
                    Dashboard.install(this@ConfigEditorActivity, uuid)
                } catch (e: Exception) {
                    // dashboard bersifat tambahan
                }

                if (old != null && old.uuid != uuid) {
                    delete(old.uuid)
                }
            }

            design.showToast(DesignR.string.config_saved, ToastDuration.Short)

            true
        } catch (e: Exception) {
            design.showToast(e.message ?: e.toString(), ToastDuration.Long)

            false
        }
    }

    /**
     * Salin isi folder profil lama ke folder profil baru, kecuali config.yaml (baru saja ditulis),
     * `ui` (dipasang ulang oleh Dashboard) dan berkas tersembunyi. Berusaha sebaik mungkin:
     * kegagalan menyalin tidak membatalkan penyimpanan config.
     */
    private fun carryOverFiles(source: File, target: File, keepAdList: Boolean) {
        if (!source.isDirectory) return

        source.listFiles()?.forEach { child ->
            if (child.name == "config.yaml" || child.name == "ui" || child.name.startsWith(".")) {
                return@forEach
            }

            try {
                child.copyRecursively(target.resolve(child.name), overwrite = false)
            } catch (e: Exception) {
                // lewati berkas yang gagal disalin
            }
        }

        if (!keepAdList) {
            try {
                target.resolve(AdBlock.PROVIDER_PATH).delete()
            } catch (e: Exception) {
                // abaikan
            }
        }
    }

    private suspend fun toggleAdBlock(design: ConfigEditorDesign) {
        val previous = adBlock

        adBlock = !previous

        if (save(design)) {
            design.adBlockEnabled = adBlock
            design.showToast(
                if (adBlock) DesignR.string.cfg_adblock_on else DesignR.string.cfg_adblock_off,
                ToastDuration.Short
            )
        } else {
            adBlock = previous
        }
    }

    private fun clipboardLink(): String? {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = manager?.primaryClip

        if (clip == null || clip.itemCount == 0) return null

        val text = clip.getItemAt(0).text?.toString()?.trim() ?: return null

        return text.takeIf {
            it.startsWith("vmess://", ignoreCase = true) ||
                    it.startsWith("trojan://", ignoreCase = true)
        }
    }

    private fun showAddDialog(design: ConfigEditorDesign) {
        val input = EditText(this).apply {
            setHint(DesignR.string.paste_proxy_hint)
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE

            clipboardLink()?.let { setText(it) }
        }

        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle(DesignR.string.add_proxy_link)
            .setView(container)
            .setPositiveButton(DesignR.string.add_proxy) { _, _ ->
                val lines = input.text.toString().lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val blocks = lines.mapNotNull { ProxyLink.convert(it) }

                launch {
                    if (blocks.isEmpty()) {
                        design.showToast(DesignR.string.link_invalid, ToastDuration.Long)

                        return@launch
                    }

                    val current = design.getText().trimEnd()
                    val prefix = if (current.isEmpty()) "" else current + "\n"

                    design.setText(prefix + blocks.joinToString("\n").trimEnd() + "\n")

                    if (blocks.size < lines.size) {
                        design.showToast(DesignR.string.link_some_invalid, ToastDuration.Long)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
