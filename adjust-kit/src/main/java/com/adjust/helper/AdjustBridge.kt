package com.adjust.helper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.adjust.helper.model.AdOptions
import com.adjust.helper.model.IapOptions
import com.adjust.helper.model.Option2Data
import com.adjust.helper.model.SourceResult
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var classifier: SourceClassifier? = null

    var appToken: String? = null
    var environment: String? = null
    internal var timing = SourceClassifier.Timing()
    var iapOptions: IapOptions? = null
    var adOptions: AdOptions? = null

    /** Kết quả hiện tại (null nếu chưa initialize). */
    val result: SourceResult? get() = classifier?.current

    val isPaid: Boolean get() = result?.isPaid == true

    val network: String? get() = result?.network

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

        val app = context.applicationContext
        val config = AdjustConfig(app, this.appToken!!, environment).apply {
            enableSendingInBackground()
            setLogLevel(LogLevel.VERBOSE)
            // Luôn nghe, kể cả khi đã có cache: option 2 có thể về muộn và nâng free lên paid.
            setOnAttributionChangedListener { attribution ->
                mainHandler.post { onOption2(attribution) }
            }
        }

        val c = SourceClassifier(
            store = LocalStore(app),
            clock = { SystemClock.elapsedRealtime() },
            schedule = { delay, block -> mainHandler.postDelayed(block, delay) },
            timing = timing,
            appVersion = appVersion(app),
            onChange = { r ->
                Log.d(TAG, "result: ${r.toJson()}")
                adOptions?.paidCallback?.invoke(r)
            },
        )
        classifier = c

        val firstRun = c.start()
        if (firstRun) readOption1(app, c)

        Adjust.initSdk(config)
        isInitialized = true

        if (!firstRun && !c.current.isPaid) {
            // Lần mở sau mà vẫn free: hỏi lại option 2 đã lưu trong SDK.
            Adjust.getAttribution { attribution ->
                mainHandler.post { onOption2(attribution) }
            }
        }
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

    private fun readOption1(context: Context, c: SourceClassifier) {
        CoroutineScope(Dispatchers.IO).launch {
            val info = Option1Reader.read(context, timing.option1TimeoutMs)
            withContext(Dispatchers.Main) { c.onOption1(info) }
        }
    }

    private fun onOption2(attribution: AdjustAttribution?) {
        attribution ?: return
        classifier?.onOption2(
            Option2Data(
                network = attribution.network,
                tracker = attribution.trackerName,
                campaign = attribution.campaign,
                adgroup = attribution.adgroup,
                creative = attribution.creative,
            )
        )
    }

    private fun appVersion(context: Context): String? = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName}+${PackageInfoCompat.getLongVersionCode(info)}"
    } catch (e: Exception) {
        null
    }
}
