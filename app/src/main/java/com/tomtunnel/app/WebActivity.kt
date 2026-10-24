package com.tomtunnel.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity

/** WebView intégrée : ouvre les liens externes (canal Telegram, etc.) sans quitter l'app. */
class WebActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val url = intent.getStringExtra("url") ?: "https://t.me/FREE_INTERNET237"
        val title = TextView(this).apply { text = Uri.parse(url).host ?: ""; textSize = 15f; setPadding(24, 0, 0, 0) }
        val close = Button(this).apply { text = "✕"; setOnClickListener { finish() } }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#F4F5F7"))
            addView(close, LinearLayout.LayoutParams(140, 120)); addView(title)
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    if (u.scheme == "http" || u.scheme == "https") { title.text = u.host; return false }
                    try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: ActivityNotFoundException) {}  // tg://, whatsapp://, mailto:...
                    return true
                }
            }
            loadUrl(url)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(-1, 120)); addView(web, LinearLayout.LayoutParams(-1, -1))
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
}
