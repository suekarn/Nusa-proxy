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
import com.github.kr328.clash.design.R as DesignR

/**
 * Editor satu config: isi config aktif dibaca ke kolom teks, dan saat disimpan
 * dibuat profil File baru berisi teks tersebut, diaktifkan, lalu profil lama dihapus.
 */
class ConfigEditorActivity : BaseActivity<ConfigEditorDesign>() {
    override suspend fun main() {
        val design = ConfigEditorDesign(this)

        setContentDesign(design)

        design.setText(loadConfig())

        while (isActive) {
            select<Unit> {
                design.requests.onReceive {
                    when (it) {
                        ConfigEditorDesign.Request.Back -> finish()
                        ConfigEditorDesign.Request.Save -> save(design)
                        ConfigEditorDesign.Request.Add -> showAddDialog(design)
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

            if (file.exists()) ProxyLink.unwrap(file.readText()) else ""
        }
    }

    private suspend fun save(design: ConfigEditorDesign) {
        val text = ProxyLink.wrap(design.getText())

        if (text.isBlank()) {
            design.showToast(DesignR.string.config_empty, ToastDuration.Short)

            return
        }

        try {
            val pendingRoot = pendingDir

            withProfile {
                val old = queryActive()
                val uuid = create(Profile.Type.File, "NUSA-PROXY")

                pendingRoot.resolve(uuid.toString()).resolve("config.yaml").writeText(text)

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
        } catch (e: Exception) {
            design.showToast(e.message ?: e.toString(), ToastDuration.Long)
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
