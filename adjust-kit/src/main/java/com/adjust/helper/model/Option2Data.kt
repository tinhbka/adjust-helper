package com.adjust.helper.model

/** Dữ liệu thô của option 2. [network] null / rỗng = chưa có kết quả. */
data class Option2Data(
    val network: String?,
    val tracker: String? = null,
    val campaign: String? = null,
    val adgroup: String? = null,
    val creative: String? = null,
)
