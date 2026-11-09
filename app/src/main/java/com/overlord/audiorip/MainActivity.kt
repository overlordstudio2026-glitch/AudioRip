package com.overlord.audiorip

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.overlord.audiorip.core.share.accessibility.AudioRipAccessibilityService
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

        // Handle shared media if launched from another app (e.g., OmniCamPro, Gallery, Files)
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && AudioRipAccessibilityService.pendingClipboardTranscriptRead) {
            AudioRipAccessibilityService.pendingClipboardTranscriptRead = false
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                ?.trim()
            val fallbackText = AudioRipAccessibilityService.lastScrapedGeminiTranscript
            viewModel.onGeminiTranscriptRetrieved(
                context = this,
                clipboardText = clipText,
                scrapedFallbackText = fallbackText
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
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
                val autoTranscribe = intent.getBooleanExtra("EXTRA_AUTO_TRANSCRIBE_PIPELINE", false) ||
                    intent.getBooleanExtra("extra_auto_transcribe_pipeline", false)
                val callerPkg = intent.getStringExtra("EXTRA_CALLER_PACKAGE")
                    ?: intent.getStringExtra("extra_caller_package")
                val videoName = intent.getStringExtra("EXTRA_VIDEO_NAME")
                    ?: intent.getStringExtra("extra_video_name")
                val videoFolder = intent.getStringExtra("EXTRA_VIDEO_FOLDER")
                    ?: intent.getStringExtra("extra_video_folder")
                val activityName = intent.getStringExtra("EXTRA_ACTIVITY_NAME")
                    ?: intent.getStringExtra("extra_activity_name")
                val lockedContact = intent.getStringExtra("EXTRA_LOCKED_LINE_CONTACT")
                    ?: intent.getStringExtra("extra_locked_line_contact")
                val customPrompt = intent.getStringExtra("EXTRA_PROMPT_TEMPLATE")
                    ?: intent.getStringExtra("extra_prompt_template")

                viewModel.loadMedia(
                    uri = mediaUri,
                    autoStartPipeline = autoTranscribe,
                    callerPackage = callerPkg,
                    callerVideoName = videoName,
                    callerVideoFolder = videoFolder,
                    customActivityName = activityName,
                    customLockedLineContact = lockedContact,
                    customPromptTemplate = customPrompt
                )
            }
        }
    }
}
