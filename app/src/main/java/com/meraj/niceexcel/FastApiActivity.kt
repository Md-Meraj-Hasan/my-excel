package com.meraj.niceexcel

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream

/** Optional FastAPI-powered web spreadsheet. Existing native workbook UI remains available. */
class FastApiActivity : Activity() {
    private lateinit var webView: WebView
    private val localUrl = "http://127.0.0.1:8765/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        val header = TextView(this).apply {
            text = "←  Nice Excel Web Engine"
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(17, 94, 57))
            setPadding(24, 24, 16, 24)
            setOnClickListener { finish() }
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        Toast.makeText(this@FastApiActivity, "Starting local Excel engine…", Toast.LENGTH_SHORT).show()
                        view?.postDelayed({ view.loadUrl(localUrl) }, 900)
                    }
                }
            }
            webChromeClient = WebChromeClient()
        }
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        try {
            copyUiAsset()
            if (!Python.isStarted()) Python.start(AndroidPlatform(this))
            Thread {
                try {
                    Python.getInstance().getModule("server").callAttr("start_server", filesDir.absolutePath)
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this, "Local engine failed: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }.apply { name = "NiceExcel-FastAPI"; isDaemon = true; start() }
            webView.postDelayed({ webView.loadUrl(localUrl) }, 1200)
        } catch (e: Exception) {
            Toast.makeText(this, "Python runtime could not start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyUiAsset() {
        val target = File(filesDir, "niceexcel_asset_index.html")
        assets.open("niceexcel_index.html").use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }
}
