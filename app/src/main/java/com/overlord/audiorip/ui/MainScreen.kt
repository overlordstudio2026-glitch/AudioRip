package com.overlord.audiorip.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.overlord.audiorip.ui.components.AudioPlayerCard
import com.overlord.audiorip.ui.components.ExtractionConfigCard
import com.overlord.audiorip.ui.components.RangeTrimSlider
import com.overlord.audiorip.ui.components.VideoInfoCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onPickMediaClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    if (state.historyStack.isNotEmpty()) {
                        IconButton(onClick = { viewModel.popHistorySession() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回上一層檔案"
                            )
                        }
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "AudioRip 音訊提取",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Restore Parent Session Banner (if in child re-edit mode)
            if (state.historyStack.isNotEmpty()) {
                val parentSession = state.historyStack.last()
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable { viewModel.popHistorySession() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("正在微調子片段", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "點此返回父檔案: ${parentSession.media.fileName} (保留 ${parentSession.segments.size} 個段落)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        TextButton(onClick = { viewModel.popHistorySession() }) {
                            Text("返回還原")
                        }
                    }
                }
            }

            val video = state.selectedVideo

            if (video == null) {
                // Empty state: prompt to select media
                EmptyPickerCard(onPickMediaClick = onPickMediaClick)
            } else {
                // Media info card (Video or Audio)
                VideoInfoCard(
                    video = video,
                    onReselectClick = onPickMediaClick
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Format & Output configuration
                ExtractionConfigCard(
                    selectedFormat = state.selectedFormat,
                    onFormatSelected = { viewModel.setFormat(it) },
                    customFileName = state.customFileName,
                    onFileNameChanged = { viewModel.setCustomFileName(it) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Trimming Slider with full-length timeline, playhead, and multi-segment markers
                RangeTrimSlider(
                    isEnabled = state.isTrimmingEnabled,
                    onToggle = { viewModel.setTrimmingEnabled(it) },
                    totalDurationMs = video.durationMs,
                    segments = state.segments,
                    activeSegmentIndex = state.activeSegmentIndex,
                    exportMode = state.exportMode,
                    previewPositionMs = state.previewCurrentPositionMs,
                    isPreviewPlaying = state.isPreviewPlaying,
                    onSeekPreview = { viewModel.seekPreview(it) },
                    onTogglePreviewPlayback = { viewModel.togglePreviewPlayback() },
                    onSetStartToCurrent = { viewModel.setStartToCurrentPosition() },
                    onSetEndToCurrent = { viewModel.setEndToCurrentPosition() },
                    onAddNewSegment = { viewModel.addNewSegment() },
                    onRemoveSegment = { viewModel.removeSegment(it) },
                    onSelectSegment = { viewModel.selectSegment(it) },
                    onExportModeChange = { viewModel.setExportMode(it) }
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Action Area: Extracting / Extract Button
                when (val extState = state.extractionState) {
                    is ExtractionState.Extracting -> {
                        ExtractingProgressCard(
                            progress = extState.progress,
                            statusText = extState.statusText,
                            onCancel = { viewModel.cancelExtraction() }
                        )
                    }
                    else -> {
                        Button(
                            onClick = { viewModel.startExtraction() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Audiotrack,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "一鍵提取音訊",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Error feedback
                AnimatedVisibility(
                    visible = state.extractionState is ExtractionState.Error,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val errorMsg = (state.extractionState as? ExtractionState.Error)?.message ?: ""
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = errorMsg,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                // Audio player on Success (with Gemini Share, Re-Edit, Delete & Automated AI Transcription)
                AnimatedVisibility(
                    visible = state.extractionState is ExtractionState.Success,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val success = state.extractionState as? ExtractionState.Success
                    if (success != null) {
                        Column {
                            Spacer(modifier = Modifier.height(16.dp))
                            AudioPlayerCard(
                                audioFile = success.file,
                                isPlaying = state.isAudioPlaying,
                                currentPositionMs = state.audioCurrentPositionMs,
                                durationMs = state.audioDurationMs,
                                onPlayPauseClick = { viewModel.toggleAudioPlayback() },
                                onSeek = { viewModel.seekAudio(it) },
                                activityName = state.activityName,
                                activityTime = state.activityTime,
                                onActivityNameChange = { viewModel.setActivityName(it) },
                                onReEditClick = { viewModel.reEditExtractedAudio() },
                                onDeleteClick = { viewModel.deleteExtractedAudio() },
                                isTranscribing = state.isTranscribing,
                                transcriptionText = state.transcriptionText,
                                onTranscriptionTextChange = { viewModel.updateTranscriptionText(it) },
                                transcriptionError = state.transcriptionError,
                                geminiApiKey = state.geminiApiKey,
                                promptTemplate = state.promptTemplate,
                                onPromptTemplateChange = { viewModel.updatePromptTemplate(it) },
                                onResetPromptTemplate = { viewModel.resetPromptTemplate() },
                                onRequestAiTranscription = { viewModel.requestAiTranscription() },
                                onStartFreeGeminiAutomation = { viewModel.startFreeGeminiAppAutomation(it) },
                                onSaveApiKey = { viewModel.updateGeminiApiKey(it) },
                                lockedLineContact = state.lockedLineContact,
                                isAccessibilityEnabled = state.isAccessibilityEnabled,
                                onUpdateLockedLineContact = { viewModel.updateLockedLineContact(it) },
                                onStartLineContactPicker = { viewModel.startLineContactPicker(it) },
                                onSendTranscriptToLine = { ctx, file, includeAudio -> viewModel.sendTranscriptToLine(ctx, file, includeAudio) },
                                callerPackage = state.callerPackage,
                                onReturnTranscriptToCaller = { viewModel.returnTranscriptToCaller(it) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun EmptyPickerCard(
    onPickMediaClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 40.dp)
            .clickable { onPickMediaClick() },
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "點擊選取要提取或剪輯的影片/音訊",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "支援影片 (MP4, MKV, MOV) 及音訊 (M4A, MP3, WAV, AAC)\n無損極速分離 • 多段裁切拼接 • 全自動 AI 逐字稿生成",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.3
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onPickMediaClick,
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("選取影片或音訊檔案")
            }
        }
    }
}

@Composable
private fun ExtractingProgressCard(
    progress: Float,
    statusText: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("取消", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
    }
}
