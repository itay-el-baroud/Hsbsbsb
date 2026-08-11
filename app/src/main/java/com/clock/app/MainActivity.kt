package com.clock.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var clockText: TextView

    private val runnable = object : Runnable {
        override fun run() {
            val time = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date())
            clockText.text = "🕒\n\n$time\n\nClock App"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clockText = TextView(this).apply {
            textSize = 28f
            setPadding(40, 300, 40, 40)
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
        }
        setContentView(clockText)
        handler.post(runnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(runnable)
    }
}
