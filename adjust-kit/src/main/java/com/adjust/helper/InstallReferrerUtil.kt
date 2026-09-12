package com.adjust.helper

import android.content.Context
import android.util.Log
import com.adjust.helper.model.InstallReferrerInfo
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Đọc Google Play Install Referrer.
 *
 * Google khuyến nghị chỉ gọi API 1 lần rồi tự lưu lại, nên kết quả được cache
 * vào SharedPreferences `adjust_prefs` và các lần sau đọc từ cache.
 */
object InstallReferrerUtil {

    private const val TAG = "InstallReferrer"
    private const val PREF_NAME = "adjust_prefs"
    private const val KEY_REFERRER = "install_referrer"
    private const val KEY_CLICK_TS = "install_referrer_click_ts"
    private const val KEY_INSTALL_TS = "install_referrer_install_ts"
    private const val KEY_INSTANT = "install_referrer_instant"
    private const val DEFAULT_TIMEOUT_MS = 5_000L

    /**
     * Trả về referrer (ưu tiên cache). Không bao giờ throw; lỗi được ghi vào
     * [InstallReferrerInfo.errorMessage]. Lỗi tạm thời (service unavailable,
     * timeout) KHÔNG được cache để lần sau thử lại.
     */
    suspend fun getInstallReferrer(
        context: Context,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): InstallReferrerInfo {
        readCache(context)?.let { return it }

        val info = withTimeoutOrNull(timeoutMs) { fetchFromPlay(context) }
            ?: InstallReferrerInfo(referrer = null, errorMessage = "TIMEOUT")

        if (info.errorMessage == null) writeCache(context, info)
        return info
    }

    private fun readCache(context: Context): InstallReferrerInfo? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_REFERRER)) return null
        return InstallReferrerInfo(
            referrer = prefs.getString(KEY_REFERRER, null),
            referrerClickTimestampSeconds = prefs.getLong(KEY_CLICK_TS, 0),
            installBeginTimestampSeconds = prefs.getLong(KEY_INSTALL_TS, 0),
            googlePlayInstant = prefs.getBoolean(KEY_INSTANT, false),
        )
    }

    private fun writeCache(context: Context, info: InstallReferrerInfo) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_REFERRER, info.referrer ?: "")
            .putLong(KEY_CLICK_TS, info.referrerClickTimestampSeconds)
            .putLong(KEY_INSTALL_TS, info.installBeginTimestampSeconds)
            .putBoolean(KEY_INSTANT, info.googlePlayInstant)
            .apply()
    }

    private suspend fun fetchFromPlay(context: Context): InstallReferrerInfo =
        suspendCancellableCoroutine { cont ->
            val client = try {
                InstallReferrerClient.newBuilder(context.applicationContext).build()
            } catch (e: Exception) {
                Log.e(TAG, "Cannot build InstallReferrerClient", e)
                cont.resume(InstallReferrerInfo(null, errorMessage = "BUILD_FAILED: ${e.message}"))
                return@suspendCancellableCoroutine
            }

            fun finish(info: InstallReferrerInfo) {
                try { client.endConnection() } catch (_: Exception) {}
                if (cont.isActive) cont.resume(info)
            }

            cont.invokeOnCancellation {
                try { client.endConnection() } catch (_: Exception) {}
            }

            try {
                client.startConnection(object : InstallReferrerStateListener {
                    override fun onInstallReferrerSetupFinished(responseCode: Int) {
                        when (responseCode) {
                            InstallReferrerClient.InstallReferrerResponse.OK -> {
                                val info = try {
                                    val d = client.installReferrer
                                    InstallReferrerInfo(
                                        referrer = d.installReferrer,
                                        referrerClickTimestampSeconds = d.referrerClickTimestampSeconds,
                                        installBeginTimestampSeconds = d.installBeginTimestampSeconds,
                                        googlePlayInstant = d.googlePlayInstantParam,
                                    )
                                } catch (e: Exception) {
                                    // RemoteException / SecurityException trên một số ROM.
                                    Log.e(TAG, "getInstallReferrer failed", e)
                                    InstallReferrerInfo(null, errorMessage = "READ_FAILED: ${e.message}")
                                }
                                Log.d(TAG, "referrer=${info.referrer}")
                                finish(info)
                            }

                            InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED ->
                                // Play Store quá cũ / không phải Play: coi như không có referrer, cache lại.
                                finish(InstallReferrerInfo(referrer = ""))

                            InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE ->
                                finish(InstallReferrerInfo(null, errorMessage = "SERVICE_UNAVAILABLE"))

                            InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR ->
                                finish(InstallReferrerInfo(null, errorMessage = "DEVELOPER_ERROR"))

                            InstallReferrerClient.InstallReferrerResponse.SERVICE_DISCONNECTED ->
                                finish(InstallReferrerInfo(null, errorMessage = "SERVICE_DISCONNECTED"))

                            else ->
                                finish(InstallReferrerInfo(null, errorMessage = "UNKNOWN_$responseCode"))
                        }
                    }

                    override fun onInstallReferrerServiceDisconnected() {
                        // Chỉ xảy ra sau khi đã kết nối; nếu chưa có kết quả thì coi là lỗi tạm thời.
                        finish(InstallReferrerInfo(null, errorMessage = "SERVICE_DISCONNECTED"))
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "startConnection failed", e)
                finish(InstallReferrerInfo(null, errorMessage = "CONNECT_FAILED: ${e.message}"))
            }
        }
}
