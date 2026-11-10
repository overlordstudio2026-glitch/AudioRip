package com.overlord.audiorip.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

object WavExtractorEngine {

    suspend fun extractWav(
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
        return extractWavSegments(context, inputUri, outputFile, segs, onProgress)
    }

    suspend fun extractWavSegments(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        segments: List<TrimSegment>,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var fos: FileOutputStream? = null

        try {
            // First pass: extract format info
            val probeExtractor = MediaExtractor()
            probeExtractor.setDataSource(context, inputUri, null)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null

            for (i in 0 until probeExtractor.trackCount) {
                val format = probeExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || inputFormat == null) {
                probeExtractor.release()
                return@withContext Result.failure(IllegalStateException("素材中未找到有效音軌"))
            }

            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            val channelCount = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }
            val mediaDurationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            probeExtractor.release()

            fos = FileOutputStream(outputFile)
            // Placeholder 44-byte WAV header, rewritten after decoding completes
            writeWavHeader(fos, 0, 0, sampleRate, channelCount, 16)

            val effectiveSegments = if (segments.isEmpty()) {
                listOf(TrimSegment(startMs = 0L, endMs = (mediaDurationUs / 1000L).coerceAtLeast(1L)))
            } else {
                segments.sortedBy { it.startMs }
            }

            val totalSpanMs = effectiveSegments.sumOf { (it.endMs - it.startMs).coerceAtLeast(1L) }
            var totalPcmBytes = 0L
            var accumulatedProcessedMs = 0L

            for (seg in effectiveSegments) {
                val extractor = MediaExtractor()
                var decoder: MediaCodec? = null
                try {
                    extractor.setDataSource(context, inputUri, null)
                    extractor.selectTrack(audioTrackIndex)

                    decoder = MediaCodec.createDecoderByType(mime)
                    decoder.configure(inputFormat, null, null, 0)
                    decoder.start()

                    val segStartUs = seg.startMs * 1000L
                    val segEndUs = if (seg.endMs > 0L) seg.endMs * 1000L else if (mediaDurationUs > 0L) mediaDurationUs else Long.MAX_VALUE
                    val segDurationMs = (segEndUs - segStartUs) / 1000L

                    if (segStartUs > 0L) {
                        extractor.seekTo(segStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    } else {
                        extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    }

                    val bufferInfo = MediaCodec.BufferInfo()
                    var sawInputEOS = false
                    var sawOutputEOS = false

                    while (!sawOutputEOS && coroutineContext.isActive) {
                        if (!sawInputEOS) {
                            val inputBufIndex = decoder.dequeueInputBuffer(10000)
                            if (inputBufIndex >= 0) {
                                val inputBuf = decoder.getInputBuffer(inputBufIndex) ?: ByteBuffer.allocate(0)
                                inputBuf.clear()
                                val sampleSize = extractor.readSampleData(inputBuf, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sawInputEOS = true
                                } else {
                                    val sampleTime = extractor.sampleTime
                                    if (sampleTime > segEndUs) {
                                        decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        sawInputEOS = true
                                    } else {
                                        decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, sampleTime, extractor.sampleFlags)
                                        extractor.advance()
                                    }
                                }
                            }
                        }

                        val outputBufIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                        if (outputBufIndex >= 0) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEOS = true
                            }

                            if (bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= segStartUs) {
                                val outputBuf = decoder.getOutputBuffer(outputBufIndex)
                                if (outputBuf != null) {
                                    val chunk = ByteArray(bufferInfo.size)
                                    outputBuf.position(bufferInfo.offset)
                                    outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                                    outputBuf.get(chunk)

                                    fos.write(chunk)
                                    totalPcmBytes += chunk.size

                                    val segProgressMs = ((bufferInfo.presentationTimeUs - segStartUs) / 1000L).coerceIn(0L, segDurationMs)
                                    val overallProgress = ((accumulatedProcessedMs + segProgressMs).toFloat() / totalSpanMs.toFloat()).coerceIn(0f, 0.99f)
                                    onProgress(overallProgress)
                                }
                            }
                            decoder.releaseOutputBuffer(outputBufIndex, false)
                        }
                    }
                    accumulatedProcessedMs += segDurationMs
                } finally {
                    try { extractor.release() } catch (_: Exception) {}
                    try {
                        decoder?.stop()
                        decoder?.release()
                    } catch (_: Exception) {}
                }
            }

            fos.flush()
            fos.close()
            fos = null

            // Update WAV header with exact total PCM data size
            updateWavHeader(outputFile, totalPcmBytes, sampleRate, channelCount, 16)
            onProgress(1.0f)
            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { fos?.close() } catch (_: Exception) {}
        }
    }

    private fun writeWavHeader(
        out: FileOutputStream,
        totalAudioLen: Long,
        totalDataLen: Long,
        longSampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = (longSampleRate * channels * bitsPerSample / 8).toLong()
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xffL).toByte()
        header[5] = (totalDataLen shr 8 and 0xffL).toByte()
        header[6] = (totalDataLen shr 16 and 0xffL).toByte()
        header[7] = (totalDataLen shr 24 and 0xffL).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM format code
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = (longSampleRate shr 8 and 0xff).toByte()
        header[26] = (longSampleRate shr 16 and 0xff).toByte()
        header[27] = (longSampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xffL).toByte()
        header[29] = (byteRate shr 8 and 0xffL).toByte()
        header[30] = (byteRate shr 16 and 0xffL).toByte()
        header[31] = (byteRate shr 24 and 0xffL).toByte()
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xffL).toByte()
        header[41] = (totalAudioLen shr 8 and 0xffL).toByte()
        header[42] = (totalAudioLen shr 16 and 0xffL).toByte()
        header[43] = (totalAudioLen shr 24 and 0xffL).toByte()

        out.write(header, 0, 44)
    }

    private fun updateWavHeader(
        file: File,
        totalAudioLen: Long,
        longSampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = (longSampleRate * channels * bitsPerSample / 8).toLong()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(
                byteArrayOf(
                    (totalDataLen and 0xffL).toByte(),
                    (totalDataLen shr 8 and 0xffL).toByte(),
                    (totalDataLen shr 16 and 0xffL).toByte(),
                    (totalDataLen shr 24 and 0xffL).toByte()
                )
            )

            raf.seek(40)
            raf.write(
                byteArrayOf(
                    (totalAudioLen and 0xffL).toByte(),
                    (totalAudioLen shr 8 and 0xffL).toByte(),
                    (totalAudioLen shr 16 and 0xffL).toByte(),
                    (totalAudioLen shr 24 and 0xffL).toByte()
                )
            )
        }
    }
}
