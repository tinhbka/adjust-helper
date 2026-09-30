package com.adjust.helper.model

import org.json.JSONObject

enum class Verdict { UNKNOWN, PAID, FREE }

enum class Certainty { NONE, PROVISIONAL, FINAL }

enum class Decider { NONE, OPTION1, OPTION2, TIMEOUT, LEGACY }

/**
 * Kết quả phân loại paid / free cùng toàn bộ dữ liệu dùng để quyết định.
 * [toJson] là thứ được gửi lên Flutter (và lưu cache).
 */
data class SourceResult(
    val verdict: Verdict = Verdict.UNKNOWN,
    val certainty: Certainty = Certainty.NONE,
    val decidedBy: Decider = Decider.NONE,
    /** Mã lý do, vd `option1_key`, `option2_network`, `both_free`, `timeout`. */
    val reason: String? = null,
    /** Chi tiết cho [reason], vd tên click-id hoặc tên network. */
    val detail: String? = null,
    val decidedAtMs: Long? = null,

    val option1: Option1Info? = null,
    val option1AtMs: Long? = null,

    val option2: Option2Data? = null,
    val option2AtMs: Long? = null,

    val appVersion: String? = null,

    // Các field dưới đây chỉ có ý nghĩa trong phiên hiện tại, không lưu cache.
    /** Lần mở app đầu tiên (chưa có cache) hay không. */
    val firstRun: Boolean = true,
    /** Đã hết thời gian chờ ở lần mở đầu tiên. */
    val timedOut: Boolean = false,
    /** Lý do phát ra kết quả này: cache / decide / upgrade / info / timeout. */
    val event: String? = null,
) {
    val isPaid: Boolean get() = verdict == Verdict.PAID
    val isFinal: Boolean get() = certainty == Certainty.FINAL

    /** Không cần chờ thêm: đã final, hết thời gian chờ, hoặc không phải lần mở đầu. */
    val done: Boolean get() = isFinal || timedOut || !firstRun

    val network: String? get() = option2?.network

    fun toJson(): JSONObject = JSONObject().apply {
        put("result", verdict.name.lowercase())
        put("isPaid", isPaid)
        put("isFinal", isFinal)
        put("done", done)
        put("certainty", certainty.name.lowercase())
        put("decidedBy", decidedBy.name.lowercase())
        put("reason", reason ?: JSONObject.NULL)
        put("detail", detail ?: JSONObject.NULL)
        put("network", network ?: JSONObject.NULL)
        put("decidedAtMs", decidedAtMs ?: JSONObject.NULL)
        put("appVersion", appVersion ?: JSONObject.NULL)
        put("firstRun", firstRun)
        put("event", event ?: JSONObject.NULL)
        put("option1", option1?.let { o1 ->
            JSONObject().apply {
                put("raw", o1.raw ?: JSONObject.NULL)
                put("clickTs", o1.clickTs)
                put("installTs", o1.installTs)
                put("instant", o1.instant)
                put("error", o1.error ?: JSONObject.NULL)
                put("source", o1.source ?: JSONObject.NULL)
                put("medium", o1.medium ?: JSONObject.NULL)
                put("campaign", o1.campaign ?: JSONObject.NULL)
                put("clickKey", o1.clickKey ?: JSONObject.NULL)
                put("atMs", option1AtMs ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL)
        put("option2", option2?.let { o2 ->
            JSONObject().apply {
                put("network", o2.network ?: JSONObject.NULL)
                put("tracker", o2.tracker ?: JSONObject.NULL)
                put("campaign", o2.campaign ?: JSONObject.NULL)
                put("adgroup", o2.adgroup ?: JSONObject.NULL)
                put("creative", o2.creative ?: JSONObject.NULL)
                put("atMs", option2AtMs ?: JSONObject.NULL)
            }
        } ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): SourceResult {
            val o1 = json.optJSONObject("option1")
            val o2 = json.optJSONObject("option2")
            return SourceResult(
                verdict = enumOr(json.optStr("result"), Verdict.UNKNOWN),
                certainty = enumOr(json.optStr("certainty"), Certainty.NONE),
                decidedBy = enumOr(json.optStr("decidedBy"), Decider.NONE),
                reason = json.optStr("reason"),
                detail = json.optStr("detail"),
                decidedAtMs = json.optLongOrNull("decidedAtMs"),
                option1 = o1?.let {
                    Option1Info(
                        raw = it.optStr("raw"),
                        clickTs = it.optLong("clickTs", 0),
                        installTs = it.optLong("installTs", 0),
                        instant = it.optBoolean("instant", false),
                        error = it.optStr("error"),
                    )
                },
                option1AtMs = o1?.optLongOrNull("atMs"),
                option2 = o2?.let {
                    Option2Data(
                        network = it.optStr("network"),
                        tracker = it.optStr("tracker"),
                        campaign = it.optStr("campaign"),
                        adgroup = it.optStr("adgroup"),
                        creative = it.optStr("creative"),
                    )
                },
                option2AtMs = o2?.optLongOrNull("atMs"),
                appVersion = json.optStr("appVersion"),
            )
        }

        private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: default

        private fun JSONObject.optStr(key: String): String? =
            if (isNull(key)) null else optString(key)

        private fun JSONObject.optLongOrNull(key: String): Long? =
            if (isNull(key)) null else optLong(key)
    }
}
