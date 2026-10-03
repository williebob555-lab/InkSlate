package com.inksheets.watch

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The watch's one screen: listening or not, one button to change it, and - calibrating - the
 * prompt in big letters (the buzz says it too, for a player not looking).
 */
class WatchActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var big: TextView
    private lateinit var small: TextView
    private lateinit var button: Button
    private lateinit var allow: Button
    private val refresh: () -> Unit = { show() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.widthPixels * 0.14f).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad / 2, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "InkSheets"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.LTGRAY)
        })
        big = TextView(this).apply { gravity = Gravity.CENTER; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) }
        small = TextView(this).apply { gravity = Gravity.CENTER; textSize = 12f; setTextColor(Color.LTGRAY) }
        button = Button(this).apply {
            setOnClickListener {
                if (FlickService.running != null) FlickService.stopByHand(this@WatchActivity) else FlickService.start(this@WatchActivity)
            }
        }
        // Let off battery saving, the phone can start it listening without a tap here.
        allow = Button(this).apply {
            text = "Let the phone start it"
            textSize = 11f
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                }.onFailure {
                    runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            }
        }
        root.addView(big)
        root.addView(small)
        root.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8 })
        root.addView(allow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 4 })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        FlickService.loadModel(this)
        listenIfAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        listenIfAsked(intent)
    }

    /** Opened from "Tap to listen": the phone wanted it listening. */
    private fun listenIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(LISTEN, false) == true && FlickService.running == null) FlickService.start(this)
    }

    companion object {
        const val LISTEN = "listen"
    }

    override fun onResume() {
        super.onResume()
        WatchState.listeners += refresh
        FlickService.sayHello(this)
        show()
    }

    override fun onPause() {
        WatchState.listeners -= refresh
        super.onPause()
    }

    private fun show() {
        val listening = FlickService.running != null
        val cue = WatchState.cue.takeIf { WatchState.calibrating } ?: WatchState.cue.takeIf { it == "done" }
        if (WatchState.calibrating) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.setBackgroundColor(when (cue) {
            "next" -> Color.rgb(20, 110, 50)
            "back" -> Color.rgb(160, 80, 0)
            else -> Color.BLACK
        })
        big.text = when {
            cue == "next" -> "Flick for\nNEXT ▶"
            cue == "back" -> "Flick for\n◀ BACK"
            cue == "done" -> "Done"
            WatchState.calibrating -> "Play\nnormally"
            !listening -> "Not listening"
            WatchState.model == null -> "Calibrate\non the phone"
            else -> "Listening"
        }
        small.text = when {
            cue == "done" -> "See the phone for how it went."
            WatchState.calibrating -> "Calibrating - follow the phone. One buzz: next. Two: back."
            !listening -> "Starts by itself while InkSheets on the phone is in use - or tap Listen."
            WatchState.model == null -> "InkSheets on the phone: Settings › Experimental › Turn pages with the watch."
            else -> listOfNotNull(
                WatchState.problem?.takeIf { System.currentTimeMillis() - WatchState.lastFlickAt < 60_000 },
                WatchState.lastFlick?.let { "Last: $it" },
                "Calibrated for ${WatchState.model}"
            ).joinToString("\n")
        }
        button.text = if (listening) "Stop" else "Listen"
        val free = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        allow.visibility = if (free) View.GONE else View.VISIBLE
        button.isEnabled = !WatchState.calibrating
    }
}
