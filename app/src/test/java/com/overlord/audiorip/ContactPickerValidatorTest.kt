package com.overlord.audiorip

import com.overlord.audiorip.core.share.accessibility.ContactPickerValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ContactPickerValidator 單元測試
 * 驗證 LINE 聯絡人/群組名稱驗證、剝除人數後綴、以及傳送按鈕比對
 */
class ContactPickerValidatorTest {

    @Test
    fun testStripMemberCountSuffix() {
        assertEquals("工作小組", ContactPickerValidator.stripMemberCountSuffix("工作小組 (15)"))
        assertEquals("測試群", ContactPickerValidator.stripMemberCountSuffix("測試群(3人)"))
        assertEquals("核心團隊", ContactPickerValidator.stripMemberCountSuffix("核心團隊 【5】"))
        assertEquals("現場幹部", ContactPickerValidator.stripMemberCountSuffix("現場幹部"))
    }

    @Test
    fun testIsTargetGroupExactOrNormalizedMatch() {
        assertTrue(ContactPickerValidator.isTargetGroupExactOrNormalizedMatch("工作小組 (15)", "工作小組"))
        assertTrue(ContactPickerValidator.isTargetGroupExactOrNormalizedMatch(" 工作小組 ", "工作小組"))
        assertTrue(ContactPickerValidator.isTargetGroupExactOrNormalizedMatch("工作 小組", "工作小組"))
        assertFalse(ContactPickerValidator.isTargetGroupExactOrNormalizedMatch("其他小組", "工作小組"))
    }

    @Test
    fun testIsSingleTargetPickerSendButton() {
        assertTrue(ContactPickerValidator.isSingleTargetPickerSendButton("傳送", null))
        assertTrue(ContactPickerValidator.isSingleTargetPickerSendButton("傳送 (1)", null))
        assertTrue(ContactPickerValidator.isSingleTargetPickerSendButton("確定", null))
        assertTrue(ContactPickerValidator.isSingleTargetPickerSendButton("Send", null))

        // 嚴格排除多重勾選
        assertFalse(ContactPickerValidator.isSingleTargetPickerSendButton("傳送 (2)", null))
        assertFalse(ContactPickerValidator.isSingleTargetPickerSendButton("傳送 (5)", null))

        // 嚴格排除「分享 / 轉傳」
        assertFalse(ContactPickerValidator.isSingleTargetPickerSendButton("分享", null))
        assertFalse(ContactPickerValidator.isSingleTargetPickerSendButton("轉傳", null))
    }

    @Test
    fun testIsValidContactName() {
        assertTrue(ContactPickerValidator.isValidContactName("現場助理"))
        assertTrue(ContactPickerValidator.isValidContactName("專案核心群"))

        // 排除系統詞
        assertFalse(ContactPickerValidator.isValidContactName("傳送"))
        assertFalse(ContactPickerValidator.isValidContactName("選擇傳送對象"))
        assertFalse(ContactPickerValidator.isValidContactName("已選取"))
        assertFalse(ContactPickerValidator.isValidContactName("核取方塊"))

        // 排除時間日期
        assertFalse(ContactPickerValidator.isValidContactName("下午 03:20"))
        assertFalse(ContactPickerValidator.isValidContactName("10月28日"))
    }

    @Test
    fun testGeminiNodeValidators() {
        assertTrue(ContactPickerValidator.isGeminiSendButton(null, "傳送", null))
        assertTrue(ContactPickerValidator.isGeminiSendButton(null, "Send message", null))
        assertFalse(ContactPickerValidator.isGeminiSendButton(null, "停止生成", null))

        assertTrue(ContactPickerValidator.isGeminiStopGeneratingButton(null, "停止生成", null))
        assertTrue(ContactPickerValidator.isGeminiStopGeneratingButton("停止", null, null))
        assertFalse(ContactPickerValidator.isGeminiStopGeneratingButton("傳送", null, null))

        assertTrue(ContactPickerValidator.isGeminiCopyButton(null, "複製", null))
        assertTrue(ContactPickerValidator.isGeminiCopyButton("複製文字", null, null))
        assertFalse(ContactPickerValidator.isGeminiCopyButton(null, "複製連結", null))

        val prompt = "請幫我將這段音訊轉成繁體中文逐字稿，並條列出重點摘要與發言重點：\n【活動時間】：2026-11-09\n【活動名稱】：現場活動"
        val sampleTranscript = "【重點摘要】\n1. 主席致詞感謝鄉親支持\n2. 說明未來地方建設規畫\n\n【完整逐字稿】\n各位鄉親大家好，今天非常高興來到現場..."
        val extracted = ContactPickerValidator.extractBestGeminiTranscriptFromTexts(
            listOf("Gemini", prompt, sampleTranscript, "複製"),
            prompt
        )
        assertEquals(sampleTranscript, extracted)
    }

    @Test
    fun testGeminiHostPackageAndPromptHelpers() {
        // 同時支援獨立 Gemini App 殼層與 Google 主程式承載之 Gemini 對話視窗
        assertTrue(ContactPickerValidator.isGeminiHostPackage("com.google.android.apps.bard"))
        assertTrue(ContactPickerValidator.isGeminiHostPackage("com.google.android.googlequicksearchbox"))
        assertFalse(ContactPickerValidator.isGeminiHostPackage("jp.naver.line.android"))

        // 驗證輸入框佔位提示詞辨識
        assertTrue(ContactPickerValidator.isGeminiPromptInputPlaceholder("向 Gemini 提問", null, null, null))
        assertTrue(ContactPickerValidator.isGeminiPromptInputPlaceholder(null, "Ask Gemini", null, null))
        assertTrue(ContactPickerValidator.isGeminiPromptInputPlaceholder(null, null, "輸入提示", null))
        assertFalse(ContactPickerValidator.isGeminiPromptInputPlaceholder("傳送", null, null, null))

        // 驗證自訂送出文字組合與已填入檢查
        val customPrompt = ContactPickerValidator.buildFinalTranscriptionPrompt(
            customInstruction = "請轉成逐字稿並加上時間軸",
            activityTime = "2026-11-09 22:30",
            activityName = "專案檢討會"
        )
        assertTrue(customPrompt.contains("請轉成逐字稿並加上時間軸"))
        assertTrue(customPrompt.contains("【活動時間】：2026-11-09 22:30"))
        assertTrue(customPrompt.contains("【活動名稱】：專案檢討會"))

        assertTrue(ContactPickerValidator.isGeminiPromptAlreadyFilled(customPrompt, customPrompt))
        assertFalse(ContactPickerValidator.isGeminiPromptAlreadyFilled("向 Gemini 提問", customPrompt))
    }

    @Test
    fun testGeminiBlacklistAndSendButtonEnhancements() {
        val accountText = "目前的登入身分是霸王色工作室\noverlordstudio2026@gmail.com\n帳戶和設定。"
        assertTrue(ContactPickerValidator.isBlacklistedGeminiSystemText(accountText))
        assertFalse(ContactPickerValidator.isValidTranscriptResult(accountText))

        val prompt = "請幫我將這段音訊轉成繁體中文逐字稿"
        val extracted = ContactPickerValidator.extractBestGeminiTranscriptFromTexts(
            listOf("Gemini", prompt, accountText, "複製"),
            prompt
        )
        // 帳號資訊必須被黑名單過濾掉，不得作為逐字稿取回
        org.junit.Assert.assertNull(extracted)

        // 送出按鈕多重關鍵字驗證
        assertTrue(ContactPickerValidator.isGeminiSendButton(null, "傳送提示", null))
        assertTrue(ContactPickerValidator.isGeminiSendButton(null, "發送提示", null))
        assertTrue(ContactPickerValidator.isGeminiSendButton(null, "Send prompt", null))
        assertTrue(ContactPickerValidator.isGeminiSendButton(null, "向上箭頭", null))
        assertTrue(ContactPickerValidator.isGeminiSendButton(null, null, "com.google.android.apps.bard:id/send_button"))

        // 驗證剪貼簿時間戳新鮮度檢查（防止誤讀任務啟動前使用者複製的無關文字）
        assertFalse(ContactPickerValidator.isClipboardTimestampFreshForJob(clipTimestampMs = 1000L, jobStartTimeMs = 2000L))
        assertTrue(ContactPickerValidator.isClipboardTimestampFreshForJob(clipTimestampMs = 2000L, jobStartTimeMs = 2000L))
        assertTrue(ContactPickerValidator.isClipboardTimestampFreshForJob(clipTimestampMs = 2500L, jobStartTimeMs = 2000L))
        assertFalse(ContactPickerValidator.isClipboardTimestampFreshForJob(clipTimestampMs = 2500L, jobStartTimeMs = 0L))

        // 驗證 Gemini 異常錯誤提示即時判定
        assertTrue(ContactPickerValidator.isGeminiErrorNotice("發生問題，請稍後再試", null))
        assertTrue(ContactPickerValidator.isGeminiErrorNotice(null, "網路連線中斷，無法處理"))
        assertTrue(ContactPickerValidator.isGeminiErrorNotice("Something went wrong, please try again", null))
        assertFalse(ContactPickerValidator.isGeminiErrorNotice("這是逐字稿內容，會議開始於十點", null))
    }

    @Test
    fun testGeminiUploadAwareComposerReadinessAndNonSendGuard() {
        // 1. 音檔仍在上傳中（有進度圈或 Send 按鈕停用）時，必須維持 UPLOADING_MEDIA，絕不可誤判已送出
        val uploadingState = ContactPickerValidator.evaluateGeminiComposerState(
            hasStoppingButton = false,
            hasPromptMovedToHistory = false,
            isPromptFilledInInput = true,
            isInputCleared = false,
            hasEnabledSendButton = false,
            hasDisabledSendButton = true,
            hasUploadProgressIndicator = true,
            hasExplicitUploadKeyword = false,
            sendAttemptCount = 0
        )
        org.junit.Assert.assertEquals(
            ContactPickerValidator.GeminiComposerReadiness.UPLOADING_MEDIA,
            uploadingState
        )

        // 2. 即使送出嘗試多次，只要音檔仍在傳（進度圈或停用鈕）仍為 UPLOADING_MEDIA，絕不轉進等待生成
        val stillUploadingEvenAfterAttempt = ContactPickerValidator.evaluateGeminiComposerState(
            hasStoppingButton = false,
            hasPromptMovedToHistory = false,
            isPromptFilledInInput = true,
            isInputCleared = false,
            hasEnabledSendButton = false,
            hasDisabledSendButton = true,
            hasUploadProgressIndicator = false,
            hasExplicitUploadKeyword = true,
            sendAttemptCount = 3
        )
        org.junit.Assert.assertEquals(
            ContactPickerValidator.GeminiComposerReadiness.UPLOADING_MEDIA,
            stillUploadingEvenAfterAttempt
        )

        // 3. 上傳完成且 Send 按鈕轉為啟用 -> READY_TO_SEND
        val readyState = ContactPickerValidator.evaluateGeminiComposerState(
            hasStoppingButton = false,
            hasPromptMovedToHistory = false,
            isPromptFilledInInput = true,
            isInputCleared = false,
            hasEnabledSendButton = true,
            hasDisabledSendButton = false,
            hasUploadProgressIndicator = false,
            hasExplicitUploadKeyword = false,
            sendAttemptCount = 0
        )
        org.junit.Assert.assertEquals(
            ContactPickerValidator.GeminiComposerReadiness.READY_TO_SEND,
            readyState
        )

        // 4. 點擊送出後輸入框已清空或出現停止按鈕 -> SUBMITTED_OR_GENERATING
        val submittedState = ContactPickerValidator.evaluateGeminiComposerState(
            hasStoppingButton = false,
            hasPromptMovedToHistory = true,
            isPromptFilledInInput = false,
            isInputCleared = true,
            hasEnabledSendButton = false,
            hasDisabledSendButton = false,
            hasUploadProgressIndicator = false,
            hasExplicitUploadKeyword = false,
            sendAttemptCount = 1
        )
        org.junit.Assert.assertEquals(
            ContactPickerValidator.GeminiComposerReadiness.SUBMITTED_OR_GENERATING,
            submittedState
        )

        // 5. 結構定位不可誤點附件 / Live / 麥克風 / 移除鈕
        assertTrue(ContactPickerValidator.isGeminiNonSendToolbarButton(null, "新增檔案", null))
        assertTrue(ContactPickerValidator.isGeminiNonSendToolbarButton(null, "開啟 Gemini Live", null))
        assertTrue(ContactPickerValidator.isGeminiNonSendToolbarButton(null, "移除附件", null))
        assertFalse(ContactPickerValidator.isGeminiNonSendToolbarButton(null, "傳送提示", null))
    }
}
