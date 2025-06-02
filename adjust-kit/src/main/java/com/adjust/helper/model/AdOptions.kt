package com.adjust.helper.model

typealias FullAdCallback = (isFullAds: Boolean, network: String?, fromCache: Boolean) -> Unit

data class AdOptions(
    val impressionToken: String? = null,
    val fullAdCallback: FullAdCallback? = null
)