package com.vtn.adjust_kit.model

data class FullAdsOption(
    val maxFull: Boolean = true,
    val useNull: Boolean = true,
    val useEmpty: Boolean = true,
    val useUnAttributed: Boolean = true
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): FullAdsOption {
            return FullAdsOption(
                maxFull = map["maxFull"] as? Boolean ?: true,
                useNull = map["useNull"] as? Boolean ?: true,
                useEmpty = map["useEmpty"] as? Boolean ?: true,
                useUnAttributed = map["useUnAttributed"] as? Boolean ?: true
            )
        }
    }
}
