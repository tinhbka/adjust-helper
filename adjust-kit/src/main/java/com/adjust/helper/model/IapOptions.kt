package com.adjust.helper.model

data class IapOptions(
    val productRevenueTokens: Map<String, String> = emptyMap(),
    val totalRevenueToken: String? = null,
    /// Event token bắn khi user bắt đầu FREE TRIAL.
    val freeTrialToken: String? = null,
    /// Event token bắn khi user mua bằng GIÁ THẬT / auto-renew.
    val subscriptionToken: String? = null,
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): IapOptions {
            val productRevenueTokens =
                (map["productRevenueTokens"] as? Map<String, String>) ?: emptyMap()
            val totalRevenueToken = map["totalRevenueToken"] as? String
            val freeTrialToken = map["freeTrialToken"] as? String
            val subscriptionToken = map["subscriptionToken"] as? String
            return IapOptions(
                productRevenueTokens,
                totalRevenueToken,
                freeTrialToken,
                subscriptionToken,
            )
        }
    }
}
