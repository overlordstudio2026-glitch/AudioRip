package com.overlord.audiorip.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object MediaStoreHelper {

    suspend fun saveAudioToMusicFolder(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/AudioRip")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext Result.failure(Exception("無法在 MediaStore 建立音訊紀錄"))

                resolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(sourceFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: return@withContext Result.failure(Exception("無法寫入目標音訊檔案"))

                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

                Result.success(uri)
            } else {
                // Legacy Android (<= API 28)
                val musicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "AudioRip"
                )
                if (!musicDir.exists()) {
                    musicDir.mkdirs()
                }

                val targetFile = File(musicDir, displayName)
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Notify system media scanner
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf(mimeType),
                    null
                )

                val fileUri = Uri.fromFile(targetFile)
                Result.success(fileUri)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun createShareIntent(context: Context, audioFile: File, mimeType: String): Intent {
        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            audioFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun createShareWithPromptIntent(
        context: Context,
        audioFile: File,
        mimeType: String,
        promptText: String,
        targetPackage: String? = null
    ): Intent {
        val contentUri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            audioFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_TEXT, promptText)
            clipData = android.content.ClipData.newRawUri("AudioRip_Audio", contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!targetPackage.isNullOrBlank()) {
                setPackage(targetPackage)
            }
        }
    }

    suspend fun deleteAudio(context: Context, mediaUri: Uri?, localFile: File?): Boolean = withContext(Dispatchers.IO) {
        var deleted = false
        try {
            // Delete local cache file
            if (localFile != null && localFile.exists()) {
                deleted = localFile.delete() || deleted
            }
            // Delete MediaStore entry
            if (mediaUri != null) {
                val rows = context.contentResolver.delete(mediaUri, null, null)
                if (rows > 0) deleted = true
            }
        } catch (_: Exception) {
        }
        deleted
    }
}
