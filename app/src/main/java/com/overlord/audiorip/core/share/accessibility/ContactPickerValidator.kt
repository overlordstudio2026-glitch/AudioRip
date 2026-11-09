package com.overlord.audiorip.core.share.accessibility

/**
 * LINE 聯絡人/群組自動挑選之候選詞驗證器
 * 精準識別並過濾系統詞、時間標記、核取方塊狀態詞、頭像描述與按鈕，
 * 確保在 LINE 點選時能 100% 精確萃取出真正的聯絡人或群組名稱。
 */
object ContactPickerValidator {

    val SEND_BUTTON_REGEX = Regex(
        "^(傳送|分享|確定|確認|選擇|完成|下一步|繼續|是|好|同意|Send|Share|OK|Next|Done|Confirm|Continue)" +
            "(\\s*[\\(\\[（【]?\\s*\\d+(\\s*[/／]\\s*\\d+)?\\s*[個部支項件]?\\s*[\\)\\]）】]?)?" +
            "(\\s*影片)?(\\s*全部)?(\\s*\\(\\d+\\))?$",
        RegexOption.IGNORE_CASE
    )

    /**
     * 嚴格單一目標傳送按鈕正規表示式（專供 LINE「選擇傳送對象」畫面點擊傳送使用）：
     * - 僅允許「傳送」、「傳送 (1)」、「傳送(1)」、「確定」、「確認」、「完成」、「Send」、「Send (1)」、「OK」、「Done」
     * - 嚴格禁止匹配聊天室圖片旁的「分享 / Share / 轉傳 / Forward」按鈕
     * - 嚴格禁止匹配「傳送 (2)」以上的多重勾選狀態
     */
    val PICKER_SINGLE_TARGET_SEND_REGEX = Regex(
        "^(傳送|確定|確認|完成|Send|OK|Done|Confirm)" +
            "(\\s*[\\(\\[（【]?\\s*1\\s*[\\)\\]）】]?)?$",
        RegexOption.IGNORE_CASE
    )

    /**
     * 偵測「選擇傳送對象」畫面是否誤勾選了 2 個（含）以上的傳送對象，例如「傳送 (2)」、「傳送(3)」
     */
    val MULTI_TARGET_SEND_REGEX = Regex(
        "^(傳送|確定|確認|完成|Send|OK|Done|Confirm)\\s*[\\(\\[（【]?\\s*([2-9]|\\d{2,})\\s*[\\)\\]）】]?$",
        RegexOption.IGNORE_CASE
    )

    private val MEMBER_COUNT_SUFFIX_REGEX = Regex("\\s*[\\(（\\[【]\\d+\\+?\\s*[人個位]?[\\)）\\]】]$")

    private val FORBIDDEN_SHARE_FORWARD_WORDS = setOf(
        "分享", "轉傳", "share", "forward", "儲存", "下載", "回覆", "收回"
    )

    /**
     * 剝除群組名稱尾端的人數括號（例如「核心工作群 (15)」->「核心工作群」）
     */
    fun stripMemberCountSuffix(name: String): String {
        return name.trim().replace(MEMBER_COUNT_SUFFIX_REGEX, "").trim()
    }

    /**
     * 標準化比對字串（去除空白與裝飾標點並轉小寫）
     */
    fun normalizeForMatch(s: String): String {
        return stripMemberCountSuffix(s)
            .replace(Regex("[\\s_·•【】()\\[\\]\\-_,，。:：]"), "")
            .lowercase()
    }

    /**
     * 判定在「選擇傳送對象」畫面的按鈕文字/描述是否為合法的「單一對象確認傳送」按鈕
     * 絕對排除「分享 / Share / 轉傳」以及「傳送 (2+)」多重勾選狀態
     */
    fun isSingleTargetPickerSendButton(text: String?, contentDesc: String?): Boolean {
        val txt = text?.trim() ?: ""
        val desc = contentDesc?.trim() ?: ""
        if (txt.isEmpty() && desc.isEmpty()) return false

        val lowerTxt = txt.lowercase()
        val lowerDesc = desc.lowercase()
        if (FORBIDDEN_SHARE_FORWARD_WORDS.any { lowerTxt == it || lowerDesc == it || lowerTxt.startsWith("$it ") || lowerDesc.startsWith("$it ") }) {
            return false
        }
        if (txt.contains("分享") || desc.contains("分享") ||
            txt.contains("轉傳") || desc.contains("轉傳") ||
            lowerTxt.contains("share") || lowerDesc.contains("share") ||
            lowerTxt.contains("forward") || lowerDesc.contains("forward") ||
            txt.contains("傳送對象") || desc.contains("傳送對象")
        ) {
            return false
        }

        if (isMultiTargetSendCount(txt, desc)) return false

        val txtOk = txt.isNotEmpty() && PICKER_SINGLE_TARGET_SEND_REGEX.matches(txt)
        val descOk = desc.isNotEmpty() && PICKER_SINGLE_TARGET_SEND_REGEX.matches(desc)
        return txtOk || descOk
    }

    fun isMultiTargetSendCount(text: String?, contentDesc: String?): Boolean {
        val txt = text?.trim() ?: ""
        val desc = contentDesc?.trim() ?: ""
        return (txt.isNotEmpty() && MULTI_TARGET_SEND_REGEX.matches(txt)) ||
            (desc.isNotEmpty() && MULTI_TARGET_SEND_REGEX.matches(desc))
    }

    /**
     * 嚴格精確比對目標群組/聯絡人名稱（支援自動忽略尾端群組人數如「(15)」）
     */
    fun isTargetGroupExactOrNormalizedMatch(candidate: String?, targetGroup: String): Boolean {
        if (candidate.isNullOrBlank() || targetGroup.isBlank()) return false
        val trimmedCand = candidate.trim()
        val trimmedTarget = targetGroup.trim()

        if (trimmedCand.contains("選擇傳送對象") || trimmedCand.contains("搜尋") || trimmedCand.contains("\n")) {
            return false
        }

        // 1. 原文或剝除尾端人數後完全一致
        val strippedCand = stripMemberCountSuffix(trimmedCand)
        val strippedTarget = stripMemberCountSuffix(trimmedTarget)
        if (trimmedCand.equals(trimmedTarget, ignoreCase = true) ||
            strippedCand.equals(strippedTarget, ignoreCase = true)
        ) {
            return true
        }

        // 2. 去除標點與空白後完全一致
        val normCand = normalizeForMatch(trimmedCand)
        val normTarget = normalizeForMatch(trimmedTarget)
        return normCand.isNotEmpty() && normCand == normTarget
    }

    /**
     * 搜尋欄已輸入過濾文字後的受限模糊比對
     */
    fun isTargetGroupSafeFuzzyMatch(candidate: String?, targetGroup: String): Boolean {
        if (candidate.isNullOrBlank() || targetGroup.isBlank()) return false
        val trimmedCand = candidate.trim()
        if (trimmedCand.contains("\n")) return false
        if (!isValidContactName(trimmedCand)) return false

        val strippedCand = stripMemberCountSuffix(trimmedCand)
        val strippedTarget = stripMemberCountSuffix(targetGroup.trim())
        if (strippedCand.length > strippedTarget.length + 15) return false

        val normCand = normalizeForMatch(trimmedCand)
        val normTarget = normalizeForMatch(strippedTarget)
        if (normTarget.length < 2) return normCand == normTarget

        return normCand.contains(normTarget) || strippedCand.contains(strippedTarget, ignoreCase = true)
    }

    val CONTACT_PICKER_IGNORE_WORDS = setOf(
        "傳送", "分享", "確定", "確認", "取消", "搜尋", "全選", "建立群組",
        "選擇傳送對象", "聊天", "好友", "群組", "官方帳號", "已選擇", "下一步",
        "line", "選擇", "完成", "編輯", "搜尋對象或訊息", "返回", "上一頁",
        "未選取", "已選取", "未勾選", "已勾選", "核取方塊", "核取方塊未選取", "核取方塊已選取",
        "核取方塊未勾選", "核取方塊已勾選", "核取", "方塊", "按鈕", "頭像", "大頭貼",
        "個人檔案", "相片", "照片", "聊天室", "已選擇1項", "選擇1項", "已選取1個",
        "send", "share", "ok", "confirm", "cancel", "search", "friends", "groups", "chat",
        "chats", "back", "next", "done", "select", "checkbox", "checked", "unchecked"
    )

    fun isValidContactName(cand: String?): Boolean {
        if (cand.isNullOrBlank()) return false
        val trimmed = cand.trim()
        val clean = trimmed.replace(Regex("[\\s_·•【】()\\[\\]\\-_,，。:：]"), "").lowercase()
        if (clean.isEmpty() || clean.length > 40) return false

        if (CONTACT_PICKER_IGNORE_WORDS.contains(clean)) return false
        if (SEND_BUTTON_REGEX.matches(trimmed)) return false

        if (clean.contains("未選取") || clean.contains("已選取") ||
            clean.contains("未勾選") || clean.contains("已勾選") ||
            clean.contains("核取方塊") || clean.contains("選擇傳送對象") ||
            clean.contains("搜尋對象") || clean.contains("checkbox") ||
            clean.contains("頭像") || clean.contains("大頭貼") ||
            clean.contains("相片") || clean.contains("照片")) {
            return false
        }

        val timeRegex = Regex("^(上午|下午|昨天|今天|前天)?\\s*\\d{1,2}[:：]\\d{2}$")
        if (timeRegex.matches(trimmed)) return false
        val dateRegex = Regex("^\\d{1,2}月\\d{1,2}日$")
        if (dateRegex.matches(trimmed)) return false
        val fullDateRegex = Regex("^\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}[日]?$")
        if (fullDateRegex.matches(trimmed)) return false

        if (clean.all { it.isDigit() || it == '+' }) return false

        return true
    }
}
