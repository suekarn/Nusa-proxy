package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Penjelajah berkas untuk folder config profil aktif (config, proxy_provider, rule_provider, dst).
 */
class ConfigFilesDesign(context: Context) : Design<ConfigFilesDesign.Request>(context) {
    class Entry(
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val modified: Long,
    )

    sealed class Request {
        object Back : Request()
        object NewFile : Request()
        object NewFolder : Request()
        object ImportFile : Request()
        class Open(val entry: Entry) : Request()
        class Rename(val entry: Entry) : Request()
        class Delete(val entry: Entry) : Request()
        class Export(val entry: Entry) : Request()
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private val onSurface: Int
        get() = context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface)

    private fun iconButton(icon: Int, onClick: (View) -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(icon)
            setBackgroundResource(android.R.color.transparent)
            setColorFilter(onSurface)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { onClick(this) }
        }
    }

    private fun selectableBackground(): Int {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))

        try {
            return attrs.getResourceId(0, 0)
        } finally {
            attrs.recycle()
        }
    }

    private val pathView = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 12f
        alpha = 0.7f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
        setPadding(dp(16), 0, dp(16), dp(8))
    }

    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val emptyView = TextView(context).apply {
        setText(R.string.cfg_empty_folder)
        gravity = Gravity.CENTER
        alpha = 0.6f
        setPadding(dp(16), dp(48), dp(16), dp(48))
    }

    private fun showAddMenu(anchor: View) {
        val popup = PopupMenu(context, anchor)

        popup.menu.add(0, 1, 0, R.string.cfg_new_file)
        popup.menu.add(0, 2, 1, R.string.cfg_new_folder)
        popup.menu.add(0, 3, 2, R.string.cfg_import_file)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> requests.trySend(Request.NewFile)
                2 -> requests.trySend(Request.NewFolder)
                3 -> requests.trySend(Request.ImportFile)
            }

            true
        }

        popup.show()
    }

    private fun showEntryMenu(anchor: View, entry: Entry) {
        val popup = PopupMenu(context, anchor)

        popup.menu.add(0, 1, 0, R.string.cfg_rename)

        if (!entry.isDirectory) {
            popup.menu.add(0, 2, 1, R.string.cfg_export)
        }

        popup.menu.add(0, 3, 2, R.string.cfg_delete)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> requests.trySend(Request.Rename(entry))
                2 -> requests.trySend(Request.Export(entry))
                3 -> requests.trySend(Request.Delete(entry))
            }

            true
        }

        popup.show()
    }

    private fun buildRow(entry: Entry): View {
        val subtitle = if (entry.isDirectory) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(entry.modified))
        } else {
            Formatter.formatShortFileSize(context, entry.size) + "  ·  " +
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(entry.modified))
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60)
            setPadding(dp(16), dp(4), dp(4), dp(4))
            setBackgroundResource(selectableBackground())
            isClickable = true
            isFocusable = true
            setOnClickListener { requests.trySend(Request.Open(entry)) }

            addView(ImageView(context).apply {
                setImageResource(
                    if (entry.isDirectory) R.drawable.ic_outline_folder else R.drawable.ic_outline_article
                )
                setColorFilter(onSurface)
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
            })

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), 0, dp(8), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(context).apply {
                    text = entry.name
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                })
                addView(TextView(context).apply {
                    text = subtitle
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Caption)
                    alpha = 0.7f
                    maxLines = 1
                })
            })

            addView(iconButton(R.drawable.ic_baseline_more_vert) { anchor ->
                showEntryMenu(anchor, entry)
            })
        }
    }

    override val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        fitsSystemWindows = true

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(4))

            addView(iconButton(R.drawable.ic_baseline_arrow_back) {
                requests.trySend(Request.Back)
            })
            addView(TextView(context).apply {
                setText(R.string.cfg_config_files)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
                setPadding(dp(16), 0, 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(iconButton(R.drawable.ic_baseline_add) { anchor ->
                showAddMenu(anchor)
            })
        })

        addView(pathView)

        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(list)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
    }

    /** Tampilkan isi folder. [path] adalah lokasi relatif terhadap folder profil, "" untuk akar. */
    suspend fun setEntries(path: String, entries: List<Entry>) {
        withContext(Dispatchers.Main) {
            pathView.text = "/$path"

            list.removeAllViews()

            if (entries.isEmpty()) {
                list.addView(emptyView)
            } else {
                entries.forEach { list.addView(buildRow(it)) }
            }
        }
    }
}
