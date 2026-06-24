package com.example.chatapp.games.web

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

class WebGameActivity : ComponentActivity() {
    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val launchUrl = intent.getStringExtra(EXTRA_GAME_URL)?.takeIf(::isAllowedGameUrl)
        if (launchUrl == null) {
            finish()
            return
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean =
                    !isAllowedGameUrl(request.url.toString())
            }
        }
        setContentView(webView)
        val loginName = intent.getStringExtra("username").orEmpty().trim().take(24)
        val gameUrl = Uri.parse(launchUrl).buildUpon()
            .appendQueryParameter("player", loginName)
            .build()
            .toString()
        webView.loadUrl(gameUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun isAllowedGameUrl(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        if (uri.scheme == "https") {
            return uri.host == "somechatapp.ddns.net" && uri.path.orEmpty().startsWith("/games/")
        }
        if (uri.scheme != "file") return false
        val gameRoot = java.io.File(filesDir, "game-packs").canonicalFile
        val requested = runCatching { java.io.File(requireNotNull(uri.path)).canonicalFile }.getOrNull() ?: return false
        return requested.path.startsWith(gameRoot.path + java.io.File.separator)
    }

    companion object {
        const val EXTRA_GAME_URL = "game_url"
        const val EXTRA_GAME_NAME = "game_name"
    }
}
