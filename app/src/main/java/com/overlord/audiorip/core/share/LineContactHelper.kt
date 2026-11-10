package com.overlord.audiorip.core.share

/**
 * LINE 常用聯絡人/群組切換輔助類
 * 負責上下循環計算目標聯絡人，純 Kotlin 實現便於完整單元測試
 */
object LineContactHelper {

    /**
     * 計算向前 (Up) 或向後 (Down) 切換之目標聯絡人
     *
     * @param contacts 常用聯絡人清單
     * @param current 當前鎖定之聯絡人名稱 (若無或不在清單中則為 null)
     * @param isUp true 為向前循環 (▲)，false 為向後循環 (▼)
     * @return 切換後之目標聯絡人名稱；若清單為空則回傳 null
     */
    fun calculateNextContact(
        contacts: List<String>,
        current: String?,
        isUp: Boolean
    ): String? {
        if (contacts.isEmpty()) return null
        if (contacts.size == 1) return contacts[0]

        val currentIndex = if (!current.isNullOrBlank()) {
            contacts.indexOfFirst { it.equals(current.trim(), ignoreCase = true) }
        } else {
            -1
        }

        return if (isUp) {
            val nextIndex = if (currentIndex <= 0) {
                contacts.size - 1
            } else {
                currentIndex - 1
            }
            contacts[nextIndex]
        } else {
            val nextIndex = if (currentIndex < 0 || currentIndex >= contacts.size - 1) {
                0
            } else {
                currentIndex + 1
            }
            contacts[nextIndex]
        }
    }
}
