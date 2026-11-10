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

    // ══════════════════════════════════════════════════════════════════════════
    // Gemini App (com.google.android.apps.bard / googlequicksearchbox) 自動化節點辨識輔助方法
    // ══════════════════════════════════════════════════════════════════════════

    const val DEFAULT_TRANSCRIPTION_PROMPT_TEMPLATE =
        "請幫我將這段音訊完整轉成繁體中文逐字稿（保留原意、加上標點符號與分段），並在最後條列出重點摘要與行動待辦事項。"

    private val GEMINI_HOST_PACKAGES = setOf(
        "com.google.android.apps.bard",
        "com.google.android.googlequicksearchbox"
    )

    private val GEMINI_INPUT_HINT_KEYWORDS = listOf(
        "向 gemini 提問",
        "詢問 gemini",
        "ask gemini",
        "輸入提示",
        "新增提示",
        "新增說明",
        "enter a prompt",
        "add a prompt",
        "type a message",
        "輸入訊息"
    )

    private val GEMINI_SEND_KEYWORDS = setOf(
        "傳送", "發送", "送出", "提交", "send", "submit", "send message", "傳送訊息",
        "傳送提示", "send prompt", "發送提示", "傳送查詢", "submit query", "向上箭頭", "arrow up", "send button", "傳送按鈕"
    )

    private val GEMINI_STOP_KEYWORDS = setOf(
        "停止", "停止生成", "停止回應", "stop", "stop generating", "stop response"
    )

    private val GEMINI_COPY_KEYWORDS = setOf(
        "複製", "複製文字", "複製回應", "複製內容", "copy", "copy text", "copy response"
    )

    val GEMINI_IGNORED_SYSTEM_PATTERNS = listOf(
        "登入身分",
        "目前的登入身分",
        "帳戶和設定",
        "帳戶與設定",
        "帳號和設定",
        "帳號與設定",
        "google 帳戶",
        "google 帳號",
        "google account",
        "@gmail.com",
        "個人檔案",
        "切換帳戶",
        "新增帳戶",
        "管理您的 google",
        "隱私權政策",
        "服務條款",
        "terms of service",
        "privacy policy",
        "gemini 可能會提供不準確",
        "gemini may display inaccurate"
    )

    /**
     * 判定是否為 Google / Gemini 帳戶個人資料或系統聲明等應予排除的黑名單文字
     */
    fun isBlacklistedGeminiSystemText(text: String?): Boolean {
        if (text.isNullOrBlank()) return true
        val lower = text.trim().lowercase()
        return GEMINI_IGNORED_SYSTEM_PATTERNS.any { pattern ->
            lower.contains(pattern.lowercase())
        }
    }

    /**
     * 驗證取得的文字是否為合法的逐字稿回覆（非空、非系統黑名單、長度足夠且不為提示詞本身）
     */
    fun isValidTranscriptResult(text: String?, promptText: String = ""): Boolean {
        if (text.isNullOrBlank()) return false
        val clean = text.trim()
        if (clean.length < 25) return false
        if (isBlacklistedGeminiSystemText(clean)) return false
        if (promptText.isNotBlank()) {
            val cleanPrompt = promptText.trim()
            if (clean == cleanPrompt) return false
            val prefix = cleanPrompt.lineSequence().firstOrNull()?.trim()?.take(16) ?: cleanPrompt.take(24)
            if (prefix.length >= 6 && clean.startsWith(prefix)) return false
        }
        return true
    }

    /**
     * 檢查剪貼簿時間戳是否新於本次逐字稿任務啟動時間（防止誤讀任務開始前複製的無關文字）
     */
    fun isClipboardTimestampFreshForJob(clipTimestampMs: Long, jobStartTimeMs: Long): Boolean {
        if (jobStartTimeMs <= 0L) return false
        if (clipTimestampMs <= 0L) return true
        return clipTimestampMs >= jobStartTimeMs
    }

    /**
     * 判定當前視窗套件是否為 Gemini App 或承載 Gemini 對話視窗的 Google App
     */
    fun isGeminiHostPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        return GEMINI_HOST_PACKAGES.contains(pkg.trim())
    }

    /**
     * 組合使用者自訂的轉逐字稿送出文字與活動時間、活動名稱
     */
    fun buildFinalTranscriptionPrompt(
        customInstruction: String,
        activityTime: String,
        activityName: String
    ): String {
        val base = customInstruction.trim().ifBlank { DEFAULT_TRANSCRIPTION_PROMPT_TEMPLATE }
        val timeLine = if (activityTime.isNotBlank()) "\n【活動時間】：${activityTime.trim()}" else ""
        val nameLine = if (activityName.isNotBlank()) "\n【活動名稱】：${activityName.trim()}" else ""
        return "$base$timeLine$nameLine"
    }

    /**
     * 判定是否為 Gemini 底部提示詞輸入框或未展開前的佔位提示節點
     */
    fun isGeminiPromptInputPlaceholder(
        text: String?,
        contentDesc: String?,
        hintText: String? = null,
        viewId: String? = null
    ): Boolean {
        val txt = text?.trim()?.lowercase() ?: ""
        val desc = contentDesc?.trim()?.lowercase() ?: ""
        val hint = hintText?.trim()?.lowercase() ?: ""
        val id = viewId?.trim()?.lowercase() ?: ""

        if (GEMINI_INPUT_HINT_KEYWORDS.any { txt == it || desc == it || hint == it || txt.contains(it) || hint.contains(it) }) {
            return true
        }
        if (id.contains("input_box") || id.contains("prompt_input") || id.contains("chat_input") ||
            id.contains("edit_text") || id.contains("query_input")
        ) {
            return true
        }
        return false
    }

    /**
     * 檢查 Gemini 輸入框目前的文字是否已成功填入目標提示詞（排除預設空白提示字）
     */
    fun isGeminiPromptAlreadyFilled(currentNodeText: String?, targetPrompt: String): Boolean {
        if (currentNodeText.isNullOrBlank() || targetPrompt.isBlank()) return false
        val cleanCurrent = currentNodeText.trim()
        val cleanTarget = targetPrompt.trim()
        if (GEMINI_INPUT_HINT_KEYWORDS.any { cleanCurrent.equals(it, ignoreCase = true) }) {
            return false
        }
        if (cleanCurrent == cleanTarget) return true
        val prefix = cleanTarget.lineSequence().firstOrNull()?.trim()?.take(12) ?: cleanTarget.take(12)
        return prefix.length >= 4 && cleanCurrent.contains(prefix)
    }

    /**
     * 判定是否為 Gemini App 的「傳送 / Send」按鈕
     */
    fun isGeminiSendButton(text: String?, contentDesc: String?, viewId: String? = null): Boolean {
        val txt = text?.trim()?.lowercase() ?: ""
        val desc = contentDesc?.trim()?.lowercase() ?: ""
        val id = viewId?.trim()?.lowercase() ?: ""

        if (isGeminiStopGeneratingButton(text, contentDesc, viewId)) return false
        if (GEMINI_SEND_KEYWORDS.any { txt == it || desc == it }) return true
        if (desc.contains("傳送") || desc.contains("發送") || desc.contains("送出") ||
            desc.contains("send") || desc.contains("submit") || desc.contains("arrow") || desc.contains("箭頭")
        ) {
            if (!desc.contains("停止") && !desc.contains("stop") && !desc.contains("傳送對象")) return true
        }
        if ((id.contains("send") || id.contains("submit") || id.contains("action_button")) &&
            !id.contains("stop") && !id.contains("voice") && !id.contains("mic")
        ) return true
        return false
    }

    /**
     * 判定是否為 Gemini App 正在生成中的「停止 / Stop」按鈕或狀態
     */
    fun isGeminiStopGeneratingButton(text: String?, contentDesc: String?, viewId: String? = null): Boolean {
        val txt = text?.trim()?.lowercase() ?: ""
        val desc = contentDesc?.trim()?.lowercase() ?: ""
        val id = viewId?.trim()?.lowercase() ?: ""

        if (GEMINI_STOP_KEYWORDS.any { txt == it || desc == it }) return true
        if (desc.contains("停止生成") || desc.contains("停止回應") || desc.contains("stop generating")) return true
        if (id.contains("stop")) return true
        return false
    }

    /**
     * 判定是否為 Gemini App 回答底部的「複製 / Copy」按鈕
     */
    fun isGeminiCopyButton(text: String?, contentDesc: String?, viewId: String? = null): Boolean {
        val txt = text?.trim()?.lowercase() ?: ""
        val desc = contentDesc?.trim()?.lowercase() ?: ""
        val id = viewId?.trim()?.lowercase() ?: ""

        if (GEMINI_COPY_KEYWORDS.any { txt == it || desc == it }) return true
        if ((desc.contains("複製") || desc.contains("copy")) &&
            !desc.contains("連結") && !desc.contains("link")
        ) {
            return true
        }
        if (id.contains("copy") && !id.contains("link")) return true
        return false
    }

    val GEMINI_ERROR_KEYWORDS = listOf(
        "發生問題，請稍後再試",
        "發生錯誤，請稍後再試",
        "請稍後再試",
        "無法連線",
        "網路連線中斷",
        "something went wrong",
        "try again later",
        "unable to connect",
        "network error"
    )

    fun isGeminiErrorNotice(text: String?, contentDesc: String?): Boolean {
        val t = text?.trim()?.lowercase() ?: ""
        val d = contentDesc?.trim()?.lowercase() ?: ""
        return GEMINI_ERROR_KEYWORDS.any { kw ->
            t.contains(kw) || d.contains(kw)
        }
    }

    /**
     * 從 Gemini 畫面所有文字節點中，過濾掉使用者發送的提示詞、系統黑名單與系統短標籤，萃取出最完整的逐字稿回覆文字
     */
    fun extractBestGeminiTranscriptFromTexts(texts: List<String>, promptText: String): String? {
        val cleanPrompt = promptText.trim()
        val promptPrefix = cleanPrompt.lineSequence().firstOrNull()?.trim()?.take(16) ?: cleanPrompt.take(24)

        val candidates = texts.map { it.trim() }.filter { cand ->
            if (cand.length < 25) return@filter false
            if (isBlacklistedGeminiSystemText(cand)) return@filter false
            if (cleanPrompt.isNotEmpty() && (cand == cleanPrompt || (promptPrefix.length >= 6 && cand.startsWith(promptPrefix)))) {
                return@filter false
            }
            if (cand.startsWith("請幫我將這段音訊")) return@filter false
            if (GEMINI_INPUT_HINT_KEYWORDS.any { cand.lowercase().startsWith(it) }) return@filter false
            true
        }

        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { it.length }
    }

    enum class GeminiComposerReadiness {
        NEED_FILL_PROMPT,
        UPLOADING_MEDIA,
        READY_TO_SEND,
        SUBMITTED_OR_GENERATING,
        WAITING_UI_SETTLE
    }

    private val GEMINI_NON_SEND_TOOLBAR_KEYWORDS = listOf(
        "麥克風", "mic", "voice", "語音", "live", "新增", "附加", "附件", "檔案",
        "相機", "相簿", "圖片", "photo", "gallery", "camera", "attach", "add",
        "移除", "刪除", "清除", "取消", "關閉", "remove", "delete", "clear", "cancel", "close",
        "選單", "menu"
    )

    fun isGeminiNonSendToolbarButton(text: String?, contentDesc: String?, viewId: String?): Boolean {
        val combined = "${text ?: ""} ${contentDesc ?: ""} ${viewId ?: ""}".lowercase()
        if (isGeminiSendButton(text, contentDesc, viewId)) return false
        return GEMINI_NON_SEND_TOOLBAR_KEYWORDS.any { combined.contains(it) }
    }

    private val GEMINI_UPLOADING_KEYWORDS = listOf(
        "正在上傳", "上傳中", "處理檔案中", "uploading", "attaching", "processing file", "loading media"
    )

    fun isGeminiMediaUploadingKeyword(text: String?, contentDesc: String?): Boolean {
        val t = text?.trim()?.lowercase() ?: ""
        val d = contentDesc?.trim()?.lowercase() ?: ""
        return GEMINI_UPLOADING_KEYWORDS.any { kw -> t.contains(kw) || d.contains(kw) }
    }

    /**
     * 統一判定 Gemini 輸入區與附件上傳狀態：
     * - 嚴格區分「音檔/媒體尚在上傳（Send 按鈕停用或顯示進度圈）」與「已真正送出」
     * - 避免音檔仍在背景上傳時誤點擊或其他分支過早跳進等待生成階段
     */
    fun evaluateGeminiComposerState(
        hasStoppingButton: Boolean,
        hasPromptMovedToHistory: Boolean,
        isPromptFilledInInput: Boolean,
        isInputCleared: Boolean,
        hasEnabledSendButton: Boolean,
        hasDisabledSendButton: Boolean,
        hasUploadProgressIndicator: Boolean,
        hasExplicitUploadKeyword: Boolean,
        sendAttemptCount: Int
    ): GeminiComposerReadiness {
        if (hasStoppingButton || hasPromptMovedToHistory) {
            return GeminiComposerReadiness.SUBMITTED_OR_GENERATING
        }
        if (isInputCleared && sendAttemptCount >= 1 && !hasDisabledSendButton && !hasUploadProgressIndicator) {
            return GeminiComposerReadiness.SUBMITTED_OR_GENERATING
        }
        if (!isPromptFilledInInput) {
            return GeminiComposerReadiness.NEED_FILL_PROMPT
        }
        val isMediaStillUploading =
            (hasUploadProgressIndicator || hasDisabledSendButton || hasExplicitUploadKeyword) && !hasEnabledSendButton
        if (isMediaStillUploading) {
            return GeminiComposerReadiness.UPLOADING_MEDIA
        }
        if (hasEnabledSendButton && !hasUploadProgressIndicator) {
            return GeminiComposerReadiness.READY_TO_SEND
        }
        return GeminiComposerReadiness.WAITING_UI_SETTLE
    }
}
