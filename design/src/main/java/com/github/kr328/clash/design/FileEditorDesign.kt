package com.github.kr328.clash.design

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.github.kr328.clash.design.util.resolveThemedColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Editor teks untuk satu berkas di folder config (YAML provider, daftar aturan, dll).
 */
class FileEditorDesign(context: Context, fileName: String) :
    Design<FileEditorDesign.Request>(context) {
    enum class Request {
        Save,
        Back,
    }

    /** True bila isi teks berbeda dari yang terakhir dimuat atau disimpan. */
    var modified: Boolean = false
        private set

    private var loading = false

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

        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (!loading) modified = true
            }
        })
    }

    private fun iconButton(icon: Int, onClick: () -> Unit): ImageButton {
        return ImageButton(context).apply {
            setImageResource(icon)
            setBackgroundResource(android.R.color.transparent)
            setColorFilter(context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { onClick() }
        }
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
                text = fileName
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline6)
                setPadding(dp(16), 0, 0, 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(iconButton(R.drawable.ic_baseline_save) {
                requests.trySend(Request.Save)
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
            loading = true

            try {
                editor.setText(text)
            } finally {
                loading = false
            }

            modified = false
        }
    }

    suspend fun getText(): String {
        return withContext(Dispatchers.Main) {
            editor.text.toString()
        }
    }

    /** Tandai teks saat ini sebagai sudah tersimpan. */
    suspend fun markSaved() {
        withContext(Dispatchers.Main) {
            modified = false
        }
    }
}
