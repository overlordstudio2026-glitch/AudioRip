package com.overlord.audiorip.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

object NativeExtractorEngine {

    suspend fun extractAudio(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        startMs: Long = 0L,
        endMs: Long = 0L,
        onProgress: (Float) -> Unit
    ): Result<File> {
        val segs = if (startMs > 0L || endMs > 0L) {
            listOf(TrimSegment(startMs = startMs, endMs = endMs))
        } else {
            emptyList()
        }
        return extractAudioSegments(context, inputUri, outputFile, segs, onProgress)
    }

    suspend fun extractAudioSegments(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        segments: List<TrimSegment>,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            extractor.setDataSource(context, inputUri, null)
            val numTracks = extractor.trackCount
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until numTracks) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                return@withContext Result.failure(IllegalStateException("該素材不包含任何可識別的音訊軌道"))
            }

            val mediaDurationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            extractor.selectTrack(audioTrackIndex)

            // Setup MediaMuxer output to M4A (MPEG_4 container)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrackIndex = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                1024 * 1024 // 1MB buffer
            }
            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            val effectiveSegments = if (segments.isEmpty()) {
                listOf(TrimSegment(startMs = 0L, endMs = (mediaDurationUs / 1000L).coerceAtLeast(1L)))
            } else {
                segments.sortedBy { it.startMs }
            }

            val totalSpanMs = effectiveSegments.sumOf { (it.endMs - it.startMs).coerceAtLeast(1L) }
            var writtenDurationUs = 0L

            for ((index, seg) in effectiveSegments.withIndex()) {
                val segStartUs = seg.startMs * 1000L
                val segEndUs = if (seg.endMs > 0L) seg.endMs * 1000L else if (mediaDurationUs > 0L) mediaDurationUs else Long.MAX_VALUE

                if (segStartUs > 0L) {
                    extractor.seekTo(segStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                } else {
                    extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }

                var segFirstSampleUs = -1L
                var segLastSampleDeltaUs = 0L

                while (coroutineContext.isActive) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        break // End of stream
                    }

                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs > segEndUs) {
                        break // Reached end of current segment
                    }

                    if (sampleTimeUs >= segStartUs) {
                        if (segFirstSampleUs == -1L) {
                            segFirstSampleUs = sampleTimeUs
                        }
                        segLastSampleDeltaUs = (sampleTimeUs - segFirstSampleUs).coerceAtLeast(0L)

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        // Ensure strictly increasing presentation timestamps across concatenated segments
                        bufferInfo.presentationTimeUs = writtenDurationUs + segLastSampleDeltaUs
                        bufferInfo.flags = extractor.sampleFlags

                        muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)

                        val currentTotalWrittenMs = (writtenDurationUs + segLastSampleDeltaUs) / 1000L
                        val progress = (currentTotalWrittenMs.toFloat() / totalSpanMs.toFloat()).coerceIn(0f, 0.99f)
                        onProgress(progress)
                    }

                    if (!extractor.advance()) {
                        break
                    }
                }

                // Offset timestamp for the next segment by at least a small frame delta (approx 23ms for AAC)
                writtenDurationUs += segLastSampleDeltaUs + 23_220L
            }

            onProgress(1.0f)
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {}

            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }
}
