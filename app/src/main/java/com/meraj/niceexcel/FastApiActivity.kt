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
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Optional FastAPI-powered web spreadsheet. Existing native workbook UI remains available. */
class FastApiActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var loadingText: TextView
    private val localUrl = "http://127.0.0.1:8765/"
    @Volatile private var engineReady = false
    @Volatile private var engineFailed = false
    private var mainFrameErrorShown = false
    private val engineLogFile by lazy { File(filesDir, "niceexcel_engine_startup.log") }

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
        loadingText = TextView(this).apply {
            text = "Starting offline Excel engine…"
            textSize = 14f
            setTextColor(Color.rgb(55, 67, 77))
            gravity = android.view.Gravity.CENTER
            setPadding(16, 18, 16, 18)
            setTextIsSelectable(true)
        }
        root.addView(loadingText, LinearLayout.LayoutParams(-1, -2))
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    if (request?.isForMainFrame == true && !mainFrameErrorShown) {
                        mainFrameErrorShown = true
                        loadingText.text = "Excel Web Engine failed to load. Go back and try Web again."
                        loadingText.visibility = android.view.View.VISIBLE
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

            // If the local engine survived an activity reopen, reuse it rather than
            // trying to bind port 8765 a second time.
            Thread {
                if (waitForHealth(900)) {
                    engineReady = true
                    runOnUiThread { if (!isFinishing) loadWebUi() }
                    return@Thread
                }

                Thread {
                    try {
                        Python.getInstance().getModule("server").callAttr("start_server", filesDir.absolutePath)
                    } catch (e: Exception) {
                        engineFailed = true
                        val detail = Log.getStackTraceString(e)
                        try { engineLogFile.appendText("\n=== Python engine start failure ===\n$detail\n") } catch (_: Exception) { }
                        Log.e("NiceExcelWebEngine", "Python/FastAPI startup failed", e)
                        runOnUiThread {
                            if (!isFinishing) {
                                val logTail = readEngineLogTail()
                                loadingText.visibility = android.view.View.VISIBLE
                                loadingText.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                                loadingText.text = "Excel engine failed to start.\n\nReason: ${e.message ?: e.javaClass.simpleName}\n\nStartup log (latest):\n$logTail"
                                Toast.makeText(
                                    this,
                                    "Excel engine failed. Error details are shown on this screen.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }.apply { name = "NiceExcel-FastAPI"; isDaemon = true; start() }

                val ready = waitForHealth(25000)
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (ready) {
                        engineReady = true
                        loadWebUi()
                    } else if (!engineFailed) {
                        val detail = try {
                            if (engineLogFile.exists()) engineLogFile.readText().takeLast(900)
                            else "No startup log was created. Expected local health endpoint: 127.0.0.1:8765/health"
                        } catch (_: Exception) { "Could not read engine startup log." }
                        Log.e("NiceExcelWebEngine", "Engine readiness timeout: $detail")
                        loadingText.visibility = android.view.View.VISIBLE
                        loadingText.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                        loadingText.text = "Excel engine startup timed out after 25 seconds.\n\nExpected health endpoint: http://127.0.0.1:8765/health\n\nStartup log (latest):\n${readEngineLogTail()}"
                        Toast.makeText(this, "Excel engine did not become ready. Error details are shown on this screen.", Toast.LENGTH_LONG).show()
                    }
                }
            }.apply { name = "NiceExcel-EngineBootstrap"; isDaemon = true; start() }
        } catch (e: Exception) {
            Log.e("NiceExcelWebEngine", "Unable to initialize Web Engine screen", e)
            Toast.makeText(this, "Python runtime could not start: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }


    private fun readEngineLogTail(): String {
        return try {
            if (engineLogFile.isFile) engineLogFile.readText().takeLast(2800)
            else "Startup log was not created at: ${engineLogFile.absolutePath}\nThis usually means Python failed before server.py could initialize logging."
        } catch (e: Exception) {
            "Could not read startup log: ${e.javaClass.simpleName}: ${e.message}\nPath: ${engineLogFile.absolutePath}"
        }
    }

    private fun waitForHealth(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !isFinishing) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("http://127.0.0.1:8765/health").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 500
                    readTimeout = 500
                    requestMethod = "GET"
                }
                if (connection.responseCode == 200) return true
            } catch (_: Exception) {
                // The server is not ready yet; keep polling until the deadline.
            } finally {
                connection?.disconnect()
            }
            try { Thread.sleep(250) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun loadWebUi() {
        if (isFinishing || !::webView.isInitialized) return
        mainFrameErrorShown = false
        if (::loadingText.isInitialized) loadingText.visibility = android.view.View.GONE
        webView.loadUrl(localUrl)
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
