package com.vtn.adjust_kit

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustAdRevenue
import com.adjust.sdk.AdjustAttribution
import com.adjust.sdk.AdjustConfig
import com.adjust.sdk.AdjustEvent
import com.vtn.adjust_kit.model.FullAdsOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

typealias FullAdCallback = (isFullAds: Boolean, network: String?, fromCache: Boolean) -> Unit

object AdjustBridge {

    private const val TAG = "AdjustUtil"

    private var isInitialized = false
    private var preferences: SharedPreferences? = null
    private var isFullAdFromApi = false
    var appToken: String? = null
    var environment: String? = null
    var apiToken: String? = null
    var impressionEventToken: String? = null
    var fullAdsOption = FullAdsOption()
    var fullAdCallback: FullAdCallback? = null

    fun isInitialized(): Boolean {
        if (!isInitialized) {
            Log.e(TAG, "AdjustUtil is not initialized")
        }
        return isInitialized
    }

    fun initialize(
        context: Context
    ) {
        if (isInitialized) return

        if (appToken.isNullOrEmpty()) {
            Log.e(TAG, "App token cannot be null")
            return
        }

        this.preferences = context.getSharedPreferences("adjust_prefs", Context.MODE_PRIVATE)
        val cachedNetwork = preferences?.getString("ad_network", null)
        val config = AdjustConfig(context, this.appToken!!, environment).apply {
            enableSendingInBackground()
        }

        if (cachedNetwork == null) {
            callAdjustApi(context)
            fullAdCallback?.let {
                config.setOnAttributionChangedListener { attribution ->
                    handleAttribution(attribution)
                }
            }
        }
        checkCachedAdNetwork()
        Adjust.initSdk(config)

        isInitialized = true
    }

    fun trackAdRevenue(value: Double, currencyCode: String) {
        if (!isInitialized()) return
        val adRevenue = AdjustAdRevenue("admob_sdk").apply {
            setRevenue(value, currencyCode)
        }
        Adjust.trackAdRevenue(adRevenue)
    }

    fun trackImpressionEvent(value: Double, currencyCode: String) {
        impressionEventToken?.let {
            val event = AdjustEvent(it).apply {
                setRevenue(value, currencyCode)
            }
            trackEvent(event)
        }
    }


    fun trackEvent(event: AdjustEvent) {
        if (!isInitialized()) return
        Adjust.trackEvent(event)
    }

    private fun callAdjustApi(context: Context) {
        if (apiToken == null || appToken == null) return

        CoroutineScope(Dispatchers.IO).launch {
            val advertisingId = AdvertisingIdUtil.getAdvertisingId(context) ?: run {
                Log.e(TAG, "Advertising ID is null")
                return@launch
            }

            val response = ApiClient().inspectDevice(appToken!!, advertisingId)
            val network = response.trackerName
            isFullAdFromApi = network.isFullAds()
            preferences?.edit { putString("ad_network", network.savableName()) }
            callAdCallback(network, false)
        }
    }

    private fun handleAttribution(attribution: AdjustAttribution) {
        if (isFullAdFromApi) return
        val network = attribution.network
        Log.d(TAG, "Network from callback: $network")
        preferences?.edit { putString("ad_network", network.savableName()) }
        callAdCallback(network, false)
    }

    private fun checkCachedAdNetwork() {
        val cached = preferences?.getString("ad_network", null)
        Log.d(TAG, "Network from cache: $cached")
        callAdCallback(cached.toOriginal(), true)
    }

    private fun callAdCallback(network: String?, fromCache: Boolean) {
        val isFullAds = network.isFullAds()
        fullAdCallback?.invoke(isFullAds, network, fromCache)
    }
}