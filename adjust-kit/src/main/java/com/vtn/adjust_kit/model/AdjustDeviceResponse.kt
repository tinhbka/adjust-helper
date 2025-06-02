package com.vtn.adjust_kit.model

import org.json.JSONObject

data class AdjustDeviceResponse(
    val adid: String? = null,
    val advertisingId: String? = null,
    val tracker: String? = null,
    val trackerName: String? = null,
    val firstTracker: String? = null,
    val firstTrackerName: String? = null,
    val environment: String? = null,
    val clickTime: String? = null,
    val installTime: String? = null,
    val lastSessionTime: String? = null,
    val lastEventsInfo: Map<String, AdjustEventInfo>? = null,
    val lastSdkVersion: String? = null,
    val state: String? = null,
    val errorMessage: String? = null
) {
    companion object {
        fun fromJson(json: JSONObject): AdjustDeviceResponse {
            val eventsInfoMap = if (json.has("LastEventsInfo")) {
                val infoJson = json.optJSONObject("LastEventsInfo")
                infoJson?.let {
                    it.keys().asSequence().associateWith { key ->
                        AdjustEventInfo.fromJson(it.getJSONObject(key))
                    }
                }
            } else null

            return AdjustDeviceResponse(
                adid = json.optString("Adid", null),
                advertisingId = json.optString("AdvertisingId", null),
                tracker = json.optString("Tracker", null),
                trackerName = json.optString("TrackerName", null),
                firstTracker = json.optString("FirstTracker", null),
                firstTrackerName = json.optString("FirstTrackerName", null),
                environment = json.optString("Environment", null),
                clickTime = json.optString("ClickTime", null),
                installTime = json.optString("InstallTime", null),
                lastSessionTime = json.optString("LastSessionTime", null),
                lastEventsInfo = eventsInfoMap,
                lastSdkVersion = json.optString("LastSdkVersion", null),
                state = json.optString("State", null),
                errorMessage = json.optString("ErrorMessage", null)
            )
        }
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("Adid", adid)
        json.put("AdvertisingId", advertisingId)
        json.put("Tracker", tracker)
        json.put("TrackerName", trackerName)
        json.put("FirstTracker", firstTracker)
        json.put("FirstTrackerName", firstTrackerName)
        json.put("Environment", environment)
        json.put("ClickTime", clickTime)
        json.put("InstallTime", installTime)
        json.put("LastSessionTime", lastSessionTime)

        lastEventsInfo?.let {
            val eventJson = JSONObject()
            for ((key, value) in it) {
                eventJson.put(key, value.toJson())
            }
            json.put("LastEventsInfo", eventJson)
        }

        json.put("LastSdkVersion", lastSdkVersion)
        json.put("State", state)
        json.put("ErrorMessage", errorMessage)

        return json
    }
}