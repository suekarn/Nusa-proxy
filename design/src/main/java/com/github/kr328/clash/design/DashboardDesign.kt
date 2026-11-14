package com.github.kr328.clash.design

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

class DashboardDesign(context: Context) : Design<DashboardDesign.Request>(context) {
    enum class Request {
        Close,
    }

    @SuppressLint("SetJavaScriptEnabled")
    private val webView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient = WebViewClient()
    }

    override val root: View = FrameLayout(context).apply {
        fitsSystemWindows = true

        addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    fun load(url: String) {
        webView.loadUrl(url)
    }

    fun goBack(): Boolean {
        if (webView.canGoBack()) {
            webView.goBack()

            return true
        }

        return false
    }

    fun destroy() {
        webView.destroy()
    }
}
