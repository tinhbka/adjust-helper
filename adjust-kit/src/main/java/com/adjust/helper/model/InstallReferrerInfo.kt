package com.adjust.helper.model

import java.net.URLDecoder

/**
 * Kết quả đọc từ Google Play Install Referrer API.
 *
 * [referrer] là chuỗi raw dạng query string, vd:
 *  - organic:   `utm_source=google-play&utm_medium=organic`
 *  - Google Ads: `gclid=xxx&utm_source=google&utm_medium=cpc`
 *  - Adjust link: `adjust_reftag=xxx&utm_source=adjust&utm_campaign=...`
 */
data class InstallReferrerInfo(
    val referrer: String?,
    val referrerClickTimestampSeconds: Long = 0,
    val installBeginTimestampSeconds: Long = 0,
    val googlePlayInstant: Boolean = false,
    /** null = đọc OK; khác null = lý do không đọc được (NOT_SUPPORTED, SERVICE_UNAVAILABLE...). */
    val errorMessage: String? = null,
) {
    val params: Map<String, String> by lazy { parseReferrer(referrer) }

    val utmSource: String? get() = params["utm_source"]?.takeIf { it.isMeaningful() }
    val utmMedium: String? get() = params["utm_medium"]?.takeIf { it.isMeaningful() }
    val utmCampaign: String? get() = params["utm_campaign"]?.takeIf { it.isMeaningful() }

    /** Có tham số click-id của mạng quảng cáo / tracker Adjust không. */
    val paidClickKey: String? get() = PAID_KEYS.firstOrNull { params[it].isMeaningful() }

    /**
     * Referrer chứng tỏ install KHÔNG organic (đến từ 1 link/quảng cáo).
     *
     * Quy tắc:
     *  - referrer rỗng / không đọc được          -> false (không kết luận được)
     *  - `utm_medium=organic`                     -> false
     *  - có click-id (gclid, fbclid, adjust_*...) -> true
     *  - `utm_source` khác `google-play`          -> true
     *  - `utm_medium` khác organic (cpc, paid...) -> true
     *  - còn lại (chỉ google-play, "(not set)")   -> false
     */
    val isNonOrganic: Boolean
        get() {
            if (referrer.isNullOrBlank() || params.isEmpty()) return false
            if (utmMedium.equals("organic", ignoreCase = true)) return false
            if (utmSource?.contains("organic", ignoreCase = true) == true) return false
            if (paidClickKey != null) return true
            val source = utmSource
            if (source != null && !source.equals("google-play", ignoreCase = true)) return true
            if (utmMedium != null) return true
            return false
        }

    /**
     * Tên "network" dùng để báo lên callback / lưu cache, để nhất quán với
     * tên tracker từ Adjust. Vd: `referrer:google`, `referrer:gclid`.
     */
    val networkName: String
        get() {
            val label = utmSource ?: paidClickKey ?: utmMedium ?: "unknown"
            return "referrer:$label"
        }

    fun toMap(): Map<String, Any?> = mapOf(
        "referrer" to referrer,
        "utmSource" to utmSource,
        "utmMedium" to utmMedium,
        "utmCampaign" to utmCampaign,
        "paidClickKey" to paidClickKey,
        "isNonOrganic" to isNonOrganic,
        "networkName" to networkName,
        "referrerClickTimestampSeconds" to referrerClickTimestampSeconds,
        "installBeginTimestampSeconds" to installBeginTimestampSeconds,
        "googlePlayInstant" to googlePlayInstant,
        "errorMessage" to errorMessage,
    )

    companion object {
        /** Các key cho thấy install đến từ 1 click quảng cáo hoặc tracker link. */
        val PAID_KEYS = listOf(
            "gclid", "gbraid", "wbraid", "dclid",
            "fbclid", "ttclid", "msclkid",
            "adjust_reftag", "adjust_external_click_id", "adjust_tracker", "adjust_t",
            "adjust_campaign", "adjust_adgroup", "adjust_creative", "adjust_label",
        )

        private val NOT_SET = setOf("(not set)", "not set", "notset", "(not%20set)", "null")

        private fun String?.isMeaningful(): Boolean =
            !this.isNullOrBlank() && this.trim().lowercase() !in NOT_SET

        fun parseReferrer(referrer: String?): Map<String, String> {
            if (referrer.isNullOrBlank()) return emptyMap()
            // Một số máy trả về chuỗi đã encode 1 lần (utm_source%3Dgoogle%26...).
            val raw = if (!referrer.contains('=') && referrer.contains("%3D", ignoreCase = true)) {
                decode(referrer)
            } else referrer
            return raw.split('&')
                .mapNotNull { pair ->
                    if (pair.isBlank()) return@mapNotNull null
                    val idx = pair.indexOf('=')
                    val key = if (idx >= 0) pair.substring(0, idx) else pair
                    val value = if (idx >= 0) pair.substring(idx + 1) else ""
                    val k = decode(key).trim().lowercase()
                    if (k.isEmpty()) null else k to decode(value).trim()
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
