package com.adjust.helper.model

data class IapOptions(
    val productRevenueTokens: Map<String, String> = emptyMap(),
    val totalRevenueToken: String? = null
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): IapOptions {
            val productRevenueTokens =
                (map["productRevenueTokens"] as? Map<String, String>) ?: emptyMap()
            val totalRevenueToken = map["totalRevenueToken"] as? String
            return IapOptions(productRevenueTokens, totalRevenueToken)
        }
    }
}