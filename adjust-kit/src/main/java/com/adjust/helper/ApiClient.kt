package com.adjust.helper

import android.util.Log
import com.adjust.helper.model.AdjustDeviceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class ApiClient(private val baseUrl: String = "https://api.adjust.com") {

    private val client = OkHttpClient()
    private val TAG = "ApiClient"

    suspend fun inspectDevice(
        appToken: String,
        advertisingId: String
    ): AdjustDeviceResponse {
        val apiToken = AdjustBridge.apiToken
        if (apiToken.isNullOrEmpty()) {
            return AdjustDeviceResponse(errorMessage = "API token is not set")
        }

        return try {
            val url = "$baseUrl/device_service/api/v2/inspect_device" +
                    "?app_token=$appToken&advertising_id=$advertisingId"

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", "Bearer $apiToken")
                .addHeader("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string()
                val json = JSONObject(body ?: "{}")
                AdjustDeviceResponse.fromJson(json)
            } else {
                AdjustDeviceResponse(
                    errorMessage = "HTTP ${response.code}: ${response.body?.string()}"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during inspectDevice", e)
            AdjustDeviceResponse(errorMessage = "Exception: ${e.message}")
        }
    }
}