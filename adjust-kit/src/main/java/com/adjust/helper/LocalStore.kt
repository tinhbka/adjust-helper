package com.adjust.helper

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.adjust.helper.model.Certainty
import com.adjust.helper.model.Decider
import com.adjust.helper.model.Option2Data
import com.adjust.helper.model.SourceResult
import com.adjust.helper.model.Verdict
import org.json.JSONObject

/** Lưu [SourceResult] dạng JSON trong SharedPreferences. */
internal class LocalStore(private val context: Context) : SourceClassifier.Store {

    override fun load(): SourceResult? {
        val raw = prefs(context).getString(KEY_RESULT, null) ?: return loadPrevious()
        return try {
            SourceResult.fromJson(JSONObject(raw))
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
            null
        }
    }

    override fun save(result: SourceResult) {
        prefs(context).edit().putString(KEY_RESULT, result.toJson().toString()).apply()
    }

    /**
     * Đọc kết quả do phiên bản trước của lib để lại, chuyển sang dạng mới rồi xoá
     * dữ liệu cũ. Kết quả free cũ chỉ là provisional để option 2 còn nâng lên được.
     */
    private fun loadPrevious(): SourceResult? {
        val old = context.getSharedPreferences(PREV_NAME, Context.MODE_PRIVATE)
        val hasFlag = old.contains(PREV_FLAG)
        val hasNetwork = old.contains(PREV_NETWORK)
        if (!hasFlag && !hasNetwork) return null

        val network = when (val n = old.getString(PREV_NETWORK, null)) {
            "null" -> null
            "empty" -> ""
            else -> n
        }
        val paid = if (hasFlag) {
            old.getBoolean(PREV_FLAG, false)
        } else {
            !network.isNullOrBlank() && !network.isFreeMark()
        }
        val result = SourceResult(
            verdict = if (paid) Verdict.PAID else Verdict.FREE,
            certainty = if (paid) Certainty.FINAL else Certainty.PROVISIONAL,
            decidedBy = Decider.LEGACY,
            reason = SourceClassifier.REASON_LEGACY,
            detail = network,
            option2 = network?.let { Option2Data(network = it) },
        )
        save(result)
        old.edit().clear().apply()
        return result
    }

    companion object {
        private const val TAG = "LocalStore"
        private const val NAME = "hp_store"
        private const val KEY_RESULT = "s1"

        private val PREV_NAME = "sferp_tsujda".reversed()
        private val PREV_FLAG = "da_lluf_si".reversed()
        private val PREV_NETWORK = "krowten_da".reversed()

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }
}
