# AudioRip — Android 影片音訊提取器 (Video to Audio Extractor)

一個現代、輕量、高質感的 Android 應用程式，支援**純原生無損極速分離**與 **FFmpeg 全格式轉碼**，並具備**時間區間裁剪**、**內建試聽播放器**與**一鍵系統儲存與分享**功能。

---

## ✨ 核心特色

1. **雙引擎架構 (Dual Extraction Engines)**：
   - **⚡ 方案 A：原生極速分離 (預設 · 推薦)**：
     - 底層採用 Android 原生 `MediaExtractor` + `MediaMuxer`。
     - **不經重新編碼**，直接將影片容器（MP4/MKV）內的原始音訊流抽取並封裝為 `.m4a` (AAC)。
     - **速度極快**（1GB 影片通常 2~3 秒內完成），零 CPU 負擔、零音質耗損。
   - **🎛️ 方案 B：全格式轉碼 (FFmpeg)**：
     - 整合 `ffmpeg-kit-audio` 專用音訊引擎。
     - 支援輸出為 **MP3 (192kbps / 320kbps)**、**WAV (無壓縮 PCM)**、**FLAC (發燒級無損壓縮)**。
     - 當你需要相容於特定舊設備或需要特定音訊格式時，自動調用此引擎。
2. **✂️ 精準時間裁剪 (Audio Trimming)**：
   - 內建雙指針滑桿，可自由挑選「起始時間」與「結束時間」，精準只提取精彩片段或副歌。
3. **📁 零權限 Scoped Storage 儲存**：
   - 提取後的音訊自動透過 `MediaStore.Audio` 登記並儲存到系統 **`Music/AudioRip/`** 目錄。
   - 完全符合 Android 10 ~ 14 規範，無需向使用者索取危險的「全部儲存空間存取權限」。
4. **🎵 內建試聽與一鍵分享**：
   - 提取完成後，介面下方直接展開迷你播放器，可立即試聽、拖曳進度條。
   - 內建「分享音訊」按鈕，可直接透過系統分享選單發送至 LINE、Telegram、Google 雲端硬碟等。
5. **📲 支援「分享至」快速開啟**：
   - 在系統相簿或檔案瀏覽器中對任一影片點擊「分享」，可直接選取 **AudioRip** 開啟並提取。

---

## 🏗️ 專案技術棧

- **開發語言**：Kotlin 2.0
- **使用者介面**：Jetpack Compose + Material 3 (動態取色主題)
- **非同步架構**：Kotlin Coroutines + Flow + Android Architecture Components (ViewModel)
- **影音處理**：
  - 原生層：`android.media.MediaExtractor`、`android.media.MediaMuxer`、`android.media.MediaMetadataRetriever`
  - 轉碼層：`com.arthenica:ffmpeg-kit-audio:6.0-2`
  - 播放器：`androidx.media3:media3-exoplayer:1.4.0`
  - 縮圖加載：`io.coil-kt:coil-compose:2.7.0` + `coil-video`
- **相容版本**：
  - `minSdk = 26` (Android 8.0 Oreo 以上)
  - `targetSdk = 34` (Android 14)

---

## 🚀 如何在 Android Studio 中開啟與編譯

### 步驟 1：開啟專案
1. 下載並安裝 [Android Studio](https://developer.android.com/studio) (建議 Hedgehog / Jellyfish / Koala 或更新版本)。
2. 點擊 **Open**，選擇專案根目錄：
   ```
   F:\_proj\000_overlordstudio\AudioRip
   ```
3. Android Studio 會自動識別 Gradle 設定並進行 Gradle Sync。

### 步驟 2：連接設備與執行
1. 在手機上開啟「開發人員選項」並啟用「USB 偵錯」，透過傳輸線連接電腦（或啟動 Android Studio 內建的模擬器）。
2. 在工具列頂部點擊綠色的 **Run 'app' (Shift + F10)** 按鈕。
3. 應用程式編譯完成後會自動安裝至你的手機上。

### 步驟 3：產生正式發行版 APK
若需將 APK 傳送給其他手機安裝：
1. 點擊 Android Studio 頂部選單：**Build** ➔ **Build Bundle(s) / APK(s)** ➔ **Build APK(s)**。
2. 編譯完成後點擊通知中的 **locate**，即可取得 `app-debug.apk`。

---

## 📂 專案核心代碼導覽

```
AudioRip/app/src/main/java/com/overlord/audiorip/
├── MainActivity.kt                  // 入口 Activity，註冊 Photo Picker 與處理外部分享 Intent
├── data/
│   ├── AudioFormat.kt               // 支援格式定義 (M4A/MP3/WAV/FLAC) 與影片元數據結構
│   ├── NativeExtractorEngine.kt     // [方案 A] 原生 MediaExtractor + MediaMuxer 無損分離引擎
│   ├── FfmpegExtractorEngine.kt     // [方案 B] FFmpegKit 轉碼引擎 (MP3, WAV, FLAC)
│   └── MediaStoreHelper.kt          // 現代 Scoped Storage 保存與 FileProvider 分享邏輯
└── ui/
    ├── MainViewModel.kt             // UI 狀態管理、ExoPlayer 播放器控制與提取排程
    ├── MainScreen.kt                // 頂層 Compose 主畫面與動態卡片配置
    └── components/
        ├── VideoInfoCard.kt         // 影片縮圖、時長、大小與解析度卡片
        ├── ExtractionConfigCard.kt  // 格式切換 (M4A/MP3/WAV/FLAC) 與輸出檔名編輯
        ├── RangeTrimSlider.kt       // 時間區間裁剪滑桿
        └── AudioPlayerCard.kt       // 內建試聽播放控制器與分享按鈕
```
