package com.overlord.audiorip.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

@OptIn(DelicateCoroutinesApi::class)
object TransformerExtractorEngine {

    suspend fun transcodeAudio(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        format: OutputAudioFormat,
        startMs: Long = 0L,
        endMs: Long = 0L,
        onProgress: (Float) -> Unit
    ): Result<File> {
        val segs = if (startMs > 0L || endMs > 0L) {
            listOf(TrimSegment(startMs = startMs, endMs = endMs))
        } else {
            emptyList()
        }
        return transcodeAudioSegments(context, inputUri, outputFile, format, segs, onProgress)
    }

    suspend fun transcodeAudioSegments(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        format: OutputAudioFormat,
        segments: List<TrimSegment>,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.Main) {
        val sequenceItems = if (segments.isEmpty()) {
            val mediaItem = MediaItem.Builder().setUri(inputUri).build()
            listOf(EditedMediaItem.Builder(mediaItem).setRemoveVideo(true).build())
        } else {
            segments.sortedBy { it.startMs }.map { seg ->
                val clippingConfig = MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(seg.startMs)
                    .setEndPositionMs(if (seg.endMs > 0L) seg.endMs else C.TIME_UNSET)
                    .build()
                val mediaItem = MediaItem.Builder()
                    .setUri(inputUri)
                    .setClippingConfiguration(clippingConfig)
                    .build()
                EditedMediaItem.Builder(mediaItem)
                    .setRemoveVideo(true)
                    .build()
            }
        }

        val sequence = EditedMediaItemSequence(sequenceItems)
        val composition = Composition.Builder(sequence).build()

        suspendCancellableCoroutine { continuation ->
            var isFinished = false

            val transformer = Transformer.Builder(context)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        isFinished = true
                        onProgress(1.0f)
                        if (continuation.isActive) {
                            continuation.resume(Result.success(outputFile))
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exception: ExportException
                    ) {
                        isFinished = true
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(exception))
                        }
                    }
                })
                .build()

            continuation.invokeOnCancellation {
                transformer.cancel()
            }

            try {
                transformer.start(composition, outputFile.absolutePath)

                // Start polling progress on background
                GlobalScope.launch(Dispatchers.Main) {
                    val progressHolder = ProgressHolder()
                    while (!isFinished && isActive) {
                        val progressState = transformer.getProgress(progressHolder)
                        if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress((progressHolder.progress / 100.0f).coerceIn(0f, 0.99f))
                        }
                        delay(250)
                    }
                }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resume(Result.failure(e))
                }
            }
        }
    }
}
