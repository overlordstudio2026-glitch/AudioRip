package com.overlord.audiorip.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.overlord.audiorip.data.MultiCutExportMode
import com.overlord.audiorip.data.TrimSegment

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RangeTrimSlider(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    totalDurationMs: Long,
    segments: List<TrimSegment>,
    activeSegmentIndex: Int,
    exportMode: MultiCutExportMode,
    previewPositionMs: Long,
    isPreviewPlaying: Boolean,
    onSeekPreview: (Long) -> Unit,
    onTogglePreviewPlayback: () -> Unit,
    onSetStartToCurrent: () -> Unit,
    onSetEndToCurrent: () -> Unit,
    onAddNewSegment: () -> Unit,
    onRemoveSegment: (Int) -> Unit,
    onSelectSegment: (Int) -> Unit,
    onExportModeChange: (MultiCutExportMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ContentCut,
                        contentDescription = null,
                        tint = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "多段自訂裁切 (邊聽邊剪)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isEnabled) "全景可視化時間軌 • 剔除贅言雜音" else "提取完整音訊，未開啟裁剪",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle
                )
            }

            if (isEnabled && totalDurationMs > 0L) {
                Spacer(modifier = Modifier.height(14.dp))

                // Export Mode Selection: Merge Concat vs Separate Files
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = exportMode == MultiCutExportMode.MERGE_CONCAT,
                        onClick = { onExportModeChange(MultiCutExportMode.MERGE_CONCAT) },
                        label = { Text("拼接為單一音檔 (剔除無效片段)") },
                        leadingIcon = {
                            Icon(Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    FilterChip(
                        selected = exportMode == MultiCutExportMode.SEPARATE_FILES,
                        onClick = { onExportModeChange(MultiCutExportMode.SEPARATE_FILES) },
                        label = { Text("分離為多個檔案") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Full-length Timeline Panorama View
                Text(
                    text = "全景時間軌 (點擊或拖曳指針，亮色為保留段，暗色為捨棄段):",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1B202A)) // Darkened background representing full original length
                        .border(1.dp, Color(0xFF2C3240), RoundedCornerShape(12.dp))
                        .pointerInput(totalDurationMs) {
                            detectTapGestures { offset ->
                                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                                val seekMs = (fraction * totalDurationMs).toLong()
                                onSeekPreview(seekMs)
                            }
                        }
                        .pointerInput(totalDurationMs) {
                            detectDragGestures { change, _ ->
                                change.consume()
                                val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                val seekMs = (fraction * totalDurationMs).toLong()
                                onSeekPreview(seekMs)
                            }
                        }
                ) {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val trackWidthPx = constraints.maxWidth.toFloat()

                    // Render each highlighted retained segment
                    segments.forEachIndexed { idx, seg ->
                        val startFraction = (seg.startMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
                        val endFraction = (seg.endMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
                        val segWidthFraction = (endFraction - startFraction).coerceAtLeast(0.005f)

                        val segStartX = (startFraction * trackWidthPx)
                        val segWidth = (segWidthFraction * trackWidthPx)
                        val segWidthDp = with(density) { segWidth.toDp() }

                        val isActive = idx == activeSegmentIndex
                        val segmentBrush = if (isActive) {
                            Brush.horizontalGradient(listOf(Color(0xFF00E676), Color(0xFF00B0FF)))
                        } else {
                            Brush.horizontalGradient(listOf(Color(0xFF00897B).copy(alpha = 0.75f), Color(0xFF1976D2).copy(alpha = 0.75f)))
                        }

                        Box(
                            modifier = Modifier
                                .offset { IntOffset(segStartX.toInt(), 6.dp.roundToPx()) }
                                .width(segWidthDp)
                                .height(46.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(segmentBrush)
                                .border(
                                    width = if (isActive) 1.5.dp else 0.5.dp,
                                    color = if (isActive) Color.White else Color.White.copy(alpha = 0.4f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelectSegment(idx) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "段落 ${idx + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Render Playhead Scrubber Pointer
                    val pointerFraction = (previewPositionMs.toFloat() / totalDurationMs).coerceIn(0f, 1f)
                    val pointerX = (pointerFraction * trackWidthPx)

                    Box(
                        modifier = Modifier
                            .offset { IntOffset((pointerX - 1.5.dp.roundToPx()).toInt(), 0) }
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(Color.White)
                    )

                    // Scrubber top indicator handle
                    Box(
                        modifier = Modifier
                            .offset { IntOffset((pointerX - 7.dp.roundToPx()).toInt(), 0) }
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFFD54F))
                            .border(1.5.dp, Color.White, CircleShape)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Playback and Precision Anchoring Bar
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Play/Pause & Scrubber Time Readout
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledIconButton(
                                onClick = onTogglePreviewPlayback,
                                shape = CircleShape,
                                modifier = Modifier.size(38.dp),
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (isPreviewPlaying) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(
                                    imageVector = if (isPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPreviewPlaying) "暫停" else "播放預覽",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isPreviewPlaying) "試聽中..." else "當前指針位置",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isPreviewPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${formatDurationWithMillis(previewPositionMs)} / ${formatDurationWithMillis(totalDurationMs)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Set Start & Set End buttons (Instantly uses current playhead position)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = onSetStartToCurrent,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("設為起點", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = onSetEndToCurrent,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text("設為終點", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Segments Management Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "已標記保留段落 (${segments.size}):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedButton(
                        onClick = onAddNewSegment,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("新增段落", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Segment Cards List
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    segments.forEachIndexed { idx, seg ->
                        val isActive = idx == activeSegmentIndex
                        val segSpan = (seg.endMs - seg.startMs).coerceAtLeast(0L)

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = if (isActive) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectSegment(idx) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "段落 ${idx + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "${formatDurationWithMillis(seg.startMs)} ~ ${formatDurationWithMillis(seg.endMs)}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "(長度: ${formatDurationWithMillis(segSpan)})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (segments.size > 1) {
                                    FilledIconButton(
                                        onClick = { onRemoveSegment(idx) },
                                        shape = CircleShape,
                                        colors = IconButtonDefaults.filledIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                                        ),
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "刪除此段",
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Summary Badge
                val totalRetainedMs = segments.sumOf { (it.endMs - it.startMs).coerceAtLeast(0L) }
                val discardedMs = (totalDurationMs - totalRetainedMs).coerceAtLeast(0L)

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "拼接後總時長: ${formatDurationWithMillis(totalRetainedMs)} • 已剔除無效音訊: ${formatDurationWithMillis(discardedMs)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

fun formatDurationWithMillis(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val fraction = (ms % 1000) / 100
    return String.format(java.util.Locale.US, "%02d:%02d.%d", minutes, seconds, fraction)
}
