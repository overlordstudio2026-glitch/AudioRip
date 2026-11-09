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
}
