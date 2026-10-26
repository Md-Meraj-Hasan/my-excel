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
import java.net.Socket

/** Optional FastAPI-powered web spreadsheet. Existing native workbook UI remains available. */
class FastApiActivity : Activity() {
    private lateinit var webView: WebView
    private val localUrl = "http://127.0.0.1:8765/"
    @Volatile private var engineReady = false
    @Volatile private var engineFailed = false
    private var mainFrameErrorShown = false

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
                    if (request?.isForMainFrame == true && !mainFrameErrorShown) {
                        mainFrameErrorShown = true
                        Toast.makeText(this@FastApiActivity, "Excel Web Engine connection failed. Go back and try Web again.", Toast.LENGTH_LONG).show()
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
                    engineFailed = true
                    runOnUiThread {
                        if (!isFinishing) Toast.makeText(this, "Local engine failed to start: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }.apply { name = "NiceExcel-FastAPI"; isDaemon = true; start() }

            // Do not load WebView until Uvicorn is actually listening. This avoids
            // the old fixed-delay race and repeated ERR_CONNECTION_REFUSED popups.
            Thread {
                val deadline = System.currentTimeMillis() + 20000L
                while (System.currentTimeMillis() < deadline && !engineFailed && !isFinishing) {
                    try {
                        Socket().use { socket -> socket.connect(java.net.InetSocketAddress("127.0.0.1", 8765), 350) }
                        engineReady = true
                        break
                    } catch (_: Exception) {
                        try { Thread.sleep(250) } catch (_: InterruptedException) { return@Thread }
                    }
                }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (engineReady) {
                        mainFrameErrorShown = false
                        webView.loadUrl(localUrl)
                    } else if (!engineFailed) {
                        Toast.makeText(this, "Excel engine did not become ready. Please reopen Web.", Toast.LENGTH_LONG).show()
                    }
                }
            }.apply { name = "NiceExcel-EngineReady"; isDaemon = true; start() }
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
