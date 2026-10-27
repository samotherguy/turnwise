package com.rollingrecorder

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    /** The launching intent, then each new one (share sheet, notification taps); the app consumes and clears it. */
    private val incoming = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Api.settings = Settings(this)
        if (savedInstanceState == null) incoming.value = intent
        setContent {
            AppTheme { TurnwiseApp(incoming) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent
    }

    override fun onResume() {
        super.onResume()
        // Recording is switched on but nothing is running (after a reboot, an update, or the
        // system killing the app): bring it back now that we are in the foreground.
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (Settings(this).enabled && micGranted && RecorderState.engine == null) {
            RecorderService.start(this)
        }
    }
}
