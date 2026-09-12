package com.adjust.helper

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.adjust.helper.model.AdOptions
import com.adjust.helper.model.FullAdsOption
import com.adjust.helper.model.IapOptions
import com.adjust.helper.model.InstallReferrerInfo
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

    /**
     * Kết quả full ads hiện tại. Nguồn quyết định:
     *  - Install Referrer (ưu tiên) khi referrer đọc được và có kết luận.
     *  - Fallback: tên network từ Adjust (API / SDK) qua [isFullAds] khi referrer
     *    lỗi, rỗng, "(not set)" hoặc tắt [FullAdsOption.useReferrer].
     */
    @Volatile
    var isFullAd = false
        private set

    /** Tên network hiện biết từ Adjust (API ưu tiên hơn SDK). */
    @Volatile
    var network: String? = null
        private set

    /** Kết quả Install Referrer lần đọc gần nhất (null nếu chưa đọc / tắt useReferrer). */
    @Volatile
    var installReferrer: InstallReferrerInfo? = null
        private set

    private enum class ReferrerState { PENDING, DECIDED, FALLBACK }

    @Volatile
    private var referrerState = ReferrerState.PENDING
    private var networkFromApi = false
    private var networkFromLib = false

    private const val KEY_NETWORK = "ad_network"
    private const val KEY_IS_FULL_AD = "is_full_ad"
    private const val KEY_FULL_AD_SOURCE = "full_ad_source"
    private const val SOURCE_REFERRER = "referrer"
    private const val SOURCE_ADJUST = "adjust"

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
        val prefs = preferences!!
        val config = AdjustConfig(context, this.appToken!!, environment).apply {
            enableSendingInBackground()
            setLogLevel(LogLevel.VERBOSE)
        }

        val cachedNetwork = prefs.getString(KEY_NETWORK, null)?.toOriginal()
        val hasCachedDecision = prefs.contains(KEY_IS_FULL_AD)

        if (hasCachedDecision || cachedNetwork != null) {
            // Đã có quyết định từ lần trước.
            network = cachedNetwork
            isFullAd = if (hasCachedDecision) {
                prefs.getBoolean(KEY_IS_FULL_AD, false)
            } else {
                cachedNetwork.isFullAds() // cache từ version cũ, chỉ có tên network
            }
            referrerState = if (prefs.getString(KEY_FULL_AD_SOURCE, null) == SOURCE_REFERRER) {
                ReferrerState.DECIDED
            } else {
                ReferrerState.FALLBACK
            }
            fireCallback(fromCache = true)
            // Đã quyết định full ads từ referrer nhưng chưa biết tên network -> hỏi Adjust bổ sung.
            if (cachedNetwork == null && !prefs.contains(KEY_NETWORK)) {
                callAdjustApi(context)
                setupAttributionListener(config)
            }
        } else {
            if (fullAdsOption.useReferrer) {
                checkInstallReferrer(context)
            } else {
                referrerState = ReferrerState.FALLBACK
            }
            callAdjustApi(context)
            setupAttributionListener(config)
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

    /**
     * Lấy Adjust device ID (adid). Trả về async qua [callback] (có thể null nếu SDK
     * chưa gán được adid).
     */
    fun getAdid(callback: (String?) -> Unit) {
        if (!isInitialized()) {
            callback(null)
            return
        }
        Adjust.getAdid { adid ->
            Log.d(TAG, "getAdid: $adid")
            callback(adid)
        }
    }

    /**
     * Lấy Google Advertising ID (GAID). Trả về async qua [callback] (có thể null nếu
     * user opt-out hoặc không lấy được).
     */
    fun getGoogleAdId(context: Context, callback: (String?) -> Unit) {
        Adjust.getGoogleAdId(context) { googleAdId ->
            Log.d(TAG, "getGoogleAdId: $googleAdId")
            callback(googleAdId)
        }
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
                val trackerName = response.trackerName
                withContext(Dispatchers.Main) {
                    onAdjustNetwork(trackerName, fromApi = true)
                }
            } catch (e: Exception) {
                Log.e("CoroutineError", "Caught: ${e.message}")
            }
        }
    }

    private fun setupAttributionListener(config: AdjustConfig) {
        adOptions?.fullAdCallback ?: return
        config.setOnAttributionChangedListener { attribution ->
            CoroutineScope(Dispatchers.Main).launch { handleAttribution(attribution) }
        }
    }

    /**
     * Đọc Google Play Install Referrer song song với API Adjust.
     *  - Referrer có kết luận (organic / non-organic) -> quyết định [isFullAd] ngay,
     *    lưu cache, bắn callback với tên network hiện có (có thể null nếu Adjust chưa về).
     *  - Referrer lỗi / rỗng / "(not set)" -> chuyển sang FALLBACK: [isFullAd] tính từ
     *    tên network của Adjust như trước đây.
     * Callback KHÔNG bắn khi referrer còn PENDING để tránh báo sai rồi lật lại.
     */
    private fun checkInstallReferrer(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val info = try {
                InstallReferrerUtil.getInstallReferrer(context)
            } catch (e: Exception) {
                InstallReferrerInfo(null, errorMessage = "Exception: ${e.message}")
            }
            installReferrer = info
            Log.d(
                TAG,
                "InstallReferrer: referrer=${info.referrer}, decisive=${info.isDecisive}, " +
                        "nonOrganic=${info.isNonOrganic}, error=${info.errorMessage}"
            )

            withContext(Dispatchers.Main) {
                if (info.isDecisive) {
                    referrerState = ReferrerState.DECIDED
                    isFullAd = info.isNonOrganic
                    saveDecision(SOURCE_REFERRER)
                    fireCallback(fromCache = false)
                } else {
                    referrerState = ReferrerState.FALLBACK
                    // Nếu Adjust đã trả tên network trong lúc chờ referrer thì quyết định luôn.
                    if (networkFromApi || networkFromLib) {
                        isFullAd = network.isFullAds()
                        saveDecision(SOURCE_ADJUST)
                        fireCallback(fromCache = false)
                    }
                }
            }
        }
    }

    /**
     * Nhận tên network từ Adjust (API hoặc SDK). Chạy trên Main thread.
     * API ưu tiên hơn SDK: khi API đã trả về thì bỏ qua SDK.
     */
    private fun onAdjustNetwork(trackerName: String?, fromApi: Boolean) {
        if (!fromApi && networkFromApi) return
        network = trackerName
        networkFromApi = fromApi
        networkFromLib = !fromApi
        preferences?.edit { putString(KEY_NETWORK, trackerName.savableName()) }
        Log.d(TAG, "Network from ${if (fromApi) "API" else "SDK"}: $trackerName")

        when (referrerState) {
            ReferrerState.PENDING -> Unit // chờ referrer quyết định rồi bắn 1 lần
            ReferrerState.DECIDED -> fireCallback(fromCache = false) // chỉ bổ sung tên network
            ReferrerState.FALLBACK -> {
                isFullAd = trackerName.isFullAds()
                saveDecision(SOURCE_ADJUST)
                fireCallback(fromCache = false)
            }
        }
    }

    private fun handleAttribution(attribution: AdjustAttribution) {
        onAdjustNetwork(attribution.network, fromApi = false)
    }

    private fun saveDecision(source: String) {
        preferences?.edit {
            putBoolean(KEY_IS_FULL_AD, isFullAd)
            putString(KEY_FULL_AD_SOURCE, source)
        }
    }

    /**
     * Bắn [FullAdCallback].
     *  - fromReferrer: [isFullAd] do Install Referrer quyết định.
     *  - fromApi / fromLib: tên [network] lấy từ API / SDK Adjust (và là nguồn
     *    quyết định [isFullAd] khi fromReferrer = false).
     */
    private fun fireCallback(fromCache: Boolean) {
        adOptions?.fullAdCallback?.invoke(
            isFullAd,
            network,
            fromCache,
            networkFromLib,
            networkFromApi,
            referrerState == ReferrerState.DECIDED,
        )
    }

    /**
     * Lấy Google Play Install Referrer (đã cache). Trả về async qua [callback].
     */
    fun getInstallReferrer(context: Context, callback: (InstallReferrerInfo) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val info = try {
                InstallReferrerUtil.getInstallReferrer(context)
            } catch (e: Exception) {
                InstallReferrerInfo(null, errorMessage = "Exception: ${e.message}")
            }
            installReferrer = info
            withContext(Dispatchers.Main) { callback(info) }
        }
    }
}