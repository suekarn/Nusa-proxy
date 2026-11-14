package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConfigEditorDesign(context: Context) : Design<ConfigEditorDesign.Request>(context) {
    enum class Request {
        Save,
        Back,
        Add,
        OpenDashboard,
        OpenFiles,
        ToggleAdBlock,
    }

    /** Status blok iklan saat ini; dipakai untuk tanda centang di menu. */
    var adBlockEnabled: Boolean = false

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private val editor = EditText(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 14f
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        background = null
        setHorizontallyScrolling(false)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        minHeight = dp(400)
    }

    private fun iconButton(icon: Int, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(icon)
            setBackgroundResource(android.R.color.transparent)
            setColorFilter(context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { onClick() }
        }
    }

    private val moreButton: ImageButton = iconButton(R.drawable.ic_baseline_more_vert) {}

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(context, anchor)

        popup.menu.add(0, MENU_FILES, 0, R.string.cfg_config_files)
        popup.menu.add(0, MENU_ADBLOCK, 1, R.string.cfg_adblock).apply {
            isCheckable = true
            isChecked = adBlockEnabled
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_FILES -> requests.trySend(Request.OpenFiles)
                MENU_ADBLOCK -> requests.trySend(Request.ToggleAdBlock)
            }

            true
        }

        popup.show()
    }

    override val root: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        fitsSystemWindows = true

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))

            addView(iconButton(R.drawable.ic_baseline_arrow_back) {
                requests.trySend(Request.Back)
            })
            addView(TextView(context).apply {
                setText(R.string.config_editor)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
                setPadding(dp(8), 0, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(iconButton(R.drawable.ic_baseline_dashboard) {
                requests.trySend(Request.OpenDashboard)
            })
            addView(iconButton(R.drawable.ic_baseline_add) {
                requests.trySend(Request.Add)
            })
            addView(iconButton(R.drawable.ic_baseline_save) {
                requests.trySend(Request.Save)
            })
            addView(moreButton.apply {
                setOnClickListener { showMoreMenu(this) }
            })
        })

        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(editor)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
    }

    suspend fun setText(text: String) {
        withContext(Dispatchers.Main) {
            editor.setText(text)
        }
    }

    suspend fun getText(): String {
        return withContext(Dispatchers.Main) {
            editor.text.toString()
        }
    }

    private companion object {
        const val MENU_FILES = 1
        const val MENU_ADBLOCK = 2
    }
}
