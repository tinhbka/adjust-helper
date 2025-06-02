package com.vtn.adjust_kit

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
    val eventToken =
        this["eventToken"] as? String ?: throw IllegalArgumentException("eventToken is required")
    val event = AdjustEvent(eventToken)

    val clazz = AdjustEvent::class.java

    fun setField(fieldName: String, value: Any?) {
        try {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(event, value)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    setField("revenue", this["revenue"])
    setField("currency", this["currency"])
    setField("callbackParameters", this["callbackParameters"])
    setField("partnerParameters", this["partnerParameters"])
    setField("orderId", this["orderId"])
    setField("deduplicationId", this["deduplicationId"])
    setField("callbackId", this["callbackId"])
    setField("productId", this["productId"])
    setField("purchaseToken", this["purchaseToken"])

    return event
}