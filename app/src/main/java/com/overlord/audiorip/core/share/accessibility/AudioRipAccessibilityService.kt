package com.overlord.audiorip.core.share.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.overlord.audiorip.MainActivity
import com.overlord.audiorip.core.share.LineDirectShareManager

/**
 * AudioRip 無障礙自動傳送至指定鎖定 LINE 群組/聯絡人服務
 *
 * 支援流程：
 * 1. 逐字稿/音訊自動直傳：
 *    - 偵測進入 LINE「選擇傳送對象」畫面
 *    - 自動尋找或搜尋鎖定之群組/聯絡人並勾選
 *    - 自動點擊單一對象確認傳送按鈕
 *    - 傳送完畢自動秒回 AudioRip
 * 2. 聯絡人吸取模式 (Contact Picker Mode)：
 *    - 點選設定後開啟 LINE，使用者在 LINE 點選任意好友/群組
 *    - 服務自動捕獲該名稱並帶回 AudioRip 自動儲存
 */
class AudioRipAccessibilityService : AccessibilityService() {

    enum class AutoShareStep {
        IDLE,
        FIND_TARGET_GROUP,
        CLICK_CONFIRM_SEND,
        VERIFY_TRANSMISSION_AND_RETURN
    }

    enum class GeminiAutoStep {
        IDLE,
        FILL_PROMPT_AND_SEND,
        WAIT_GENERATION_AND_COPY,
        RETURN_WITH_RESULT
    }

    companion object {
        private const val TAG = "AudioRipAccessibility"
        private const val LINE_PKG = "jp.naver.line.android"
        private const val GEMINI_PKG = "com.google.android.apps.bard"
        private const val JOB_TIMEOUT_MS = 45_000L
        private const val GEMINI_TIMEOUT_MS = 120_000L
        private const val RETURN_DELAY_TEXT_MS = 600L
        private const val TICK_INTERVAL_MS = 250L

        const val ACTION_RECEIVE_TRANSCRIPT = "com.overlord.omnicam.ACTION_RECEIVE_TRANSCRIPT"
        const val EXTRA_TRANSCRIPT_TEXT = "EXTRA_TRANSCRIPT_TEXT"
        const val EXTRA_READ_CLIPBOARD_TRANSCRIPT = "EXTRA_READ_CLIPBOARD_TRANSCRIPT"
        const val EXTRA_VIDEO_NAME = "EXTRA_VIDEO_NAME"
        const val EXTRA_VIDEO_FOLDER = "EXTRA_VIDEO_FOLDER"

        @Volatile
        var isServiceConnected: Boolean = false
            private set

        @Volatile
        var targetGroupName: String? = null
            private set

        @Volatile
        var isAutomatedSendingActive: Boolean = false
            private set

        @Volatile
        var isCurrentJobTextOnly: Boolean = true
            private set

        @Volatile
        var currentStep: AutoShareStep = AutoShareStep.IDLE
            private set

        // --- Gemini 免 API 全自動生成與取回逐字稿狀態 ---
        @Volatile
        var isGeminiAutoActive: Boolean = false
            private set

        @Volatile
        var geminiStep: GeminiAutoStep = GeminiAutoStep.IDLE
            private set

        @Volatile
        var pendingClipboardTranscriptRead: Boolean = false

        @Volatile
        var lastScrapedGeminiTranscript: String? = null

        private var geminiPromptText: String = ""
        private var geminiCallerPackage: String? = null
        private var geminiCallerVideoName: String? = null
        private var geminiCallerVideoFolder: String? = null
        private var geminiJobStartTimeMs: Long = 0L
        private var geminiStepChangedTimeMs: Long = 0L
        private var hasFilledGeminiPrompt: Boolean = false
        private var hasClickedGeminiSend: Boolean = false
        private var hasClickedGeminiCopy: Boolean = false
        private var lastGeminiActionTimeMs: Long = 0L
        private var lastCandidateTextChangeMs: Long = 0L
        private var onGeminiResultCallback: ((String?) -> Unit)? = null

        private var jobStartTimeMs: Long = 0L
        private var stepChangedTimeMs: Long = 0L
        private var lastChooserClickTimeMs: Long = 0L
        private var hasInputSearchText: Boolean = false
        private var hasClickedSearchExpand: Boolean = false
        private var hasClickedTargetGroup: Boolean = false
        private var lastActionClickTimeMs: Long = 0L
        private var lastProcessingActiveTimeMs: Long = 0L
        private var stableQuietStartTimeMs: Long = 0L
        private var onJobCompletedCallback: (() -> Unit)? = null

        private val mainHandler = Handler(Looper.getMainLooper())
        private var activeServiceInstance: AudioRipAccessibilityService? = null

        @Volatile
        var isContactPickerMode: Boolean = false
            private set

        private var onContactPickedCallback: ((String) -> Unit)? = null
        private var contactPickerStartTimeMs: Long = 0L

        fun startContactPickerMode(onPicked: (String) -> Unit) {
            cancelAutoShareJob()
            cancelGeminiAutoJob()
            isContactPickerMode = true
            onContactPickedCallback = onPicked
            contactPickerStartTimeMs = System.currentTimeMillis()
            Log.i(TAG, "ContactPickerMode started, waiting for user click in LINE")
            activeServiceInstance?.scheduleContactPickerTicker()
        }

        fun cancelContactPickerMode() {
            isContactPickerMode = false
            onContactPickedCallback = null
            activeServiceInstance?.stopContactPickerTicker()
        }

        /**
         * 啟動 Gemini App 免 API 全自動生成逐字稿並自動點擊複製取回任務
         */
        fun startGeminiAutoTranscribeJob(
            promptText: String,
            callerPackage: String? = null,
            callerVideoName: String? = null,
            callerVideoFolder: String? = null,
            onResult: ((String?) -> Unit)? = null
        ) {
            cancelAutoShareJob()
            cancelContactPickerMode()

            val now = System.currentTimeMillis()
            geminiPromptText = promptText
            geminiCallerPackage = callerPackage
            geminiCallerVideoName = callerVideoName
            geminiCallerVideoFolder = callerVideoFolder
            geminiJobStartTimeMs = now
            geminiStepChangedTimeMs = now
            hasFilledGeminiPrompt = false
            hasClickedGeminiSend = false
            hasClickedGeminiCopy = false
            lastGeminiActionTimeMs = 0L
            lastCandidateTextChangeMs = now
            lastScrapedGeminiTranscript = null
            pendingClipboardTranscriptRead = false
            onGeminiResultCallback = onResult

            geminiStep = GeminiAutoStep.FILL_PROMPT_AND_SEND
            isGeminiAutoActive = true

            Log.i(TAG, "啟動 Gemini 免 API 全自動逐字稿任務 (caller=$callerPackage, video=$callerVideoName)")
            activeServiceInstance?.scheduleTicker()
        }

        fun cancelGeminiAutoJob() {
            isGeminiAutoActive = false
            geminiStep = GeminiAutoStep.IDLE
            onGeminiResultCallback = null
            hasFilledGeminiPrompt = false
            hasClickedGeminiSend = false
            hasClickedGeminiCopy = false
        }

        /**
         * 啟動 LINE 聯絡人挑選模式並開啟 LINE 選擇對象視窗
         */
        fun launchLineContactPicker(context: Context, onPicked: (String) -> Unit) {
            startContactPickerMode(onPicked)
            try {
                val intent = LineDirectShareManager.createDirectLineTextShareIntent(
                    context,
                    "[AudioRip 聯絡人配對]"
                )
                context.startActivity(intent)
            } catch (e: Exception) {
                cancelContactPickerMode()
                Toast.makeText(context, "無法開啟 LINE 挑選聯絡人: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * 檢查系統無障礙設定中是否已開啟本服務
         */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            if (isServiceConnected) return true
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabledServices.contains(context.packageName) &&
                    enabledServices.contains(AudioRipAccessibilityService::class.java.simpleName)
            } catch (_: Exception) {
                false
            }
        }

        /**
         * 啟動一次自動化 LINE 傳送任務
         */
        fun startAutoShareJob(
            groupName: String,
            isTextOnly: Boolean = true,
            onCompleted: (() -> Unit)? = null
        ) {
            val trimmed = groupName.trim()
            if (trimmed.isEmpty()) return

            targetGroupName = trimmed
            isCurrentJobTextOnly = isTextOnly
            val now = System.currentTimeMillis()
            jobStartTimeMs = now
            stepChangedTimeMs = now
            lastChooserClickTimeMs = 0L
            hasInputSearchText = false
            hasClickedSearchExpand = false
            hasClickedTargetGroup = false
            lastActionClickTimeMs = 0L
            lastProcessingActiveTimeMs = 0L
            stableQuietStartTimeMs = 0L
            onJobCompletedCallback = onCompleted
            currentStep = AutoShareStep.FIND_TARGET_GROUP
            isAutomatedSendingActive = true

            Log.i(TAG, "啟動 LINE 鎖定對象自動傳送: 目標=[$trimmed], 純文字=$isTextOnly")
            activeServiceInstance?.scheduleTicker()
        }

        /**
         * 取消自動傳送任務
         */
        fun cancelAutoShareJob() {
            isAutomatedSendingActive = false
            currentStep = AutoShareStep.IDLE
            onJobCompletedCallback = null
            hasClickedTargetGroup = false
            stableQuietStartTimeMs = 0L
            lastProcessingActiveTimeMs = 0L
            lastActionClickTimeMs = 0L
        }
    }

    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (!isAutomatedSendingActive && !isGeminiAutoActive) return
            if (isAutomatedSendingActive) {
                processCurrentWindow()
            }
            if (isGeminiAutoActive) {
                processGeminiWindow()
            }
            if (isAutomatedSendingActive || isGeminiAutoActive) {
                mainHandler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }
    }

    private val contactPickerTickerRunnable = object : Runnable {
        override fun run() {
            if (!isContactPickerMode) return
            if (System.currentTimeMillis() - contactPickerStartTimeMs > 60_000L) {
                Log.w(TAG, "ContactPickerMode 超時取消")
                cancelContactPickerMode()
                return
            }
            mainHandler.postDelayed(this, 300L)
        }
    }

    private fun scheduleTicker() {
        mainHandler.removeCallbacks(tickerRunnable)
        mainHandler.post(tickerRunnable)
    }

    private fun stopTicker() {
        mainHandler.removeCallbacks(tickerRunnable)
    }

    private fun scheduleContactPickerTicker() {
        mainHandler.removeCallbacks(contactPickerTickerRunnable)
        mainHandler.post(contactPickerTickerRunnable)
    }

    private fun stopContactPickerTicker() {
        mainHandler.removeCallbacks(contactPickerTickerRunnable)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceConnected = true
        activeServiceInstance = this
        Log.i(TAG, "AudioRip 無障礙自動傳送服務已連接")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceConnected = false
        activeServiceInstance = null
        stopTicker()
        stopContactPickerTicker()
        Log.i(TAG, "AudioRip 無障礙自動傳送服務已銷毀")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // 聯絡人吸取模式
        if (isContactPickerMode && pkg == LINE_PKG) {
            handleContactPickerEvent(event)
            return
        }

        // LINE 自動傳送狀態機
        if (isAutomatedSendingActive) {
            processCurrentWindow()
        }

        // Gemini 免 API 全自動生成並複製取回狀態機
        if (isGeminiAutoActive) {
            processGeminiWindow()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "無障礙服務被打斷")
        cancelAutoShareJob()
        cancelGeminiAutoJob()
        cancelContactPickerMode()
    }

    private fun handleContactPickerEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SELECTED) {
            return
        }

        val source = event.source ?: return
        try {
            val candidateTexts = mutableListOf<String>()
            collectNodeTexts(source, candidateTexts)

            var bestName: String? = null
            for (cand in candidateTexts) {
                if (ContactPickerValidator.isValidContactName(cand)) {
                    bestName = ContactPickerValidator.stripMemberCountSuffix(cand)
                    break
                }
            }

            if (bestName != null) {
                Log.i(TAG, "成功識別挑選之聯絡人/群組: $bestName")
                val cb = onContactPickedCallback
                cancelContactPickerMode()
                cb?.invoke(bestName)
                mainHandler.postDelayed({ returnToAudioRip() }, 200L)
            }
        } finally {
            try { source.recycle() } catch (_: Exception) {}
        }
    }

    private fun processCurrentWindow() {
        if (!isAutomatedSendingActive) return

        val now = System.currentTimeMillis()
        if (now - jobStartTimeMs > JOB_TIMEOUT_MS) {
            Log.w(TAG, "傳送任務超時，終止自動傳送")
            cancelAutoShareJob()
            return
        }

        val rootNode = rootInActiveWindow ?: return
        try {
            val pkg = rootNode.packageName?.toString() ?: ""

            // 處理系統 Chooser (若跳出分享選單，自動點擊 LINE)
            if (pkg != LINE_PKG && currentStep == AutoShareStep.FIND_TARGET_GROUP) {
                if (now - lastChooserClickTimeMs > 800L) {
                    val lineNode = findNodeByTextOrDesc(rootNode, "LINE")
                    if (lineNode != null) {
                        Log.i(TAG, "於系統分享器中找到 LINE，執行點擊")
                        clickNodeOrParent(lineNode)
                        lastChooserClickTimeMs = now
                        return
                    }
                }
            }

            if (pkg != LINE_PKG) return

            when (currentStep) {
                AutoShareStep.FIND_TARGET_GROUP -> handleStepFindTargetGroup(rootNode, now)
                AutoShareStep.CLICK_CONFIRM_SEND -> handleStepClickConfirmSend(rootNode, now)
                AutoShareStep.VERIFY_TRANSMISSION_AND_RETURN -> handleStepVerifyTransmission(rootNode, now)
                AutoShareStep.IDLE -> {}
            }
        } finally {
            try { rootNode.recycle() } catch (_: Exception) {}
        }
    }

    private fun handleStepFindTargetGroup(rootNode: AccessibilityNodeInfo, now: Long) {
        val target = targetGroupName ?: return

        // 1. 直接搜尋畫面上匹配的目標節點
        val matchingNode = findMatchingContactNode(rootNode, target)
        if (matchingNode != null) {
            Log.i(TAG, "畫面上精準命中目標對象: $target，執行點擊選取")
            clickNodeOrParent(matchingNode)
            hasClickedTargetGroup = true
            currentStep = AutoShareStep.CLICK_CONFIRM_SEND
            stepChangedTimeMs = now
            lastActionClickTimeMs = now
            return
        }

        // 2. 若尚未命中且尚未輸入搜尋文字，嘗試使用搜尋欄快速過濾
        if (!hasInputSearchText && now - stepChangedTimeMs > 400L) {
            val searchInput = findSearchInputField(rootNode)
            if (searchInput != null) {
                Log.i(TAG, "找到搜尋欄，自動輸入目標名稱: $target")
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, target)
                }
                searchInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                hasInputSearchText = true
                stepChangedTimeMs = now
                lastActionClickTimeMs = now
                return
            } else if (!hasClickedSearchExpand) {
                val searchIcon = findSearchExpandButton(rootNode)
                if (searchIcon != null) {
                    Log.i(TAG, "點擊搜尋展開按鈕")
                    clickNodeOrParent(searchIcon)
                    hasClickedSearchExpand = true
                    lastActionClickTimeMs = now
                    return
                }
            }
        }

        // 3. 搜尋欄已輸入過濾文字後的結果匹配
        if (hasInputSearchText && now - stepChangedTimeMs > 300L) {
            val searchResultNode = findFuzzyMatchingContactNode(rootNode, target)
            if (searchResultNode != null) {
                Log.i(TAG, "搜尋結果命中目標對象: $target，執行點擊選取")
                clickNodeOrParent(searchResultNode)
                hasClickedTargetGroup = true
                currentStep = AutoShareStep.CLICK_CONFIRM_SEND
                stepChangedTimeMs = now
                lastActionClickTimeMs = now
            }
        }
    }

    private fun handleStepClickConfirmSend(rootNode: AccessibilityNodeInfo, now: Long) {
        if (now - lastActionClickTimeMs < 250L) return

        val sendButton = findSingleSendButton(rootNode)
        if (sendButton != null) {
            Log.i(TAG, "找到單一目標確認傳送按鈕，執行點擊送出！")
            clickNodeOrParent(sendButton)
            currentStep = AutoShareStep.VERIFY_TRANSMISSION_AND_RETURN
            stepChangedTimeMs = now
            lastActionClickTimeMs = now
            stableQuietStartTimeMs = now
        }
    }

    private fun handleStepVerifyTransmission(rootNode: AccessibilityNodeInfo, now: Long) {
        // 純文字逐字稿快訊
        if (isCurrentJobTextOnly) {
            if (now - stableQuietStartTimeMs >= RETURN_DELAY_TEXT_MS) {
                Log.i(TAG, "純文字逐字稿傳送完成，自動平穩返回 AudioRip")
                finishAndReturn()
            }
            return
        }

        // 包含音訊檔案時：檢測是否仍在「上傳中/處理中/壓縮中」
        val isProcessing = hasProcessingIndicator(rootNode)
        if (isProcessing) {
            lastProcessingActiveTimeMs = now
            stableQuietStartTimeMs = 0L
            return
        }

        if (stableQuietStartTimeMs == 0L) {
            stableQuietStartTimeMs = now
            return
        }

        if (now - stableQuietStartTimeMs >= 1500L) {
            Log.i(TAG, "音訊檔案與逐字稿已平穩上傳發送完成，自動返回 AudioRip")
            finishAndReturn()
        }
    }

    private fun finishAndReturn() {
        val cb = onJobCompletedCallback
        cancelAutoShareJob()
        cb?.invoke()
        returnToAudioRip()
    }

    private fun returnToAudioRip() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (_: Exception) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Gemini App (com.google.android.apps.bard) 全自動生成與取回逐字稿狀態機
    // ══════════════════════════════════════════════════════════════════════════

    private fun processGeminiWindow() {
        if (!isGeminiAutoActive) return

        val now = System.currentTimeMillis()
        if (now - geminiJobStartTimeMs > GEMINI_TIMEOUT_MS) {
            Log.w(TAG, "Gemini 自動逐字稿任務超時，自動返回 App")
            finishGeminiJobAndReturn(lastScrapedGeminiTranscript)
            return
        }

        val rootNode = rootInActiveWindow ?: return
        try {
            val pkg = rootNode.packageName?.toString() ?: ""

            // 若跳出系統分享選單 (Chooser)，自動點擊 Gemini
            if (pkg != GEMINI_PKG && geminiStep == GeminiAutoStep.FILL_PROMPT_AND_SEND) {
                if (now - lastChooserClickTimeMs > 800L) {
                    val geminiNode = findNodeByTextOrDesc(rootNode, "Gemini")
                    if (geminiNode != null) {
                        Log.i(TAG, "於系統分享選單中找到 Gemini，自動點擊")
                        clickNodeOrParent(geminiNode)
                        lastChooserClickTimeMs = now
                        return
                    }
                }
            }

            if (pkg != GEMINI_PKG) return

            when (geminiStep) {
                GeminiAutoStep.FILL_PROMPT_AND_SEND -> handleGeminiFillAndSend(rootNode, now)
                GeminiAutoStep.WAIT_GENERATION_AND_COPY -> handleGeminiWaitAndCopy(rootNode, now)
                GeminiAutoStep.RETURN_WITH_RESULT -> {
                    if (now - geminiStepChangedTimeMs >= 450L) {
                        finishGeminiJobAndReturn(lastScrapedGeminiTranscript)
                    }
                }
                GeminiAutoStep.IDLE -> {}
            }
        } finally {
            try { rootNode.recycle() } catch (_: Exception) {}
        }
    }

    private fun handleGeminiFillAndSend(rootNode: AccessibilityNodeInfo, now: Long) {
        if (now - lastGeminiActionTimeMs < 350L) return

        // 1. 檢查並填入提示詞
        if (!hasFilledGeminiPrompt && geminiPromptText.isNotBlank()) {
            val inputField = findGeminiPromptInputField(rootNode)
            if (inputField != null) {
                val existingText = inputField.text?.toString()?.trim() ?: ""
                if (!existingText.contains("逐字稿")) {
                    Log.i(TAG, "找到 Gemini 輸入框，自動填入逐字稿提示詞")
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, geminiPromptText)
                    }
                    val setOk = inputField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (!setOk) {
                        inputField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        inputField.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    }
                }
                hasFilledGeminiPrompt = true
                lastGeminiActionTimeMs = now
                return
            }
        }

        // 2. 點擊「傳送 / Send」按鈕
        if (!hasClickedGeminiSend && (hasFilledGeminiPrompt || now - geminiStepChangedTimeMs > 1800L)) {
            val sendBtn = findGeminiSendButton(rootNode)
            if (sendBtn != null) {
                Log.i(TAG, "找到 Gemini 傳送按鈕，自動點擊送出音訊與指令")
                clickNodeOrParent(sendBtn)
                hasClickedGeminiSend = true
                geminiStep = GeminiAutoStep.WAIT_GENERATION_AND_COPY
                geminiStepChangedTimeMs = now
                lastGeminiActionTimeMs = now
                lastCandidateTextChangeMs = now
            }
        }
    }

    private fun handleGeminiWaitAndCopy(rootNode: AccessibilityNodeInfo, now: Long) {
        // 先等待至少 3.2 秒讓 Gemini 開始處理音訊並進入生成狀態
        if (now - geminiStepChangedTimeMs < 3200L) return

        val isStoppingPresent = hasGeminiStopGeneratingNode(rootNode)
        val allTexts = mutableListOf<String>()
        collectNodeTexts(rootNode, allTexts)
        val bestCandidate = ContactPickerValidator.extractBestGeminiTranscriptFromTexts(allTexts, geminiPromptText)
        if (!bestCandidate.isNullOrBlank()) {
            if (bestCandidate != lastScrapedGeminiTranscript) {
                lastScrapedGeminiTranscript = bestCandidate
                lastCandidateTextChangeMs = now
            }
        }

        if (isStoppingPresent) {
            // Gemini 仍在生成中，持續等待
            return
        }

        // 尋找回答下方的「複製 / Copy」按鈕
        val copyBtn = findGeminiCopyButton(rootNode)
        if (copyBtn != null && !hasClickedGeminiCopy && now - geminiStepChangedTimeMs >= 4000L) {
            // 確認文字已穩定至少 1.2 秒再按複製
            if (now - lastCandidateTextChangeMs >= 1200L) {
                Log.i(TAG, "偵測到 Gemini 生成完畢且出現【複製】按鈕，自動點擊複製完整逐字稿！")
                clickNodeOrParent(copyBtn)
                hasClickedGeminiCopy = true
                pendingClipboardTranscriptRead = true
                geminiStep = GeminiAutoStep.RETURN_WITH_RESULT
                geminiStepChangedTimeMs = now
                lastGeminiActionTimeMs = now
                return
            }
        }

        // 備援機制：若畫面已產出超過 40 字的逐字稿且連續 3.5 秒無變化、亦無停止按鈕，直接取回
        val currentScraped = lastScrapedGeminiTranscript
        if (!currentScraped.isNullOrBlank() && currentScraped.length >= 40 &&
            now - lastCandidateTextChangeMs >= 3500L && now - geminiStepChangedTimeMs >= 6500L
        ) {
            Log.i(TAG, "Gemini 回覆文字已穩定 (${currentScraped.length} 字)，自動取回逐字稿並返回")
            pendingClipboardTranscriptRead = true
            geminiStep = GeminiAutoStep.RETURN_WITH_RESULT
            geminiStepChangedTimeMs = now
        }
    }

    private fun finishGeminiJobAndReturn(scrapedText: String?) {
        val cb = onGeminiResultCallback
        val callerPkg = geminiCallerPackage
        val videoName = geminiCallerVideoName
        val videoFolder = geminiCallerVideoFolder
        cancelGeminiAutoJob()
        pendingClipboardTranscriptRead = true
        cb?.invoke(scrapedText)

        if (!callerPkg.isNullOrBlank()) {
            try {
                val returnIntent = Intent(ACTION_RECEIVE_TRANSCRIPT).apply {
                    setPackage(callerPkg)
                    putExtra(EXTRA_TRANSCRIPT_TEXT, scrapedText ?: "")
                    putExtra(EXTRA_READ_CLIPBOARD_TRANSCRIPT, true)
                    putExtra(EXTRA_VIDEO_NAME, videoName ?: "")
                    putExtra(EXTRA_VIDEO_FOLDER, videoFolder ?: "")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(returnIntent)
                Log.i(TAG, "已自動將逐字稿結果導回呼叫端 App: $callerPkg")
                return
            } catch (e: Exception) {
                Log.w(TAG, "直接導回呼叫端 $callerPkg 失敗，改由 AudioRip 中轉: ${e.message}")
            }
        }
        returnToAudioRip()
    }

    private fun findGeminiPromptInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val cls = node.className?.toString() ?: ""
            if (node.isEditable || cls.contains("EditText", ignoreCase = true)) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findGeminiSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val viewId = node.viewIdResourceName
            if (ContactPickerValidator.isGeminiSendButton(text, desc, viewId) && node.isEnabled) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun hasGeminiStopGeneratingNode(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val viewId = node.viewIdResourceName
            if (ContactPickerValidator.isGeminiStopGeneratingButton(text, desc, viewId)) {
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun findGeminiCopyButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var lastMatch: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val viewId = node.viewIdResourceName
            if (ContactPickerValidator.isGeminiCopyButton(text, desc, viewId)) {
                lastMatch = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return lastMatch
    }

    // --- 輔助比對節點方法 ---

    private fun findMatchingContactNode(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()

            if (ContactPickerValidator.isTargetGroupExactOrNormalizedMatch(text, target) ||
                ContactPickerValidator.isTargetGroupExactOrNormalizedMatch(desc, target)) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findFuzzyMatchingContactNode(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()

            if (ContactPickerValidator.isTargetGroupSafeFuzzyMatch(text, target) ||
                ContactPickerValidator.isTargetGroupSafeFuzzyMatch(desc, target)) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findSingleSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()

            if (ContactPickerValidator.isSingleTargetPickerSendButton(text, desc)) {
                return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findSearchInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) return node
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            if (text.contains("搜尋") || desc.contains("搜尋") ||
                text.contains("Search", ignoreCase = true) || desc.contains("Search", ignoreCase = true)) {
                if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findSearchExpandButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            if ((text == "搜尋" || desc == "搜尋" || text.equals("search", true) || desc.equals("search", true)) &&
                node.isClickable) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findNodeByTextOrDesc(root: AccessibilityNodeInfo, keyword: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            if (text.equals(keyword, ignoreCase = true) || desc.equals(keyword, ignoreCase = true)) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun hasProcessingIndicator(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val cls = node.className?.toString() ?: ""

            if (cls.contains("ProgressBar", ignoreCase = true)) return true
            if (text.contains("處理中") || desc.contains("處理中") ||
                text.contains("上傳中") || desc.contains("上傳中") ||
                text.contains("壓縮中") || desc.contains("壓縮中") ||
                text.contains("正在準備") || desc.contains("正在準備")) {
                return true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null != null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                val ok = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) return true
            }
            current = current.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun collectNodeTexts(node: AccessibilityNodeInfo, result: MutableList<String>) {
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty() && !result.contains(t)) result.add(t)
        val d = node.contentDescription?.toString()?.trim()
        if (!d.isNullOrEmpty() && !result.contains(d)) result.add(d)

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodeTexts(it, result) }
        }
    }
}
