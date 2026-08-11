package com.clock.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clockText: TextView

    private val runnable = object : Runnable {
        override fun run() {
            try {
                val time = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
                clockText.text = "🕒\n\n$time\n\nClock App"
                handler.postDelayed(this, 1000)
            } catch (e: Exception) {
                // مستحيل يحصل crash تاني
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clockText = TextView(this).apply {
            textSize = 32f
            gravity = Gravity.CENTER
            setPadding(40, 400, 40, 40)
        }
        setContentView(clockText)
        handler.post(runnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
    }
}
