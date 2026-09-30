package com.adjust.helper.model

typealias PaidCallback = (result: SourceResult) -> Unit

data class AdOptions(
    val impressionToken: String? = null,
    val event80Token: String? = null,
    val paidCallback: PaidCallback? = null
)
