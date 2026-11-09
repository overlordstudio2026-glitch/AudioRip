package com.overlord.audiorip

import com.overlord.audiorip.core.share.LineContactHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * LineContactHelper 單元測試
 * 驗證 LINE 聯絡人快速切換與防呆機制
 */
class LineContactHelperTest {

    @Test
    fun testEmptyContacts_returnsNull() {
        assertNull(LineContactHelper.calculateNextContact(emptyList(), null, isUp = true))
        assertNull(LineContactHelper.calculateNextContact(emptyList(), "Any", isUp = false))
    }

    @Test
    fun testSingleContact_alwaysReturnsThatContact() {
        val list = listOf("現場助理")
        assertEquals("現場助理", LineContactHelper.calculateNextContact(list, null, isUp = true))
        assertEquals("現場助理", LineContactHelper.calculateNextContact(list, null, isUp = false))
        assertEquals("現場助理", LineContactHelper.calculateNextContact(list, "現場助理", isUp = true))
        assertEquals("現場助理", LineContactHelper.calculateNextContact(list, "現場助理", isUp = false))
    }

    @Test
    fun testMultipleContacts_stepDown_cyclesForward() {
        val list = listOf("聯絡人A", "聯絡人B", "聯絡人C")

        assertEquals("聯絡人A", LineContactHelper.calculateNextContact(list, null, isUp = false))
        assertEquals("聯絡人A", LineContactHelper.calculateNextContact(list, "陌生人", isUp = false))

        assertEquals("聯絡人B", LineContactHelper.calculateNextContact(list, "聯絡人A", isUp = false))
        assertEquals("聯絡人C", LineContactHelper.calculateNextContact(list, "聯絡人B", isUp = false))
        assertEquals("聯絡人A", LineContactHelper.calculateNextContact(list, "聯絡人C", isUp = false))
    }

    @Test
    fun testMultipleContacts_stepUp_cyclesBackward() {
        val list = listOf("聯絡人A", "聯絡人B", "聯絡人C")

        assertEquals("聯絡人C", LineContactHelper.calculateNextContact(list, null, isUp = true))
        assertEquals("聯絡人C", LineContactHelper.calculateNextContact(list, "聯絡人A", isUp = true))

        assertEquals("聯絡人B", LineContactHelper.calculateNextContact(list, "聯絡人C", isUp = true))
        assertEquals("聯絡人A", LineContactHelper.calculateNextContact(list, "聯絡人B", isUp = true))
    }

    @Test
    fun testCaseInsensitiveAndTrim() {
        val list = listOf("Donald.J", "Elon", "Sam")
        assertEquals("Elon", LineContactHelper.calculateNextContact(list, " donald.j ", isUp = false))
        assertEquals("Sam", LineContactHelper.calculateNextContact(list, " DONALD.J ", isUp = true))
    }
}
