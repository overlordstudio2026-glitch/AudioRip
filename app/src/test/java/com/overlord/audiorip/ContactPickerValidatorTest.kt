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
}
