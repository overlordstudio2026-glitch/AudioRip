package com.overlord.audiorip.core.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * LINE 直傳與群組分享管理器
 * 負責構建直達 LINE「選擇傳送對象」畫面的 Intent (SelectChatActivity)，
 * 支援純文字逐字稿、音訊檔案、以及音訊+逐字稿合併傳輸。
 */
object LineDirectShareManager {

    const val LINE_PACKAGE_NAME = "jp.naver.line.android"

    /**
     * 檢查手機是否安裝 LINE
     */
    fun isLineInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(LINE_PACKAGE_NAME, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 安全轉換本機 File 為 FileProvider 簽發之 content:// Uri
     */
    fun toSafeContentUri(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * 安全賦予 LINE 讀取 URI 權限
     */
    fun grantUriPermissionsSafely(context: Context, intent: Intent, uris: List<Uri>) {
        try {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            for (uri in uris) {
                if (uri.scheme.equals("content", ignoreCase = true)) {
                    context.grantUriPermission(
                        LINE_PACKAGE_NAME,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * 建立直達 LINE「選擇傳送對象」畫面的純文字快速訊息 Intent (逐字稿專用)
     */
    fun createDirectLineTextShareIntent(
        context: Context,
        messageText: String,
        targetGroup: String? = null
    ): Intent {
        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, messageText)
        }

        try {
            val pm = context.packageManager
            val resolves = pm.queryIntentActivities(baseIntent, 0)
                .filter { it.activityInfo.packageName == LINE_PACKAGE_NAME }

            val isKeepTarget = targetGroup?.contains("keep", ignoreCase = true) == true
            val chosenResolve = if (isKeepTarget) {
                resolves.firstOrNull { info ->
                    val clsName = info.activityInfo.name.lowercase()
                    val label = info.loadLabel(pm).toString().lowercase()
                    clsName.contains("keep") || label.contains("keep")
                } ?: resolves.firstOrNull()
            } else {
                // 優先選擇非 KeepMemo 的主分享選擇器 Activity (如 SelectChatActivity)
                resolves.firstOrNull { info ->
                    val clsName = info.activityInfo.name.lowercase()
                    val label = info.loadLabel(pm).toString().lowercase()
                    !clsName.contains("keep") && !label.contains("keep")
                } ?: resolves.firstOrNull()
            }

            if (chosenResolve != null) {
                baseIntent.component = ComponentName(
                    LINE_PACKAGE_NAME,
                    chosenResolve.activityInfo.name
                )
                baseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                return baseIntent
            }
        } catch (_: Exception) {
            // Fallback below
        }

        val chooserTitle = if (isLineInstalled(context)) "傳送逐字稿至 LINE" else "傳送逐字稿"
        return Intent.createChooser(baseIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 建立直達 LINE「選擇傳送對象」畫面的音訊與逐字稿合併傳輸 Intent
     */
    fun createDirectLineAudioAndTextShareIntent(
        context: Context,
        audioFile: File,
        messageText: String,
        targetGroup: String? = null
    ): Intent {
        val safeUri = toSafeContentUri(context, audioFile)
        val mime = when (audioFile.name.substringAfterLast(".").lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            else -> "audio/*"
        }

        val baseIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, safeUri)
            putExtra(Intent.EXTRA_TEXT, messageText)
            clipData = ClipData(
                ClipDescription("AudioRipMedia", arrayOf(mime, "text/plain")),
                ClipData.Item(safeUri)
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        grantUriPermissionsSafely(context, baseIntent, listOf(safeUri))

        try {
            val pm = context.packageManager
            val resolves = pm.queryIntentActivities(baseIntent, 0)
                .filter { it.activityInfo.packageName == LINE_PACKAGE_NAME }

            val chosenResolve = resolves.firstOrNull { info ->
                val clsName = info.activityInfo.name.lowercase()
                val label = info.loadLabel(pm).toString().lowercase()
                !clsName.contains("keep") && !label.contains("keep")
            } ?: resolves.firstOrNull()

            if (chosenResolve != null) {
                baseIntent.component = ComponentName(
                    LINE_PACKAGE_NAME,
                    chosenResolve.activityInfo.name
                )
                baseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                return baseIntent
            }
        } catch (_: Exception) {}

        val chooserTitle = if (isLineInstalled(context)) "分享音訊與逐字稿至 LINE" else "分享音訊與逐字稿"
        return Intent.createChooser(baseIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 建立自由選擇其他 LINE 好友或群組的 Intent (Chooser 模式)
     */
    fun createLineChooserShareIntent(
        context: Context,
        messageText: String,
        audioFile: File? = null
    ): Intent {
        val baseIntent = if (audioFile != null && audioFile.exists()) {
            val safeUri = toSafeContentUri(context, audioFile)
            val mime = "audio/*"
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, safeUri)
                putExtra(Intent.EXTRA_TEXT, messageText)
                clipData = ClipData(
                    ClipDescription("AudioRipMedia", arrayOf(mime, "text/plain")),
                    ClipData.Item(safeUri)
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, messageText)
            }
        }

        if (isLineInstalled(context)) {
            baseIntent.setPackage(LINE_PACKAGE_NAME)
        }
        return Intent.createChooser(baseIntent, "選擇 LINE 聯絡人或群組傳送").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
