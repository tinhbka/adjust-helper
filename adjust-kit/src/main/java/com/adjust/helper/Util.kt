package com.adjust.helper

import com.adjust.sdk.AdjustEvent

fun String?.isFullAds(): Boolean {
    val option = AdjustBridge.fullAdsOption
    if (option.maxFull) {
        return !this.isOrganic()
    }
    return when {
        this == null -> option.useNull
        this.isEmpty() -> option.useEmpty
        this.isUnattributed() -> option.useUnAttributed
        else -> !this.isOrganic()
    }
}

fun String?.savableName(): String {
    return when {
        this == null -> "null"
        this.isEmpty() -> "empty"
        else -> this
    }
}

fun String?.toOriginal(): String? {
    return when (this) {
        "null" -> null
        "empty" -> ""
        else -> this
    }
}

fun String?.isUnattributed(): Boolean {
    return this?.lowercase()?.contains("unattributed") == true
}

fun String?.isOrganic(): Boolean {
    return this?.lowercase()?.contains("organic") == true
}

fun Map<String, Any?>.toAdjustEvent(): AdjustEvent {
    val eventToken = this["eventToken"] as? String
        ?: throw IllegalArgumentException("eventToken is required")

    val event = AdjustEvent(eventToken)

    // Xử lý Revenue
    val revenue = this["revenue"] as? Double
    val currency = this["currency"] as? String
    if (revenue != null && currency != null) {
        event.setRevenue(revenue, currency)
    }

    // Xử lý Parameters (ép kiểu sang Map<String, String>)
    (this["callbackParameters"] as? Map<*, *>)?.forEach { (k, v) ->
        event.addCallbackParameter(k.toString(), v.toString())
    }

    (this["partnerParameters"] as? Map<*, *>)?.forEach { (k, v) ->
        event.addPartnerParameter(k.toString(), v.toString())
    }

    // Các field đơn giản khác
    (this["orderId"] as? String)?.let { event.setOrderId(it) }
    (this["callbackId"] as? String)?.let { event.setCallbackId(it) }
    (this["productId"] as? String)?.let { event.setProductId(it) }
    (this["purchaseToken"] as? String)?.let { event.setPurchaseToken(it) }
    (this["deduplicationId"] as? String)?.let { event.setDeduplicationId(it) }

    return event
}