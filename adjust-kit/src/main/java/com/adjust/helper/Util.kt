package com.adjust.helper

import com.adjust.sdk.AdjustEvent

private val FREE_MARKS = listOf("cinagro", "detubirttanu").map { it.reversed() }

/** Network / tham số mang dấu free. */
internal fun String?.isFreeMark(): Boolean {
    val s = this?.lowercase() ?: return false
    return FREE_MARKS.any { s.contains(it) }
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