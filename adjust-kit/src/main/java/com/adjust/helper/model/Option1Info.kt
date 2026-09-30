package com.adjust.helper.model

import com.adjust.helper.isFreeMark
import java.net.URLDecoder

/**
 * Dữ liệu thô của option 1.
 *
 * [raw] là chuỗi dạng query string, vd `gclid=xxx&gbraid=yyy` hoặc
 * `utm_source=google-play&utm_medium=...`.
 */
data class Option1Info(
    val raw: String?,
    val clickTs: Long = 0,
    val installTs: Long = 0,
    val instant: Boolean = false,
    /** null = đọc OK; khác null = lý do không đọc được. */
    val error: String? = null,
) {
    val params: Map<String, String> by lazy { parse(raw) }

    val source: String? get() = params["utm_source"]?.takeIf { it.isMeaningful() }
    val medium: String? get() = params["utm_medium"]?.takeIf { it.isMeaningful() }
    val campaign: String? get() = params["utm_campaign"]?.takeIf { it.isMeaningful() }

    /** Key click-id đầu tiên tìm thấy trong [raw], null nếu không có. */
    val clickKey: String? get() = PAID_KEYS.firstOrNull { params.containsKey(it) }

    /**
     * Lý do option 1 cho thấy user là paid, null nếu là free candidate.
     *
     * Thứ tự:
     *  1. có click-id                          -> paid (option1_key)
     *  2. `utm_source` hoặc `utm_medium` mang dấu free -> free candidate
     *  3. `utm_source` khác `google-play`      -> paid (option1_source)
     *  4. có `utm_medium`                      -> paid (option1_medium)
     *  5. [clickTs] > 0 (chuỗi có thể bị cắt)  -> paid (option1_click)
     *  6. còn lại (rỗng, "not set", rác)       -> free candidate
     */
    val paidReason: Reason?
        get() {
            if (error != null) return null
            clickKey?.let { return Reason(REASON_KEY, it) }
            if (source.isFreeMark() || medium.isFreeMark()) return null
            source?.let {
                if (!it.equals("google-play", ignoreCase = true)) return Reason(REASON_SOURCE, it)
            }
            medium?.let { return Reason(REASON_MEDIUM, it) }
            if (clickTs > 0) return Reason(REASON_CLICK, clickTs.toString())
            return null
        }

    /** Mô tả ngắn cho trường hợp free candidate, để log. */
    val freeDetail: String
        get() = when {
            error != null -> "error:$error"
            raw.isNullOrBlank() -> "empty"
            params.isEmpty() -> "not_set"
            source != null || medium != null -> "store"
            else -> "unknown_params"
        }

    data class Reason(val code: String, val detail: String?)

    companion object {
        const val REASON_KEY = "option1_key"
        const val REASON_SOURCE = "option1_source"
        const val REASON_MEDIUM = "option1_medium"
        const val REASON_CLICK = "option1_click"

        /** Các key cho thấy install đến từ 1 click quảng cáo hoặc tracker link. */
        val PAID_KEYS = listOf(
            "gclid", "gbraid", "wbraid", "dclid", "gad_source", "gad_campaignid",
            "fbclid", "ttclid", "msclkid",
            "adjust_external_click_id", "adjust_reftag", "adjust_tracker", "adjust_t",
            "adjust_campaign", "adjust_adgroup", "adjust_creative", "adjust_label",
        )

        private val NOT_SET = setOf("(not set)", "not set", "notset", "(not%20set)", "null")

        private fun String?.isMeaningful(): Boolean =
            !this.isNullOrBlank() && this.trim().lowercase() !in NOT_SET

        fun parse(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            // Một số máy trả về chuỗi đã encode 1 lần (utm_source%3Dgoogle%26...).
            val text = if (!raw.contains('=') && raw.contains("%3D", ignoreCase = true)) {
                decode(raw)
            } else raw
            return text.split('&')
                .mapNotNull { pair ->
                    if (pair.isBlank()) return@mapNotNull null
                    val idx = pair.indexOf('=')
                    // Key không có '=' (vd "not set") thì bỏ qua.
                    if (idx <= 0) return@mapNotNull null
                    val k = decode(pair.substring(0, idx)).trim().lowercase()
                    val v = decode(pair.substring(idx + 1)).trim()
                    if (k.isEmpty()) null else k to v
                }
                .toMap()
        }

        private fun decode(s: String): String = try {
            URLDecoder.decode(s, "UTF-8")
        } catch (_: Exception) {
            s
        }
    }
}
