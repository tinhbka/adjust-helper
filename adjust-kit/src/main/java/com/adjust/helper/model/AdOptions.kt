package com.adjust.helper.model

typealias FullAdCallback = (
    isFullAds: Boolean,
    network: String?,
    fromCache: Boolean,
    fromLib: Boolean,
    fromApi: Boolean
) -> Unit

data class AdOptions(
    val impressionToken: String? = null,
    val event80Token: String? = null,
    val fullAdCallback: FullAdCallback? = null
)