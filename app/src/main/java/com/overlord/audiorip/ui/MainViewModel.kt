package com.overlord.audiorip.ui

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.overlord.audiorip.core.share.LineDirectShareManager
import com.overlord.audiorip.core.share.accessibility.AudioRipAccessibilityService
import com.overlord.audiorip.core.share.accessibility.ContactPickerValidator
import com.overlord.audiorip.data.GeminiTranscriptionService
import com.overlord.audiorip.data.MediaStoreHelper
import com.overlord.audiorip.data.MultiCutExportMode
import com.overlord.audiorip.data.NativeExtractorEngine
import com.overlord.audiorip.data.OutputAudioFormat
import com.overlord.audiorip.data.TransformerExtractorEngine
import com.overlord.audiorip.data.TrimSegment
import com.overlord.audiorip.data.VideoMetadata
import com.overlord.audiorip.data.WavExtractorEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ExtractionState {
    data object Idle : ExtractionState
    data class Extracting(val progress: Float, val statusText: String) : ExtractionState
    data class Success(val file: File, val mediaUri: Uri, val extraFiles: List<File> = emptyList()) : ExtractionState
    data class Error(val message: String) : ExtractionState
}

data class EditSessionSnapshot(
    val media: VideoMetadata,
    val segments: List<TrimSegment>,
    val activeSegmentIndex: Int,
    val exportMode: MultiCutExportMode,
    val customFileName: String,
    val extractionState: ExtractionState
)

data class UiState(
    val selectedVideo: VideoMetadata? = null,
    val selectedFormat: OutputAudioFormat = OutputAudioFormat.M4A_NATIVE,
    val isTrimmingEnabled: Boolean = false,
    val segments: List<TrimSegment> = emptyList(),
    val activeSegmentIndex: Int = 0,
    val exportMode: MultiCutExportMode = MultiCutExportMode.MERGE_CONCAT,
    val customFileName: String = "",
    val activityName: String = "",
    val activityTime: String = "",
    val extractionState: ExtractionState = ExtractionState.Idle,
    // Timeline Playhead & Preview
    val isPreviewPlaying: Boolean = false,
    val previewCurrentPositionMs: Long = 0L,
    // Result audio playback
    val isAudioPlaying: Boolean = false,
    val audioCurrentPositionMs: Long = 0L,
    val audioDurationMs: Long = 0L,
    // Edit History / Session Stack
    val historyStack: List<EditSessionSnapshot> = emptyList(),
    // Automated AI Transcription State
    val isTranscribing: Boolean = false,
    val transcriptionText: String? = null,
    val transcriptionError: String? = null,
    val geminiApiKey: String = "",
    val promptTemplate: String = ContactPickerValidator.DEFAULT_TRANSCRIPTION_PROMPT_TEMPLATE,
    // LINE Direct & Automated Sharing State
    val lockedLineContact: String = "",
    val isAccessibilityEnabled: Boolean = false,
    // Cross-App Caller State (e.g. from OmniCamPro)
    val callerPackage: String? = null,
    val callerVideoName: String? = null,
    val callerVideoFolder: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var extractionJob: Job? = null
    private var exoPlayer: ExoPlayer? = null
    private var playbackProgressJob: Job? = null

    companion object {
        private const val PREFS_NAME = "audiorip_config"
        private const val KEY_LOCKED_LINE_CONTACT = "locked_line_contact"
    }

    init {
        initPlayer()
        loadInitialApiKey()
        loadInitialLineConfig()
    }

    private fun loadInitialApiKey() {
        val savedKey = GeminiTranscriptionService.getSavedApiKey(getApplication())
        val savedPrompt = GeminiTranscriptionService.getSavedPromptTemplate(getApplication())
        _uiState.update {
            it.copy(
                geminiApiKey = savedKey,
                promptTemplate = savedPrompt
            )
        }
    }

    fun updatePromptTemplate(newTemplate: String) {
        _uiState.update { it.copy(promptTemplate = newTemplate) }
        GeminiTranscriptionService.savePromptTemplate(getApplication(), newTemplate)
    }

    fun resetPromptTemplate() {
        val defaultTpl = GeminiTranscriptionService.resetPromptTemplate(getApplication())
        _uiState.update { it.copy(promptTemplate = defaultTpl) }
    }

    private fun loadInitialLineConfig() {
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val locked = prefs.getString(KEY_LOCKED_LINE_CONTACT, "") ?: ""
        val isA11y = AudioRipAccessibilityService.isAccessibilityServiceEnabled(getApplication())
        _uiState.update {
            it.copy(
                lockedLineContact = locked,
                isAccessibilityEnabled = isA11y
            )
        }
    }

    private fun initPlayer() {
        exoPlayer = ExoPlayer.Builder(getApplication()).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    val isResultState = _uiState.value.extractionState is ExtractionState.Success
                    if (isResultState) {
                        _uiState.update { it.copy(isAudioPlaying = isPlaying) }
                    } else {
                        _uiState.update { it.copy(isPreviewPlaying = isPlaying) }
                    }
                    if (isPlaying) {
                        startTrackingPlayback(isResultState)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        val isResultState = _uiState.value.extractionState is ExtractionState.Success
                        if (isResultState) {
                            _uiState.update { it.copy(isAudioPlaying = false, audioCurrentPositionMs = 0L) }
                        } else {
                            _uiState.update { it.copy(isPreviewPlaying = false, previewCurrentPositionMs = 0L) }
                        }
                    }
                }
            })
        }
    }

    private fun startTrackingPlayback(isResultState: Boolean) {
        playbackProgressJob?.cancel()
        playbackProgressJob = viewModelScope.launch {
            while (isActive && exoPlayer?.isPlaying == true) {
                val current = exoPlayer?.currentPosition ?: 0L
                val total = exoPlayer?.duration?.coerceAtLeast(0L) ?: 0L

                if (isResultState) {
                    _uiState.update {
                        it.copy(audioCurrentPositionMs = current, audioDurationMs = total)
                    }
                } else {
                    val state = _uiState.value
                    val activeSeg = state.segments.getOrNull(state.activeSegmentIndex)
                    if (state.isTrimmingEnabled && activeSeg != null && activeSeg.endMs > activeSeg.startMs && current >= activeSeg.endMs) {
                        exoPlayer?.pause()
                        exoPlayer?.seekTo(activeSeg.startMs)
                        _uiState.update {
                            it.copy(isPreviewPlaying = false, previewCurrentPositionMs = activeSeg.startMs)
                        }
                        break
                    } else {
                        _uiState.update {
                            it.copy(previewCurrentPositionMs = current)
                        }
                    }
                }
                delay(100)
            }
        }
    }

    fun loadMedia(
        uri: Uri,
        autoStartPipeline: Boolean = false,
        callerPackage: String? = null,
        callerVideoName: String? = null,
        callerVideoFolder: String? = null,
        customActivityName: String? = null,
        customLockedLineContact: String? = null,
        customPromptTemplate: String? = null
    ) {
        viewModelScope.launch {
            stopAudio()
            if (!customLockedLineContact.isNullOrBlank()) {
                updateLockedLineContact(customLockedLineContact)
            }
            if (!customPromptTemplate.isNullOrBlank()) {
                updatePromptTemplate(customPromptTemplate)
            }
            _uiState.update {
                it.copy(
                    extractionState = ExtractionState.Idle,
                    transcriptionText = null,
                    transcriptionError = null,
                    callerPackage = callerPackage ?: it.callerPackage,
                    callerVideoName = callerVideoName ?: it.callerVideoName,
                    callerVideoFolder = callerVideoFolder ?: it.callerVideoFolder
                )
            }

            val context = getApplication<Application>()
            var fileName = callerVideoName?.takeIf { it.isNotBlank() } ?: "media"
            var fileSize = 0L

            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) fileName = cursor.getString(nameIndex) ?: fileName
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                    }
                }
            } catch (_: Exception) {
                val path = uri.path
                if (path != null) {
                    val f = File(path)
                    if (f.exists()) {
                        fileName = f.name
                        fileSize = f.length()
                    }
                }
            }

            var durationMs = 0L
            var audioMime: String? = null
            var width = 0
            var height = 0
            var isAudioOnly = false

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                audioMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                if (hasVideo == null && (audioMime?.startsWith("audio/") == true || width == 0)) {
                    isAudioOnly = true
                }
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            val defaultName = fileName.substringBeforeLast(".")
            val effectiveActivity = customActivityName?.takeIf { it.isNotBlank() } ?: defaultName
            val metadata = VideoMetadata(
                uri = uri,
                fileName = fileName,
                durationMs = durationMs,
                fileSizeBytes = fileSize,
                audioMime = audioMime,
                width = width,
                height = height,
                isAudioOnly = isAudioOnly
            )

            val currentTimeStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
            val initialSegment = TrimSegment(startMs = 0L, endMs = durationMs)

            _uiState.update {
                it.copy(
                    selectedVideo = metadata,
                    customFileName = defaultName,
                    activityName = effectiveActivity,
                    activityTime = currentTimeStr,
                    segments = listOf(initialSegment),
                    activeSegmentIndex = 0,
                    exportMode = MultiCutExportMode.MERGE_CONCAT,
                    previewCurrentPositionMs = 0L,
                    isPreviewPlaying = false
                )
            }

            try {
                val mediaItem = MediaItem.fromUri(uri)
                exoPlayer?.setMediaItem(mediaItem)
                exoPlayer?.prepare()
            } catch (_: Exception) {}

            if (autoStartPipeline) {
                Toast.makeText(context, "已接收影片，自動拆出音軌並送往 Gemini 轉逐字稿...", Toast.LENGTH_SHORT).show()
                startExtraction(autoTriggerTranscription = true)
            }
        }
    }

    // Timeline and Trimming Controls
    fun setTrimmingEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isTrimmingEnabled = enabled) }
    }

    fun setExportMode(mode: MultiCutExportMode) {
        _uiState.update { it.copy(exportMode = mode) }
    }

    fun seekPreview(positionMs: Long) {
        val safePos = positionMs.coerceIn(0L, _uiState.value.selectedVideo?.durationMs ?: 0L)
        exoPlayer?.seekTo(safePos)
        _uiState.update { it.copy(previewCurrentPositionMs = safePos) }
    }

    fun togglePreviewPlayback() {
        val player = exoPlayer ?: return
        val state = _uiState.value
        if (player.isPlaying) {
            player.pause()
        } else {
            val activeSeg = state.segments.getOrNull(state.activeSegmentIndex)
            if (state.isTrimmingEnabled && activeSeg != null) {
                val current = player.currentPosition
                if (current < activeSeg.startMs || (activeSeg.endMs > activeSeg.startMs && current >= activeSeg.endMs)) {
                    player.seekTo(activeSeg.startMs)
                }
            }
            player.play()
        }
    }

    fun setStartToCurrentPosition() {
        val currentMs = _uiState.value.previewCurrentPositionMs
        val curList = _uiState.value.segments.toMutableList()
        val index = _uiState.value.activeSegmentIndex.coerceIn(0, (curList.size - 1).coerceAtLeast(0))

        if (curList.isEmpty()) {
            val total = _uiState.value.selectedVideo?.durationMs ?: 0L
            curList.add(TrimSegment(startMs = currentMs, endMs = total))
        } else {
            val old = curList[index]
            val safeEnd = if (old.endMs <= currentMs) (_uiState.value.selectedVideo?.durationMs ?: currentMs) else old.endMs
            curList[index] = old.copy(startMs = currentMs, endMs = safeEnd)
        }

        _uiState.update {
            it.copy(
                isTrimmingEnabled = true,
                segments = curList,
                activeSegmentIndex = index
            )
        }
    }

    fun setEndToCurrentPosition() {
        val currentMs = _uiState.value.previewCurrentPositionMs
        val curList = _uiState.value.segments.toMutableList()
        val index = _uiState.value.activeSegmentIndex.coerceIn(0, (curList.size - 1).coerceAtLeast(0))

        if (curList.isEmpty()) {
            curList.add(TrimSegment(startMs = 0L, endMs = currentMs))
        } else {
            val old = curList[index]
            val safeStart = if (old.startMs >= currentMs) 0L else old.startMs
            curList[index] = old.copy(startMs = safeStart, endMs = currentMs)
        }

        _uiState.update {
            it.copy(
                isTrimmingEnabled = true,
                segments = curList,
                activeSegmentIndex = index
            )
        }
    }

    fun addNewSegment() {
        val state = _uiState.value
        val totalDuration = state.selectedVideo?.durationMs ?: 0L
        val currentMs = state.previewCurrentPositionMs
        val curList = state.segments.toMutableList()

        val defaultSpan = 15_000L
        val newStart = currentMs.coerceAtMost((totalDuration - 1000L).coerceAtLeast(0L))
        val newEnd = (newStart + defaultSpan).coerceAtMost(totalDuration)

        val newSeg = TrimSegment(startMs = newStart, endMs = newEnd)
        curList.add(newSeg)

        _uiState.update {
            it.copy(
                isTrimmingEnabled = true,
                segments = curList,
                activeSegmentIndex = curList.size - 1
            )
        }
        seekPreview(newStart)
    }

    fun removeSegment(index: Int) {
        val curList = _uiState.value.segments.toMutableList()
        if (index in curList.indices && curList.size > 1) {
            curList.removeAt(index)
            val nextActive = (index - 1).coerceAtLeast(0)
            _uiState.update {
                it.copy(segments = curList, activeSegmentIndex = nextActive)
            }
        }
    }

    fun selectSegment(index: Int) {
        if (index in _uiState.value.segments.indices) {
            _uiState.update { it.copy(activeSegmentIndex = index) }
            val seg = _uiState.value.segments[index]
            seekPreview(seg.startMs)
        }
    }

    fun setFormat(format: OutputAudioFormat) {
        _uiState.update { it.copy(selectedFormat = format) }
    }

    fun setCustomFileName(name: String) {
        _uiState.update { it.copy(customFileName = name) }
    }

    fun setActivityName(name: String) {
        _uiState.update { it.copy(activityName = name) }
    }

    fun setActivityTime(time: String) {
        _uiState.update { it.copy(activityTime = time) }
    }

    fun updateGeminiApiKey(key: String) {
        GeminiTranscriptionService.saveApiKey(getApplication(), key)
        _uiState.update { it.copy(geminiApiKey = key) }
    }

    // Session Stack: Push snapshot on re-edit, Pop snapshot on return
    fun reEditExtractedAudio() {
        val state = _uiState.value
        val success = state.extractionState as? ExtractionState.Success ?: return
        val currentMedia = state.selectedVideo ?: return

        // Take snapshot of parent workspace before jumping to child re-edit
        val snapshot = EditSessionSnapshot(
            media = currentMedia,
            segments = state.segments,
            activeSegmentIndex = state.activeSegmentIndex,
            exportMode = state.exportMode,
            customFileName = state.customFileName,
            extractionState = state.extractionState
        )

        val newStack = state.historyStack + snapshot
        _uiState.update { it.copy(historyStack = newStack) }

        val fileUri = Uri.fromFile(success.file)
        loadMedia(fileUri)
    }

    fun popHistorySession() {
        val state = _uiState.value
        if (state.historyStack.isEmpty()) return

        stopAudio()
        val snapshot = state.historyStack.last()
        val remainingStack = state.historyStack.dropLast(1)

        _uiState.update {
            it.copy(
                selectedVideo = snapshot.media,
                segments = snapshot.segments,
                activeSegmentIndex = snapshot.activeSegmentIndex,
                exportMode = snapshot.exportMode,
                customFileName = snapshot.customFileName,
                extractionState = snapshot.extractionState,
                historyStack = remainingStack,
                previewCurrentPositionMs = 0L,
                isPreviewPlaying = false,
                transcriptionText = null,
                transcriptionError = null
            )
        }

        // Reload parent media or result into player
        val resultSuccess = snapshot.extractionState as? ExtractionState.Success
        if (resultSuccess != null) {
            loadAudioIntoPlayer(resultSuccess.file)
        } else {
            try {
                val mediaItem = MediaItem.fromUri(snapshot.media.uri)
                exoPlayer?.setMediaItem(mediaItem)
                exoPlayer?.prepare()
            } catch (_: Exception) {}
        }
    }

    // Automated Gemini AI Transcription Execution
    fun requestAiTranscription(autoReturnToCaller: Boolean = false) {
        val state = _uiState.value
        val success = state.extractionState as? ExtractionState.Success ?: return
        if (state.isTranscribing) return

        val audioFile = success.file
        val mime = when (audioFile.name.substringAfterLast(".").lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            else -> "audio/mp4"
        }

        val effectiveName = state.activityName.ifBlank { audioFile.nameWithoutExtension }
        val effectiveTime = state.activityTime.ifBlank {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        }
        val promptText = ContactPickerValidator.buildFinalTranscriptionPrompt(
            customInstruction = state.promptTemplate,
            activityTime = effectiveTime,
            activityName = effectiveName
        )

        _uiState.update {
            it.copy(isTranscribing = true, transcriptionError = null)
        }

        viewModelScope.launch {
            val result = GeminiTranscriptionService.transcribeAudio(
                context = getApplication(),
                audioFile = audioFile,
                mimeType = mime,
                promptText = promptText
            )

            result.fold(
                onSuccess = { transcript ->
                    _uiState.update {
                        it.copy(
                            isTranscribing = false,
                            transcriptionText = transcript,
                            transcriptionError = null
                        )
                    }
                    if (autoReturnToCaller && !_uiState.value.callerPackage.isNullOrBlank()) {
                        returnTranscriptToCaller(getApplication(), transcript)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isTranscribing = false,
                            transcriptionError = error.message ?: "轉譯失敗"
                        )
                    }
                    // 若為跨 App 自動管線且 API 轉譯失敗，自動切換為免 API Gemini App 自動化管線
                    if (autoReturnToCaller) {
                        startFreeGeminiAppAutomation(getApplication())
                    }
                }
            )
        }
    }

    /**
     * 免 API 啟動 Gemini App 自動化轉逐字稿與自動點擊複製取回
     */
    fun startFreeGeminiAppAutomation(context: Context) {
        val state = _uiState.value
        val success = state.extractionState as? ExtractionState.Success ?: return
        val audioFile = success.file
        val mime = when (audioFile.name.substringAfterLast(".").lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            else -> "audio/*"
        }

        val effectiveName = state.activityName.ifBlank { audioFile.nameWithoutExtension }
        val effectiveTime = state.activityTime.ifBlank {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        }
        val promptText = ContactPickerValidator.buildFinalTranscriptionPrompt(
            customInstruction = state.promptTemplate,
            activityTime = effectiveTime,
            activityName = effectiveName
        )

        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("GeminiPrompt", promptText))
        } catch (_: Exception) {}

        AudioRipAccessibilityService.startGeminiAutoTranscribeJob(
            promptText = promptText,
            callerPackage = state.callerPackage,
            callerVideoName = state.callerVideoName,
            callerVideoFolder = state.callerVideoFolder
        ) { scraped ->
            if (!scraped.isNullOrBlank()) {
                _uiState.update {
                    it.copy(
                        isTranscribing = false,
                        transcriptionText = scraped,
                        transcriptionError = null
                    )
                }
            }
        }

        val geminiPackage = "com.google.android.apps.bard"
        val shareIntent = MediaStoreHelper.createShareWithPromptIntent(
            context = context,
            audioFile = audioFile,
            mimeType = mime,
            promptText = promptText,
            targetPackage = geminiPackage
        ).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(shareIntent)
            Toast.makeText(context, "已送往 Gemini，將自動填入送出文字並取回逐字稿！", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            val fallbackIntent = MediaStoreHelper.createShareWithPromptIntent(
                context = context,
                audioFile = audioFile,
                mimeType = mime,
                promptText = promptText
            )
            context.startActivity(android.content.Intent.createChooser(fallbackIntent, "選擇 Gemini 或其他 App").apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun updateTranscriptionText(newText: String) {
        _uiState.update { it.copy(transcriptionText = newText) }
    }

    fun onGeminiTranscriptRetrieved(
        context: Context,
        clipboardText: String?,
        scrapedFallbackText: String?
    ) {
        val currentTplPrefix = _uiState.value.promptTemplate.trim().lineSequence().firstOrNull()?.trim()?.take(12) ?: ""
        val cleanClip = clipboardText?.trim()?.takeIf {
            it.isNotBlank() &&
                !it.startsWith("請幫我將這段音訊") &&
                (currentTplPrefix.length < 4 || !it.startsWith(currentTplPrefix))
        }
        val finalTranscript = cleanClip ?: scrapedFallbackText?.trim()?.takeIf { it.isNotBlank() } ?: return

        _uiState.update {
            it.copy(
                isTranscribing = false,
                transcriptionText = finalTranscript,
                transcriptionError = null
            )
        }
        Toast.makeText(context, "🎉 已自動從 Gemini 取回逐字稿！", Toast.LENGTH_SHORT).show()

        if (!_uiState.value.callerPackage.isNullOrBlank()) {
            returnTranscriptToCaller(context, finalTranscript)
        }
    }

    fun returnTranscriptToCaller(context: Context, transcriptText: String? = _uiState.value.transcriptionText) {
        val state = _uiState.value
        val callerPkg = state.callerPackage?.takeIf { it.isNotBlank() } ?: return
        val textToSend = transcriptText?.takeIf { it.isNotBlank() } ?: return

        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Transcript", textToSend))
        } catch (_: Exception) {}

        try {
            val returnIntent = android.content.Intent(AudioRipAccessibilityService.ACTION_RECEIVE_TRANSCRIPT).apply {
                setPackage(callerPkg)
                putExtra(AudioRipAccessibilityService.EXTRA_TRANSCRIPT_TEXT, textToSend)
                putExtra(AudioRipAccessibilityService.EXTRA_READ_CLIPBOARD_TRANSCRIPT, true)
                putExtra(AudioRipAccessibilityService.EXTRA_VIDEO_NAME, state.callerVideoName ?: "")
                putExtra(AudioRipAccessibilityService.EXTRA_VIDEO_FOLDER, state.callerVideoFolder ?: "")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(returnIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "返回原 App 失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun clearTranscription() {
        _uiState.update { it.copy(transcriptionText = null, transcriptionError = null) }
    }

    // LINE Direct & Automated Sharing Management
    fun updateLockedLineContact(contact: String) {
        val trimmed = contact.trim()
        val prefs = getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LOCKED_LINE_CONTACT, trimmed).apply()
        _uiState.update { it.copy(lockedLineContact = trimmed) }
    }

    fun checkAccessibilityStatus() {
        val enabled = AudioRipAccessibilityService.isAccessibilityServiceEnabled(getApplication())
        _uiState.update { it.copy(isAccessibilityEnabled = enabled) }
    }

    fun startLineContactPicker(context: Context) {
        AudioRipAccessibilityService.launchLineContactPicker(context) { pickedName ->
            updateLockedLineContact(pickedName)
            Toast.makeText(context, "已成功鎖定 LINE 目標: $pickedName", Toast.LENGTH_SHORT).show()
        }
    }

    fun sendTranscriptToLine(
        context: Context,
        audioFile: File? = null,
        includeAudio: Boolean = false
    ) {
        val transcript = _uiState.value.transcriptionText ?: return
        val target = _uiState.value.lockedLineContact

        checkAccessibilityStatus()
        val isA11yEnabled = _uiState.value.isAccessibilityEnabled

        if (target.isNotBlank() && isA11yEnabled) {
            AudioRipAccessibilityService.startAutoShareJob(
                groupName = target,
                isTextOnly = !includeAudio || audioFile == null || !audioFile.exists()
            ) {
                Toast.makeText(context, "逐字稿已成功發送至 LINE [$target]！", Toast.LENGTH_SHORT).show()
            }

            val intent = if (includeAudio && audioFile != null && audioFile.exists()) {
                LineDirectShareManager.createDirectLineAudioAndTextShareIntent(
                    context = context,
                    audioFile = audioFile,
                    messageText = transcript,
                    targetGroup = target
                )
            } else {
                LineDirectShareManager.createDirectLineTextShareIntent(
                    context = context,
                    messageText = transcript,
                    targetGroup = target
                )
            }
            context.startActivity(intent)
        } else {
            val intent = LineDirectShareManager.createLineChooserShareIntent(
                context = context,
                messageText = transcript,
                audioFile = if (includeAudio) audioFile else null
            )
            context.startActivity(intent)
        }
    }

    fun deleteExtractedAudio(onFinished: (Boolean) -> Unit = {}) {
        val success = _uiState.value.extractionState as? ExtractionState.Success ?: return
        viewModelScope.launch {
            stopAudio()
            val deleted = MediaStoreHelper.deleteAudio(
                context = getApplication(),
                mediaUri = success.mediaUri,
                localFile = success.file
            )
            success.extraFiles.forEach { f ->
                try { f.delete() } catch (_: Exception) {}
            }

            _uiState.update {
                it.copy(
                    extractionState = ExtractionState.Idle,
                    isAudioPlaying = false,
                    audioCurrentPositionMs = 0L,
                    transcriptionText = null,
                    transcriptionError = null
                )
            }
            val currentMedia = _uiState.value.selectedVideo
            if (currentMedia != null) {
                try {
                    val item = MediaItem.fromUri(currentMedia.uri)
                    exoPlayer?.setMediaItem(item)
                    exoPlayer?.prepare()
                } catch (_: Exception) {}
            }
            onFinished(deleted)
        }
    }

    fun startExtraction(autoTriggerTranscription: Boolean = false) {
        val state = _uiState.value
        val video = state.selectedVideo ?: return
        val format = state.selectedFormat

        val context = getApplication<Application>()
        val outputDir = File(context.cacheDir, "extracted_audio").apply { if (!exists()) mkdirs() }
        val baseName = state.customFileName.ifBlank { "extracted_audio" }

        val effectiveSegments = if (state.isTrimmingEnabled && state.segments.isNotEmpty()) {
            state.segments.sortedBy { it.startMs }
        } else {
            listOf(TrimSegment(startMs = 0L, endMs = video.durationMs))
        }

        stopAudio()
        extractionJob?.cancel()
        extractionJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    extractionState = ExtractionState.Extracting(0f, "準備提取音訊..."),
                    transcriptionText = null,
                    transcriptionError = null
                )
            }

            if (state.isTrimmingEnabled && state.exportMode == MultiCutExportMode.SEPARATE_FILES && effectiveSegments.size > 1) {
                val extractedFiles = mutableListOf<File>()
                for ((idx, seg) in effectiveSegments.withIndex()) {
                    val partName = "${baseName}_part${idx + 1}.${format.extension}"
                    val partFile = File(outputDir, partName)
                    _uiState.update {
                        it.copy(extractionState = ExtractionState.Extracting(
                            idx.toFloat() / effectiveSegments.size,
                            "正在導出第 ${idx + 1}/${effectiveSegments.size} 段..."
                        ))
                    }

                    val segResult = when (format) {
                        OutputAudioFormat.M4A_NATIVE -> NativeExtractorEngine.extractAudioSegments(context, video.uri, partFile, listOf(seg)) {}
                        OutputAudioFormat.WAV_PCM -> WavExtractorEngine.extractWavSegments(context, video.uri, partFile, listOf(seg)) {}
                        else -> TransformerExtractorEngine.transcodeAudioSegments(context, video.uri, partFile, format, listOf(seg)) {}
                    }

                    segResult.onSuccess { f ->
                        extractedFiles.add(f)
                        MediaStoreHelper.saveAudioToMusicFolder(context, f, partName, format.mimeType)
                    }
                }

                if (extractedFiles.isNotEmpty()) {
                    val firstFile = extractedFiles.first()
                    _uiState.update {
                        it.copy(extractionState = ExtractionState.Success(
                            file = firstFile,
                            mediaUri = Uri.fromFile(firstFile),
                            extraFiles = extractedFiles.drop(1)
                        ))
                    }
                    loadAudioIntoPlayer(firstFile)
                    if (autoTriggerTranscription) {
                        triggerAutoTranscriptionPipeline(context)
                    }
                } else {
                    _uiState.update { it.copy(extractionState = ExtractionState.Error("多段導出失敗")) }
                }
            } else {
                val outputName = "$baseName.${format.extension}"
                val tempOutputFile = File(outputDir, outputName)

                val result: Result<File> = when (format) {
                    OutputAudioFormat.M4A_NATIVE -> {
                        _uiState.update {
                            it.copy(extractionState = ExtractionState.Extracting(0f, "無損多段極速拼接分離中..."))
                        }
                        NativeExtractorEngine.extractAudioSegments(
                            context = context,
                            inputUri = video.uri,
                            outputFile = tempOutputFile,
                            segments = effectiveSegments,
                            onProgress = { p ->
                                _uiState.update {
                                    it.copy(extractionState = ExtractionState.Extracting(p, "極速拼接處理中 ${(p * 100).toInt()}%"))
                                }
                            }
                        )
                    }
                    OutputAudioFormat.WAV_PCM -> {
                        _uiState.update {
                            it.copy(extractionState = ExtractionState.Extracting(0f, "無壓縮 PCM 多段無縫拼接中..."))
                        }
                        WavExtractorEngine.extractWavSegments(
                            context = context,
                            inputUri = video.uri,
                            outputFile = tempOutputFile,
                            segments = effectiveSegments,
                            onProgress = { p ->
                                _uiState.update {
                                    it.copy(extractionState = ExtractionState.Extracting(p, "PCM 拼接中 ${(p * 100).toInt()}%"))
                                }
                            }
                        )
                    }
                    OutputAudioFormat.AAC_TRANSCODE, OutputAudioFormat.MP3_COMPAT -> {
                        _uiState.update {
                            it.copy(extractionState = ExtractionState.Extracting(0f, "Media3 多段拼接轉碼中 (${format.displayName})..."))
                        }
                        TransformerExtractorEngine.transcodeAudioSegments(
                            context = context,
                            inputUri = video.uri,
                            outputFile = tempOutputFile,
                            format = format,
                            segments = effectiveSegments,
                            onProgress = { p ->
                                _uiState.update {
                                    it.copy(extractionState = ExtractionState.Extracting(p, "轉碼進行中 ${(p * 100).toInt()}%"))
                                }
                            }
                        )
                    }
                }

                result.fold(
                    onSuccess = { extractedFile ->
                        _uiState.update {
                            it.copy(extractionState = ExtractionState.Extracting(0.99f, "正在儲存至系統音樂目錄..."))
                        }

                        val saveResult = MediaStoreHelper.saveAudioToMusicFolder(
                            context = context,
                            sourceFile = extractedFile,
                            displayName = outputName,
                            mimeType = format.mimeType
                        )

                        saveResult.fold(
                            onSuccess = { mediaUri ->
                                _uiState.update {
                                    it.copy(extractionState = ExtractionState.Success(extractedFile, mediaUri))
                                }
                                loadAudioIntoPlayer(extractedFile)
                                if (autoTriggerTranscription) {
                                    triggerAutoTranscriptionPipeline(context)
                                }
                            },
                            onFailure = { error ->
                                // 即使 MediaStore 儲存失敗，本地快取檔案仍可直接送往 Gemini 轉逐字稿
                                _uiState.update {
                                    it.copy(extractionState = ExtractionState.Success(extractedFile, Uri.fromFile(extractedFile)))
                                }
                                loadAudioIntoPlayer(extractedFile)
                                if (autoTriggerTranscription) {
                                    triggerAutoTranscriptionPipeline(context)
                                }
                            }
                        )
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(extractionState = ExtractionState.Error("提取失敗: ${error.message}"))
                        }
                    }
                )
            }
        }
    }

    private fun triggerAutoTranscriptionPipeline(context: Context) {
        val currentKey = _uiState.value.geminiApiKey
        if (currentKey.isNotBlank()) {
            requestAiTranscription(autoReturnToCaller = true)
        } else {
            startFreeGeminiAppAutomation(context)
        }
    }

    fun cancelExtraction() {
        extractionJob?.cancel()
        _uiState.update { it.copy(extractionState = ExtractionState.Idle) }
    }

    private fun loadAudioIntoPlayer(file: File) {
        try {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
        } catch (_: Exception) {}
    }

    fun toggleAudioPlayback() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    fun seekAudio(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun stopAudio() {
        exoPlayer?.stop()
        _uiState.update {
            it.copy(
                isAudioPlaying = false,
                audioCurrentPositionMs = 0L,
                isPreviewPlaying = false,
                previewCurrentPositionMs = 0L
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackProgressJob?.cancel()
        extractionJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }
}
