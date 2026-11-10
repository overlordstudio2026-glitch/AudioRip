package com.overlord.audiorip.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.overlord.audiorip.data.MediaStoreHelper
import java.io.File

@Composable
fun AudioPlayerCard(
    audioFile: File,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    activityName: String = "",
    activityTime: String = "",
    onActivityNameChange: (String) -> Unit = {},
    onReEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    // Automated AI Transcription props
    isTranscribing: Boolean = false,
    transcriptionText: String? = null,
    onTranscriptionTextChange: (String) -> Unit = {},
    transcriptionError: String? = null,
    geminiApiKey: String = "",
    promptTemplate: String = "",
    onPromptTemplateChange: (String) -> Unit = {},
    onResetPromptTemplate: () -> Unit = {},
    onRequestAiTranscription: () -> Unit = {},
    onStartFreeGeminiAutomation: (Context) -> Unit = {},
    onSaveApiKey: (String) -> Unit = {},
    // LINE Direct & Automated Sharing props
    lockedLineContact: String = "",
    isAccessibilityEnabled: Boolean = false,
    onUpdateLockedLineContact: (String) -> Unit = {},
    onStartLineContactPicker: (Context) -> Unit = {},
    onSendTranscriptToLine: (Context, File?, Boolean) -> Unit = { _, _, _ -> },
    callerPackage: String? = null,
    onReturnTranscriptToCaller: (Context) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var inputApiKey by remember(geminiApiKey) { mutableStateOf(geminiApiKey) }
    var showContactEditDialog by remember { mutableStateOf(false) }
    var inputContactName by remember(lockedLineContact) { mutableStateOf(lockedLineContact) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        ),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Success header
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF00C853),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "音訊提取成功！",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = audioFile.name,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // API Key Settings Button
                FilledIconButton(
                    onClick = { showApiKeyDialog = true },
                    shape = CircleShape,
                    modifier = Modifier.size(34.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (geminiApiKey.isNotBlank()) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "設定 Gemini API Key",
                        tint = if (geminiApiKey.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Saved path notice
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "已保存至: Music/AudioRip/${audioFile.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // In-app Player Controller
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledIconButton(
                    onClick = onPlayPauseClick,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暫停" else "播放",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    val safeDuration = durationMs.coerceAtLeast(1L)
                    Slider(
                        value = currentPositionMs.coerceIn(0L, safeDuration).toFloat(),
                        onValueChange = { onSeek(it.toLong()) },
                        valueRange = 0f..safeDuration.toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatPlayerDuration(currentPositionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = formatPlayerDuration(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Activity Info Configuration
            OutlinedTextField(
                value = activityName,
                onValueChange = onActivityNameChange,
                label = { Text("活動/會議名稱 (用於逐字稿開頭)") },
                placeholder = { Text("例：產品研發週會 / 演講分享") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 預先生成且可隨時編輯、自動記憶的「轉逐字稿送出文字 (Prompt)」設定區塊
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📝 轉逐字稿送出文字 (可直接編輯・自動記憶)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(
                            onClick = {
                                onResetPromptTemplate()
                                Toast.makeText(context, "已恢復預設轉逐字稿送出文字範本", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text("恢復預設", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    OutlinedTextField(
                        value = promptTemplate,
                        onValueChange = onPromptTemplateChange,
                        placeholder = { Text("請輸入要送給 Gemini 的轉逐字稿指令文字...") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "💡 送出時將自動在結尾附加上方「活動時間」與「活動名稱」，並自動填入 Gemini 對話框送出。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Track 1: Automated AI Transcription (In-App Direct Extraction)
            Button(
                onClick = {
                    if (geminiApiKey.isBlank()) {
                        showApiKeyDialog = true
                    } else {
                        onRequestAiTranscription()
                    }
                },
                enabled = !isTranscribing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (isTranscribing) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AI 正在聆聽並生成逐字稿...")
                } else {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "一鍵 AI 生成逐字稿 (全自動取回)", fontWeight = FontWeight.Bold)
                }
            }

            // Transcription Error Feedback (with fallback guidance)
            AnimatedVisibility(
                visible = !transcriptionError.isNullOrBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = transcriptionError ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            // Render Retrieved Transcription Result Card
            AnimatedVisibility(
                visible = !transcriptionText.isNullOrBlank(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val transcript = transcriptionText ?: ""
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("繁體中文逐字稿內容：", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                            Row {
                                FilledIconButton(
                                    onClick = {
                                        onTranscriptionTextChange("")
                                        Toast.makeText(context, "已一鍵清除逐字稿內容", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "清除逐字稿", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                FilledIconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", transcript))
                                        Toast.makeText(context, "逐字稿已複製至剪貼簿！", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "複製逐字稿", modifier = Modifier.size(16.dp))
                                }

                                Spacer(modifier = Modifier.width(6.dp))

                                FilledIconButton(
                                    onClick = {
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, transcript)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "傳送逐字稿至..."))
                                    },
                                    modifier = Modifier.size(32.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "分享逐字稿", modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = transcript,
                            onValueChange = onTranscriptionTextChange,
                            label = { Text("逐字稿檢查與修正 (可直接點擊修改內容)") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            shape = RoundedCornerShape(8.dp)
                        )

                        if (!callerPackage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { onReturnTranscriptToCaller(context) },
                                modifier = Modifier.fillMaxWidth().height(40.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("🔙 將修正後逐字稿傳回錄影 App (OmniCamPro)", fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // LINE 跨 App 自動傳送控制區
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Chat,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (lockedLineContact.isNotBlank()) "🎯 鎖定目標: $lockedLineContact" else "🎯 尚未設定鎖定目標",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (lockedLineContact.isNotBlank()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                                    )
                                }

                                // 無障礙狀態提示膠囊
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isAccessibilityEnabled) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    } else {
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                    },
                                    modifier = Modifier.clickable {
                                        if (!isAccessibilityEnabled) {
                                            try {
                                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                })
                                                Toast.makeText(context, "請在「已下載的應用程式」中開啟 AudioRip 無障礙服務", Toast.LENGTH_LONG).show()
                                            } catch (_: Exception) {}
                                        }
                                    }
                                ) {
                                    Text(
                                        text = if (isAccessibilityEnabled) "🟢 自動點擊就緒" else "🟡 點此開啟無障礙",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isAccessibilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 目標切換設定按鈕
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onStartLineContactPicker(context) },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("在 LINE 點選吸取", style = MaterialTheme.typography.labelMedium)
                                }

                                OutlinedButton(
                                    onClick = {
                                        inputContactName = lockedLineContact
                                        showContactEditDialog = true
                                    },
                                    modifier = Modifier.weight(1f).height(36.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("手動輸入目標", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 主動作 1: 一鍵傳送逐字稿至 LINE
                            Button(
                                onClick = {
                                    if (lockedLineContact.isBlank()) {
                                        showContactEditDialog = true
                                        Toast.makeText(context, "請先設定要傳送的 LINE 聯絡人或群組名稱", Toast.LENGTH_SHORT).show()
                                    } else {
                                        onSendTranscriptToLine(context, audioFile, false)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (lockedLineContact.isNotBlank()) "一鍵傳送逐字稿至 [$lockedLineContact]" else "一鍵傳送逐字稿至 LINE",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // 次要動作 2: 音訊與逐字稿一同傳送 & 選擇其他好友
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (lockedLineContact.isBlank()) {
                                            showContactEditDialog = true
                                            Toast.makeText(context, "請先設定要傳送的 LINE 聯絡人或群組名稱", Toast.LENGTH_SHORT).show()
                                        } else {
                                            onSendTranscriptToLine(context, audioFile, true)
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("音訊+逐字稿同發", style = MaterialTheme.typography.labelMedium)
                                }

                                OutlinedButton(
                                    onClick = {
                                        // 自由選擇其他好友 (不鎖定單一目標)
                                        onSendTranscriptToLine(context, null, false)
                                    },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("選其他 LINE 好友", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Track 2: 免 API 自動傳送至 Gemini 並自動複製取回逐字稿
            Button(
                onClick = {
                    onStartFreeGeminiAutomation(context)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Icon(imageVector = Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "🤖 免 API 自動傳送 Gemini 並取回逐字稿",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Secondary Actions: Re-Edit & Standard Share
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onReEditClick,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("再微調編輯")
                }

                OutlinedButton(
                    onClick = {
                        val mime = getMimeTypeFromFileName(audioFile.name)
                        val shareIntent = MediaStoreHelper.createShareIntent(context, audioFile, mime)
                        context.startActivity(Intent.createChooser(shareIntent, "分享音訊檔案至..."))
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("一般分享")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Delete Action
            TextButton(
                onClick = { showDeleteConfirmDialog = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("刪除此檔案", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    // API Key Configuration Dialog
    if (showApiKeyDialog) {
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text("設定 Gemini API Key") },
            text = {
                Column {
                    Text(
                        text = "輸入您的 Google AI Studio API Key，即可啟用「一鍵全自動取回逐字稿」。若未設定，仍可使用下方的「免 API 系統分享」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = inputApiKey,
                        onValueChange = { inputApiKey = it },
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("AIzaSy...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveApiKey(inputApiKey.trim())
                        showApiKeyDialog = false
                        Toast.makeText(context, "API Key 已儲存！", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("儲存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // LINE Locked Contact Edit Dialog
    if (showContactEditDialog) {
        AlertDialog(
            onDismissRequest = { showContactEditDialog = false },
            title = { Text("設定鎖定 LINE 聯絡人 / 群組") },
            text = {
                Column {
                    Text(
                        text = "輸入欲自動發送的 LINE 聯絡人或群組名稱（須與 LINE 中的名稱完全一致，尾端人數括號如 (15) 可略）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = inputContactName,
                        onValueChange = { inputContactName = it },
                        label = { Text("LINE 聯絡人 / 群組名稱") },
                        placeholder = { Text("例: 核心團隊、現場助理") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = inputContactName.trim()
                        if (trimmed.isNotBlank()) {
                            onUpdateLockedLineContact(trimmed)
                            showContactEditDialog = false
                            Toast.makeText(context, "已鎖定 LINE 目標: $trimmed", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "名稱不能為空", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("確定儲存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showContactEditDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("確認刪除檔案？") },
            text = { Text("刪除後將從本地快取與系統 Music/AudioRip 目錄中移除：\n${audioFile.name}") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteClick()
                        Toast.makeText(context, "檔案已刪除", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("確定刪除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

private fun isPackageInstalled(context: Context, packageName: String): Boolean {
    return try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

private fun formatPlayerDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
}

private fun getMimeTypeFromFileName(fileName: String): String {
    return when (fileName.substringAfterLast(".").lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "m4a", "aac" -> "audio/mp4"
        else -> "audio/*"
    }
}
