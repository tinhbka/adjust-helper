package com.adjust.helper

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.adjust.helper.model.AdOptions
import com.adjust.helper.model.FullAdsOption
import com.adjust.helper.model.IapOptions
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class AdjustChannel(private val context: Context, messenger: BinaryMessenger) :
    MethodChannel.MethodCallHandler {
    private val channel = MethodChannel(messenger, "com.custom.adjust.sdk/api")
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        channel.setMethodCallHandler(this)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "initSdk" -> {
                val environment = call.argument<String>("environment")
                val appToken = call.argument<String>("appToken")

                if (environment != null && appToken != null) {
                    val fullAdsOption = call.argument<Map<String, Any?>>("fullAdsOption")
                    AdjustBridge.apply {
                        this.environment = environment
                        this.appToken = appToken
                        this.fullAdsOption = FullAdsOption.fromMap(fullAdsOption ?: emptyMap())
                        this.apiToken = call.argument<String>("apiToken")
                        this.adOptions = AdOptions(
                            impressionToken = call.argument<String>("impressionToken"),
                            event80Token = call.argument<String>("event80Token"),
                            fullAdCallback = { isFullAds, network, fromCache, fromLib, fromApi ->
                                mainHandler.post {
                                    channel.invokeMethod(
                                        "onFullAdCallback",
                                        mapOf(
                                            "isFullAds" to isFullAds,
                                            "network" to network,
                                            "fromCache" to fromCache,
                                            "fromLib" to fromLib,
                                            "fromApi" to fromApi
                                        )
                                    )
                                }
                            }
                        )
                        this.iapOptions = IapOptions.fromMap(
                            call.argument<Map<String, Any?>>("iapOptions") ?: emptyMap()
                        )
                    }

                    AdjustBridge.initialize(
                        context
                    )
                    result.success(null)
                } else {
                    result.error("INVALID_ARGUMENT", "Environment or App Token is null", null)
                }
            }

            "trackAdRevenue" -> {
                val value = call.argument<Double>("value")
                val currencyCode = call.argument<String>("currencyCode")
                if (value != null && currencyCode != null) {
                    AdjustBridge.trackAdRevenue(value, currencyCode)
                    result.success(null)
                } else {
                    result.error("INVALID_ARGUMENT", "Value or Currency Code is null", null)
                }
            }

            "isInitialized" -> {
                result.success(AdjustBridge.isInitialized())
            }

            "trackEvent" -> {
                val callbackParams = (call.arguments as? Map<*, *>)
                val typedCallbackParams = callbackParams?.mapNotNull {
                    val key = it.key as? String
                    val value = it.value as? String
                    if (key != null && value != null) key to value else null
                }?.toMap()
                if (typedCallbackParams != null) {
                    AdjustBridge.trackEvent(typedCallbackParams.toAdjustEvent())
                    result.success(null)
                } else {
                    result.error("INVALID_ARGUMENT", "Event Token is null", null)
                }
            }

            "trackSubscription" -> {
                val price = call.argument<Double>("price")
                val currencyCode = call.argument<String>("currencyCode")
                val productId = call.argument<String>("productId")
                val orderId = call.argument<String>("orderId")
                val signature = call.argument<String>("signature")
                val purchaseToken = call.argument<String>("purchaseToken")
                val isFreeTrial = call.argument<Boolean>("isFreeTrial") ?: false
                // Dart int có thể về Integer hoặc Long -> đọc qua Number cho an toàn.
                val purchaseTime = (call.argument<Any>("purchaseTime") as? Number)?.toLong()

                if (price != null && currencyCode != null && productId != null) {
                    AdjustBridge.trackSubscription(
                        price, currencyCode, productId,
                        orderId,
                        signature,
                        purchaseToken,
                        isFreeTrial,
                        purchaseTime,
                    )
                    result.success(null)
                } else {
                    result.error(
                        "INVALID_ARGUMENT",
                        "Price, Currency Code or Product ID is null",
                        null
                    )
                }
            }

            "getAdid" -> {
                AdjustBridge.getAdid { adid ->
                    mainHandler.post { result.success(adid) }
                }
            }

            "getGoogleAdId" -> {
                AdjustBridge.getGoogleAdId(context) { googleAdId ->
                    mainHandler.post { result.success(googleAdId) }
                }
            }

            else -> {
                result.notImplemented()
            }

        }
    }
}