package com.vtn.adjust_kit

import android.content.Context
import com.google.android.gms.ads.identifier.AdvertisingIdClient

object AdvertisingIdUtil {
    suspend fun getAdvertisingId(context: Context): String? {
        return try {
            val info = AdvertisingIdClient.getAdvertisingIdInfo(context)
            if (!info.isLimitAdTrackingEnabled) {
                info.id
            } else {
                null // user opted out
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}