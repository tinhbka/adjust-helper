package com.adjust.helper

import android.content.Context
import com.adjust.helper.model.FullAdsOption
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class AdjustChannel : FlutterPlugin, MethodChannel.MethodCallHandler {
    private var channel: MethodChannel? = null
    private var applicationContext: Context? = null
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "com.adjust.sdk/api")
        channel!!.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = null
        if (channel != null) {
            channel!!.setMethodCallHandler(null)
        }
        channel = null

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
                        this.impressionEventToken = call.argument<String>("impressionEventToken")
                        this.fullAdCallback = { isFullAds, network, fromCache ->
                            channel?.invokeMethod(
                                "fullAdCallback",
                                mapOf(
                                    "isFullAds" to isFullAds,
                                    "network" to network,
                                    "fromCache" to fromCache
                                )
                            )
                        }
                    }

                    AdjustBridge.initialize(
                        applicationContext!!
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

            "trackImpressionEvent" -> {
                val value = call.argument<Double>("value")
                val currencyCode = call.argument<String>("currencyCode")
                if (value != null && currencyCode != null) {
                    AdjustBridge.trackImpressionEvent(value, currencyCode)
                    result.success(null)
                } else {
                    result.error("INVALID_ARGUMENT", "Event Token is null", null)
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

            else -> {
                result.notImplemented()
            }

        }
    }
}