package com.adjust.helper

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.adjust.helper.model.AdOptions
import com.adjust.helper.model.FullAdsOption
import com.adjust.helper.model.IapOptions
import com.adjust.sdk.Adjust
import com.adjust.sdk.AdjustAdRevenue
import com.adjust.sdk.AdjustAttribution
import com.adjust.sdk.AdjustConfig
import com.adjust.sdk.AdjustEvent
import com.adjust.sdk.AdjustPlayStoreSubscription
import com.adjust.sdk.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


object AdjustBridge {

    private const val TAG = "AdjustUtil"

    private var isInitialized = false
    private var preferences: SharedPreferences? = null
    var appToken: String? = null
    var environment: String? = null
    var apiToken: String? = null
    var fullAdsOption = FullAdsOption()
    var iapOptions: IapOptions? = null
    var adOptions: AdOptions? = null

    var fullAdFromApi = false

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
            setLogLevel(LogLevel.VERBOSE)
        }

        if (cachedNetwork == null) {
            callAdjustApi(context)
            adOptions?.fullAdCallback?.let {
                config.setOnAttributionChangedListener { attribution ->
                    handleAttribution(attribution)
                }
            }
        } else {
            callAdCallback(
                cachedNetwork.toOriginal(),
                fromCache = true,
                fromLib = false,
                fromApi = false,
            )
        }
        Adjust.initSdk(config)

        isInitialized = true
    }

    fun trackAdRevenue(value: Double, currencyCode: String) {
        if (!isInitialized()) return
        val adRevenue = AdjustAdRevenue("admob_sdk").apply {
            setRevenue(value, currencyCode)
        }
        Adjust.trackAdRevenue(adRevenue)

        // Impression event
        adOptions?.impressionToken?.let {
            val event = AdjustEvent(it).apply {
                setRevenue(value, currencyCode)
            }
            trackEvent(event)
        }

        // 80% revenue event
        adOptions?.event80Token?.let {
            val event = AdjustEvent(it).apply {
                setRevenue(value * 0.8, currencyCode)
            }
            trackEvent(event)
        }
    }

    /**
     * Track subscription và bắn nhiều event Adjust:
     *  - [isFreeTrial] = true  -> user bắt đầu free trial -> bắn [IapOptions.freeTrialToken] (không revenue).
     *  - [isFreeTrial] = false -> mua giá thật / renew     -> bắn:
     *        + event theo gói: [IapOptions.productRevenueTokens] map theo [productId] (vd tuần/tháng)
     *        + event tổng:     [IapOptions.subscriptionToken] (mọi gói paid)
     *
     * Đồng thời luôn gửi [AdjustPlayStoreSubscription] để Adjust validate với Google
     * và tự track toàn bộ vòng đời (initial + trial-convert + renew) ở server.
     *
     * @param price giá ở ĐƠN VỊ CHÍNH (vd 4.99). Nội bộ tự đổi sang micros cho Adjust.
     *              Với free trial nên truyền GIÁ THẬT của gói (sau trial) để Adjust
     *              attribute doanh thu khi convert.
     * @param purchaseTimeMillis thời điểm mua (ms) từ Google Play, optional.
     */
    fun trackSubscription(
        price: Double,
        currencyCode: String,
        productId: String,
        orderId: String?,
        signature: String?,
        purchaseToken: String?,
        isFreeTrial: Boolean,
        purchaseTimeMillis: Long? = null,
    ) {
        if (!isInitialized()) return

        // 1) Bắn (các) custom event tương ứng.
        if (isFreeTrial) {
            // Free trial chưa thu tiền -> không set revenue.
            iapOptions?.freeTrialToken?.let {
                fireSubscriptionEvent(it, null, currencyCode, productId, orderId, purchaseToken)
            }
        } else {
            // Event theo từng gói (vd subscription week / month).
            iapOptions?.productRevenueTokens?.get(productId)?.let {
                fireSubscriptionEvent(it, price, currencyCode, productId, orderId, purchaseToken)
            }
            // Event tổng cho mọi gói paid.
            iapOptions?.subscriptionToken?.let {
                fireSubscriptionEvent(it, price, currencyCode, productId, orderId, purchaseToken)
            }
        }

        // 2) Gửi Play Store subscription (price phải là MICROS).
        val priceMicros = (price * 1_000_000).toLong()
        val subscription = AdjustPlayStoreSubscription(
            priceMicros,
            currencyCode,
            productId,
            orderId,
            signature,
            purchaseToken,
        )
        purchaseTimeMillis?.let { subscription.purchaseTime = it }
        Adjust.trackPlayStoreSubscription(subscription)
    }

    private fun fireSubscriptionEvent(
        token: String,
        revenue: Double?,
        currencyCode: String,
        productId: String,
        orderId: String?,
        purchaseToken: String?,
    ) {
        val event = AdjustEvent(token).apply {
            revenue?.let { setRevenue(it, currencyCode) }
            setProductId(productId)
            orderId?.let { setOrderId(it) }
            purchaseToken?.let { setPurchaseToken(it) }
        }
        trackEvent(event)
    }

    fun trackEvent(event: AdjustEvent) {
        if (!isInitialized()) return
        val eventJson = mapOf(
            "eventToken" to event.eventToken,
            "revenue" to event.revenue,
            "productId" to event.productId,
            "orderId" to event.orderId,
            "purchaseToken" to event.purchaseToken,
            "currency" to event.currency,
        )
        Log.d("[Adjust] trackEvent: ", eventJson.toString())
        Adjust.trackEvent(event)
    }

    private fun callAdjustApi(context: Context) {
        if (apiToken == null || appToken == null) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val advertisingId = AdvertisingIdUtil.getAdvertisingId(context) ?: run {
                    Log.e(TAG, "Advertising ID is null")
                    return@launch
                }

                val response = ApiClient().inspectDevice(appToken!!, advertisingId)
                if (response.errorMessage != null) {
                    return@launch
                }
                val network = response.trackerName
                fullAdFromApi = network.isFullAds()

                withContext(Dispatchers.Main) {
                    preferences?.edit { putString("ad_network", network.savableName()) }
                    callAdCallback(network, fromCache = false, fromLib = false, fromApi = true)
                }
            } catch (e: Exception) {
                Log.e("CoroutineError", "Caught: ${e.message}")
            }
        }
    }

    private fun handleAttribution(attribution: AdjustAttribution) {
        if (fullAdFromApi) {
            return
        }
        val network = attribution.network
        Log.d(TAG, "Network from callback: $network")
        preferences?.edit { putString("ad_network", network.savableName()) }
        callAdCallback(network, fromCache = false, fromLib = true, fromApi = false)
    }

    private fun callAdCallback(
        network: String?, fromCache: Boolean, fromLib: Boolean, fromApi: Boolean
    ) {
        val isFullAds = network.isFullAds()
        adOptions?.fullAdCallback?.invoke(isFullAds, network, fromCache, fromLib, fromApi)
    }
}