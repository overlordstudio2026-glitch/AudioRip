package com.overlord.audiorip.data

enum class OutputAudioFormat(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val isNativeSupported: Boolean,
    val description: String
) {
    M4A_NATIVE(
        displayName = "M4A (原生極速 · 推薦)",
        extension = "m4a",
        mimeType = "audio/mp4",
        isNativeSupported = true,
        description = "直接解流無損音軌，2秒極速完成，零品質損耗"
    ),
    WAV_PCM(
        displayName = "WAV (純淨無壓縮 PCM)",
        extension = "wav",
        mimeType = "audio/wav",
        isNativeSupported = true,
        description = "廣播級最高保真格式，原音重現，適合剪輯與收藏"
    ),
    AAC_TRANSCODE(
        displayName = "AAC (高保真轉碼 256k)",
        extension = "m4a",
        mimeType = "audio/mp4",
        isNativeSupported = true,
        description = "Media3 高階音訊處理器，音質細節純淨"
    ),
    MP3_COMPAT(
        displayName = "MP3 (廣泛相容格式)",
        extension = "mp3",
        mimeType = "audio/mpeg",
        isNativeSupported = false,
        description = "廣泛相容各大車載音響、播放器與舊設備"
    )
}

data class VideoMetadata(
    val uri: android.net.Uri,
    val fileName: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val audioMime: String?,
    val width: Int,
    val height: Int,
    val isAudioOnly: Boolean = false
)

data class TrimSegment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val startMs: Long,
    val endMs: Long
)

enum class MultiCutExportMode(val displayName: String) {
    MERGE_CONCAT("拼接為單一音檔 (推薦)"),
    SEPARATE_FILES("各自分離為多個檔案")
}

data class ExtractionParams(
    val inputUri: android.net.Uri,
    val outputFormat: OutputAudioFormat,
    val customFileName: String,
    val segments: List<TrimSegment> = emptyList(),
    val exportMode: MultiCutExportMode = MultiCutExportMode.MERGE_CONCAT
)
