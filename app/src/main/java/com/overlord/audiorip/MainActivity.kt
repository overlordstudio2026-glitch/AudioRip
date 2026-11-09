package com.overlord.audiorip

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.overlord.audiorip.ui.MainScreen
import com.overlord.audiorip.ui.MainViewModel
import com.overlord.audiorip.ui.theme.AudioRipTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // SAF Document Picker supporting both videos and audio files seamlessly
    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist read permission across reboots if possible
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            viewModel.loadMedia(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle shared media if launched from another app (e.g., Gallery, Files, WhatsApp)
        handleIncomingIntent(intent)

        setContent {
            AudioRipTheme {
                MainScreen(
                    viewModel = viewModel,
                    onPickMediaClick = {
                        pickMediaLauncher.launch(
                            arrayOf("video/*", "audio/*")
                        )
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkAccessibilityStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val type = intent?.type
        val isMediaShare = type?.startsWith("video/") == true || type?.startsWith("audio/") == true
        if (intent?.action == Intent.ACTION_SEND && isMediaShare) {
            val mediaUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (mediaUri != null) {
                viewModel.loadMedia(mediaUri)
            }
        }
    }
}
