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
        var lastGeminiJobStartTimeMs: Long = 0L
            private set

        @Volatile
        var lastScrapedGeminiTranscript: String? = null

        private var geminiPromptText: String = ""
        private var geminiCallerPackage: String? = null
        private var geminiCallerVideoName: String? = null
        private var geminiCallerVideoFolder: String? = null
        private var geminiJobStartTimeMs: Long = 0L
        private var geminiStepChangedTimeMs: Long = 0L
        private var hasFilledGeminiPrompt: Boolean = false
        private var geminiPromptFillAttempts: Int = 0
        private var hasClickedGeminiSend: Boolean = false
        private var hasClickedGeminiCopy: Boolean = false
        private var hasSeenStopGenerating: Boolean = false
        private var sendAttemptCount: Int = 0
        private var sendAttemptTimeMs: Long = 0L
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
            lastGeminiJobStartTimeMs = now
            geminiStepChangedTimeMs = now
            hasFilledGeminiPrompt = false
            geminiPromptFillAttempts = 0
            hasClickedGeminiSend = false
            hasClickedGeminiCopy = false
            hasSeenStopGenerating = false
            sendAttemptCount = 0
            sendAttemptTimeMs = 0L
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
            geminiPromptFillAttempts = 0
            hasClickedGeminiSend = false
            hasClickedGeminiCopy = false
            hasSeenStopGenerating = false
            sendAttemptCount = 0
            sendAttemptTimeMs = 0L
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
    // Gemini App (com.google.android.apps.bard / googlequicksearchbox) 全自動生成與取回逐字稿狀態機
    // ══════════════════════════════════════════════════════════════════════════

    private fun processGeminiWindow() {
        if (!isGeminiAutoActive) return

        val now = System.currentTimeMillis()
        if (now - geminiJobStartTimeMs > GEMINI_TIMEOUT_MS) {
            Log.w(TAG, "Gemini 自動逐字稿任務超時，自動返回 App")
            finishGeminiJobAndReturn(lastScrapedGeminiTranscript)
            return
        }

        val candidateRoots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { candidateRoots.add(it) }
        try {
            windows?.forEach { win ->
                win.root?.let { r ->
                    if (candidateRoots.none { it == r }) {
                        candidateRoots.add(r)
                    }
                }
            }
        } catch (_: Exception) {}

        if (candidateRoots.isEmpty()) return

        try {
            val geminiRoot = candidateRoots.firstOrNull { root ->
                ContactPickerValidator.isGeminiHostPackage(root.packageName?.toString())
            }

            // 若尚未進入 Gemini 視窗，且跳出系統分享選單 (Chooser)，自動點擊 Gemini
            if (geminiRoot == null && geminiStep == GeminiAutoStep.FILL_PROMPT_AND_SEND) {
                if (now - lastChooserClickTimeMs > 800L) {
                    for (r in candidateRoots) {
                        val geminiNode = findNodeByTextOrDesc(r, "Gemini")
                        if (geminiNode != null) {
                            Log.i(TAG, "於系統分享選單中找到 Gemini，自動點擊")
                            clickNodeOrParent(geminiNode)
                            lastChooserClickTimeMs = now
                            return
                        }
                    }
                }
                return
            }

            val activeGeminiRoot = geminiRoot ?: return

            when (geminiStep) {
                GeminiAutoStep.FILL_PROMPT_AND_SEND -> handleGeminiFillAndSend(activeGeminiRoot, now)
                GeminiAutoStep.WAIT_GENERATION_AND_COPY -> handleGeminiWaitAndCopy(activeGeminiRoot, now)
                GeminiAutoStep.RETURN_WITH_RESULT -> {
                    if (now - geminiStepChangedTimeMs >= 450L) {
                        finishGeminiJobAndReturn(lastScrapedGeminiTranscript)
                    }
                }
                GeminiAutoStep.IDLE -> {}
            }
        } finally {
            for (r in candidateRoots) {
                try { r.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun handleGeminiFillAndSend(rootNode: AccessibilityNodeInfo, now: Long) {
        if (now - lastGeminiActionTimeMs < 350L) return

        val inputField = findGeminiPromptInputField(rootNode)
        val currentInputText = inputField?.text?.toString()?.trim() ?: ""

        // 1. 檢查並填入提示詞至 Gemini 底部輸入框
        if (!hasFilledGeminiPrompt && geminiPromptText.isNotBlank()) {
            if (inputField != null) {
                if (ContactPickerValidator.isGeminiPromptAlreadyFilled(currentInputText, geminiPromptText)) {
                    Log.i(TAG, "已確認 Gemini 輸入框內含目標提示詞，準備進行送出")
                    hasFilledGeminiPrompt = true
                } else {
                    Log.i(TAG, "找到 Gemini 輸入框 (嘗試 #${geminiPromptFillAttempts + 1})，自動聚焦並填入逐字稿提示詞")
                    clickNodeOrParent(inputField)
                    inputField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, geminiPromptText)
                    }
                    val setOk = inputField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (!setOk || geminiPromptFillAttempts >= 1) {
                        inputField.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    }

                    geminiPromptFillAttempts++
                    lastGeminiActionTimeMs = now
                    if (geminiPromptFillAttempts >= 2) {
                        hasFilledGeminiPrompt = true
                    }
                    return
                }
            } else {
                val placeholderNode = findGeminiPromptPlaceholderNode(rootNode)
                if (placeholderNode != null) {
                    Log.i(TAG, "找到 Gemini 提示詞佔位區，點擊展開輸入框")
                    clickNodeOrParent(placeholderNode)
                    placeholderNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    lastGeminiActionTimeMs = now
                    return
                }
            }
        }

        // 2. 執行送出與握手驗證 (Handshake Verification)
        val isPromptFilled = hasFilledGeminiPrompt || geminiPromptText.isBlank() || ContactPickerValidator.isGeminiPromptAlreadyFilled(currentInputText, geminiPromptText)
        if (isPromptFilled && !hasClickedGeminiSend) {
            val sendBtn = findGeminiSendButton(rootNode, inputField)

            // 若尚未觸發點擊，或距離上次點擊送出已超過 1.2 秒仍未看到發送成功跡象
            if (sendAttemptCount == 0 || (now - sendAttemptTimeMs >= 1200L && sendAttemptCount < 5)) {
                var clickedSend = false
                if (sendBtn != null && sendBtn.isEnabled) {
                    Log.i(TAG, "找到 Gemini 傳送按鈕 (嘗試 #${sendAttemptCount + 1})，執行點擊")
                    clickedSend = clickNodeOrParent(sendBtn)
                }

                // 備援：透過無障礙軟鍵盤發送 ACTION_IME_ENTER
                if (inputField != null && (!clickedSend || sendAttemptCount >= 1)) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        Log.i(TAG, "透過輸入框觸發 ACTION_IME_ENTER 送出指令")
                        inputField.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    }
                }

                sendAttemptCount++
                sendAttemptTimeMs = now
                lastGeminiActionTimeMs = now
            }

            // 握手驗證：檢查是否已真正送出（輸入框文字已清空、出現停止生成按鈕、或送出按鈕已轉為停用）
            val isStoppingPresent = hasGeminiStopGeneratingNode(rootNode)
            val isInputCleared = inputField != null && (currentInputText.isBlank() || ContactPickerValidator.isGeminiPromptInputPlaceholder(currentInputText, null))
            val isSendBtnGoneOrDisabled = sendBtn == null || !sendBtn.isEnabled

            if (isStoppingPresent || (sendAttemptCount >= 1 && isInputCleared) || (sendAttemptCount >= 2 && isSendBtnGoneOrDisabled && now - sendAttemptTimeMs >= 800L)) {
                Log.i(TAG, "確認 Gemini 已成功送出（isStopping=$isStoppingPresent, isInputCleared=$isInputCleared），切換至等待生成")
                hasClickedGeminiSend = true
                if (isStoppingPresent) hasSeenStopGenerating = true
                geminiStep = GeminiAutoStep.WAIT_GENERATION_AND_COPY
                geminiStepChangedTimeMs = now
                lastGeminiActionTimeMs = now
                lastCandidateTextChangeMs = now
            }
        }
    }

    private fun handleGeminiWaitAndCopy(rootNode: AccessibilityNodeInfo, now: Long) {
        val isStoppingPresent = hasGeminiStopGeneratingNode(rootNode)
        if (isStoppingPresent) {
            hasSeenStopGenerating = true
            // 只要仍在生成中，持續刷新候選文字與計時，確保不會提早擷取
            lastCandidateTextChangeMs = now
            return
        }

        // 先等待至少 3.0 秒讓 Gemini 啟動處理
        if (now - geminiStepChangedTimeMs < 3000L) return

        val allTexts = mutableListOf<String>()
        collectNodeTexts(rootNode, allTexts)

        // 檢查是否出現 Gemini 異常錯誤提示（如連線中斷、稍後再試）
        val hasErrorNotice = allTexts.any { ContactPickerValidator.isGeminiErrorNotice(it, null) }
        if (hasErrorNotice) {
            Log.w(TAG, "偵測到 Gemini 畫面出現異常錯誤提示，提早結束並返回")
            finishGeminiJobAndReturn(null)
            return
        }
        val bestCandidate = ContactPickerValidator.extractBestGeminiTranscriptFromTexts(allTexts, geminiPromptText)
        if (!bestCandidate.isNullOrBlank()) {
            if (bestCandidate != lastScrapedGeminiTranscript) {
                lastScrapedGeminiTranscript = bestCandidate
                lastCandidateTextChangeMs = now
            }
        }

        // 尋找回答下方的「複製 / Copy」按鈕
        val copyBtn = findGeminiCopyButton(rootNode)
        if (copyBtn != null && !hasClickedGeminiCopy && (now - geminiStepChangedTimeMs >= 3500L || hasSeenStopGenerating)) {
            if (now - lastCandidateTextChangeMs >= 1000L) {
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

        // 嚴格備援機制：
        // 1. 必須曾經看到停止按鈕 (hasSeenStopGenerating == true) 或等候已超過 12 秒
        // 2. currentScraped 必須通過 isValidTranscriptResult 嚴格驗證 (絕對禁止黑名單如帳號身分)
        // 3. 文字已穩定至少 3 秒
        val currentScraped = lastScrapedGeminiTranscript
        if (ContactPickerValidator.isValidTranscriptResult(currentScraped, geminiPromptText) &&
            (hasSeenStopGenerating || now - geminiStepChangedTimeMs >= 12000L) &&
            now - lastCandidateTextChangeMs >= 3000L
        ) {
            Log.i(TAG, "Gemini 回覆文字已穩定 (${currentScraped!!.length} 字) 且通過黑名單檢驗，自動取回逐字稿並返回")
            pendingClipboardTranscriptRead = true
            geminiStep = GeminiAutoStep.RETURN_WITH_RESULT
            geminiStepChangedTimeMs = now
        }
    }

    private fun finishGeminiJobAndReturn(scrapedText: String?) {
        val safeText = if (ContactPickerValidator.isValidTranscriptResult(scrapedText, geminiPromptText)) {
            scrapedText
        } else {
            null
        }
        val didClickCopy = hasClickedGeminiCopy
        val cb = onGeminiResultCallback
        val callerPkg = geminiCallerPackage
        val videoName = geminiCallerVideoName
        val videoFolder = geminiCallerVideoFolder
        cancelGeminiAutoJob()
        pendingClipboardTranscriptRead = didClickCopy || safeText != null
        cb?.invoke(safeText)

        if (!callerPkg.isNullOrBlank()) {
            try {
                val returnIntent = Intent(ACTION_RECEIVE_TRANSCRIPT).apply {
                    setPackage(callerPkg)
                    putExtra(EXTRA_TRANSCRIPT_TEXT, safeText ?: "")
                    putExtra(EXTRA_READ_CLIPBOARD_TRANSCRIPT, didClickCopy)
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

    private fun findGeminiPromptPlaceholderNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val hint = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                node.hintText?.toString()
            } else {
                null
            }
            val viewId = node.viewIdResourceName
            if (ContactPickerValidator.isGeminiPromptInputPlaceholder(text, desc, hint, viewId)) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findGeminiSendButton(root: AccessibilityNodeInfo, inputField: AccessibilityNodeInfo? = null): AccessibilityNodeInfo? {
        // 1. 常規遍歷：尋找符合 isGeminiSendButton 且啟用的節點
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

        // 2. 結構定位保底：輸入框同層右側或相鄰 Action Button
        if (inputField != null) {
            val parent = inputField.parent
            if (parent != null) {
                for (i in (parent.childCount - 1) downTo 0) {
                    val child = parent.getChild(i) ?: continue
                    if (child == inputField) continue
                    if (child.isClickable && child.isEnabled) {
                        val desc = child.contentDescription?.toString() ?: ""
                        if (!desc.contains("麥克風") && !desc.contains("voice") && !desc.contains("mic")) {
                            return child
                        }
                    }
                }
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
