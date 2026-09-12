package com.adjust.helper.model

data class FullAdsOption(
    val maxFull: Boolean = true,
    val useNull: Boolean = true,
    val useEmpty: Boolean = true,
    val useUnAttributed: Boolean = true,
    /**
     * Dùng thêm Google Play Install Referrer làm nguồn xác định non-organic.
     * Referrer chỉ "nâng" lên full ads (khi chứng tỏ install từ link/quảng cáo),
     * không bao giờ hạ xuống organic.
     */
    val useReferrer: Boolean = true,
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): FullAdsOption {
            return FullAdsOption(
                maxFull = map["maxFull"] as? Boolean ?: true,
                useNull = map["useNull"] as? Boolean ?: true,
                useEmpty = map["useEmpty"] as? Boolean ?: true,
                useUnAttributed = map["useUnAttributed"] as? Boolean ?: true,
                useReferrer = map["useReferrer"] as? Boolean ?: true,
            )
        }
    }
}
