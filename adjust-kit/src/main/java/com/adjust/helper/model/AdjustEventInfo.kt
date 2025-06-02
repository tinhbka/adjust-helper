package com.adjust.helper.model

import org.json.JSONObject

data class AdjustEventInfo(
    val name: String? = null,
    val time: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): AdjustEventInfo {
            return AdjustEventInfo(
                name = json.optString("name", null),
                time = json.optString("time", null)
            )
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        name?.let { json.put("name", it) }
        time?.let { json.put("time", it) }
        return json
    }
}