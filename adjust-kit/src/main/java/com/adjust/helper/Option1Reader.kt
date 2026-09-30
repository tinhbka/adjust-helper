package com.adjust.helper

import android.content.Context
import android.util.Log
import com.adjust.helper.model.Option1Info
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Đọc option 1. Nguồn chỉ nên gọi 1 lần rồi tự lưu, nên kết quả thành công
 * được cache và các lần sau đọc từ cache.
 */
internal object Option1Reader {

    private const val TAG = "O1"
    private const val KEY_RAW = "o1_raw"
    private const val KEY_CLICK_TS = "o1_click"
    private const val KEY_INSTALL_TS = "o1_install"
    private const val KEY_INSTANT = "o1_instant"

    /**
     * Không bao giờ throw; lỗi được ghi vào [Option1Info.error]. Lỗi tạm thời
     * (service unavailable, timeout) KHÔNG được cache để lần sau thử lại.
     */
    suspend fun read(context: Context, timeoutMs: Long): Option1Info {
        readCache(context)?.let { return it }

        val info = try {
            withTimeoutOrNull(timeoutMs) { fetch(context) }
                ?: Option1Info(raw = null, error = "TIMEOUT")
        } catch (e: Exception) {
            Option1Info(raw = null, error = "EXCEPTION: ${e.message}")
        }

        if (info.error == null) writeCache(context, info)
        return info
    }

    private fun readCache(context: Context): Option1Info? {
        val prefs = LocalStore.prefs(context)
        if (!prefs.contains(KEY_RAW)) return null
        return Option1Info(
            raw = prefs.getString(KEY_RAW, null),
            clickTs = prefs.getLong(KEY_CLICK_TS, 0),
            installTs = prefs.getLong(KEY_INSTALL_TS, 0),
            instant = prefs.getBoolean(KEY_INSTANT, false),
        )
    }

    private fun writeCache(context: Context, info: Option1Info) {
        LocalStore.prefs(context).edit()
            .putString(KEY_RAW, info.raw ?: "")
            .putLong(KEY_CLICK_TS, info.clickTs)
            .putLong(KEY_INSTALL_TS, info.installTs)
            .putBoolean(KEY_INSTANT, info.instant)
            .apply()
    }

    private suspend fun fetch(context: Context): Option1Info =
        suspendCancellableCoroutine { cont ->
            val client = try {
                InstallReferrerClient.newBuilder(context.applicationContext).build()
            } catch (e: Exception) {
                Log.e(TAG, "build failed", e)
                cont.resume(Option1Info(null, error = "BUILD_FAILED: ${e.message}"))
                return@suspendCancellableCoroutine
            }

            fun finish(info: Option1Info) {
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
                                    Option1Info(
                                        raw = d.installReferrer,
                                        clickTs = d.referrerClickTimestampSeconds,
                                        installTs = d.installBeginTimestampSeconds,
                                        instant = d.googlePlayInstantParam,
                                    )
                                } catch (e: Exception) {
                                    // RemoteException / SecurityException trên một số ROM.
                                    Log.e(TAG, "read failed", e)
                                    Option1Info(null, error = "READ_FAILED: ${e.message}")
                                }
                                finish(info)
                            }

                            InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED ->
                                // Store quá cũ / không có: coi như chuỗi rỗng, cache lại.
                                finish(Option1Info(raw = ""))

                            InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE ->
                                finish(Option1Info(null, error = "SERVICE_UNAVAILABLE"))

                            InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR ->
                                finish(Option1Info(null, error = "DEVELOPER_ERROR"))

                            InstallReferrerClient.InstallReferrerResponse.SERVICE_DISCONNECTED ->
                                finish(Option1Info(null, error = "SERVICE_DISCONNECTED"))

                            else ->
                                finish(Option1Info(null, error = "UNKNOWN_$responseCode"))
                        }
                    }

                    override fun onInstallReferrerServiceDisconnected() {
                        // Chỉ xảy ra sau khi đã kết nối; chưa có kết quả thì coi là lỗi tạm thời.
                        finish(Option1Info(null, error = "SERVICE_DISCONNECTED"))
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "connect failed", e)
                finish(Option1Info(null, error = "CONNECT_FAILED: ${e.message}"))
            }
        }
}
