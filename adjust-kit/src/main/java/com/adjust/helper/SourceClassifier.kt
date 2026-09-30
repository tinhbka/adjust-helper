package com.adjust.helper

import com.adjust.helper.model.Certainty
import com.adjust.helper.model.Decider
import com.adjust.helper.model.Option1Info
import com.adjust.helper.model.Option2Data
import com.adjust.helper.model.SourceResult
import com.adjust.helper.model.Verdict

/**
 * Phân loại user paid / free từ 2 nguồn:
 *  - option 1: về nhanh (~2s). Có dấu hiệu paid -> PAID final ngay.
 *    Không có -> FREE provisional, chờ option 2.
 *  - option 2: về chậm hơn (~9s). Network không mang dấu free -> PAID final.
 *    Network mang dấu free chỉ đáng tin sau [Timing.trustAfterMs] kể từ [start];
 *    trước mốc đó coi như chưa có kết quả.
 *  - Hết [Timing.waitMaxMs] mà chưa final -> FREE provisional (decidedBy = timeout).
 *
 * Quy tắc:
 *  1. PAID luôn là final, không bao giờ hạ xuống FREE.
 *  2. FREE chỉ final khi cả 2 nguồn cùng free (option 2 sau mốc tin cậy).
 *  3. Chỉ chạy đầy đủ ở lần mở đầu tiên. Các lần sau đọc cache, option 2 vẫn có
 *     thể nâng FREE lên PAID (hoặc chốt FREE final).
 *
 * Mọi hàm phải gọi trên cùng 1 thread (Main).
 */
internal class SourceClassifier(
    private val store: Store,
    private val clock: () -> Long,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    private val timing: Timing = Timing(),
    private val appVersion: String? = null,
    private val onChange: (SourceResult) -> Unit,
) {

    data class Timing(
        val trustAfterMs: Long = 8_000,
        val waitMaxMs: Long = 20_000,
        val option1TimeoutMs: Long = 10_000,
    )

    interface Store {
        fun load(): SourceResult?
        fun save(result: SourceResult)
    }

    var current = SourceResult(appVersion = appVersion)
        private set

    private var startAt = 0L
    private var started = false

    private val elapsed: Long get() = clock() - startAt

    /** @return true nếu là lần mở đầu tiên và cần đọc option 1. */
    fun start(): Boolean {
        if (started) return current.firstRun
        started = true
        startAt = clock()

        val cached = store.load()
        if (cached != null) {
            current = cached.copy(firstRun = false, event = EVENT_CACHE)
            onChange(current)
            return false
        }

        current = SourceResult(appVersion = appVersion, firstRun = true)
        schedule(timing.waitMaxMs) { onTimeout() }
        return true
    }

    fun onOption1(info: Option1Info) {
        if (!current.firstRun) return
        current = current.copy(option1 = info, option1AtMs = elapsed)

        if (current.isFinal) {
            publish(EVENT_INFO)
            return
        }

        val paid = info.paidReason
        when {
            paid != null ->
                decide(Verdict.PAID, Certainty.FINAL, Decider.OPTION1, paid.code, paid.detail)

            option2Trusted() ->
                decide(Verdict.FREE, Certainty.FINAL, Decider.OPTION2, REASON_BOTH_FREE, current.network)

            else ->
                decide(Verdict.FREE, Certainty.PROVISIONAL, Decider.OPTION1, REASON_O1_FREE, info.freeDetail)
        }
    }

    fun onOption2(data: Option2Data) {
        if (data.network.isNullOrBlank()) return
        if (!current.firstRun) {
            // Lần mở sau: không có gì mới thì bỏ qua để khỏi phát trùng.
            if (data == current.option2 && (current.isPaid || current.isFinal)) return
            // Giữ mốc thời gian của lần mở đầu, mốc của phiên này không có ý nghĩa.
            current = current.copy(option2 = data)
        } else {
            current = current.copy(option2 = data, option2AtMs = elapsed)
        }

        if (!data.network.isFreeMark()) {
            if (current.isPaid) {
                publish(EVENT_INFO)
            } else {
                decide(Verdict.PAID, Certainty.FINAL, Decider.OPTION2, REASON_O2_NETWORK, data.network)
            }
            return
        }

        // Option 2 báo free.
        when {
            current.isPaid || current.isFinal -> publish(EVENT_INFO)

            // Lần mở sau: kết quả đã cũ, đáng tin.
            !current.firstRun ->
                decide(Verdict.FREE, Certainty.FINAL, Decider.OPTION2, REASON_BOTH_FREE, data.network)

            // Lần mở đầu: chỉ chốt khi option 1 cũng đã về free.
            option2Trusted() && current.option1 != null ->
                decide(Verdict.FREE, Certainty.FINAL, Decider.OPTION2, REASON_BOTH_FREE, data.network)

            else -> save() // chờ tiếp, không phát
        }
    }

    private fun onTimeout() {
        if (current.isFinal) return
        current = current.copy(timedOut = true)
        decide(Verdict.FREE, Certainty.PROVISIONAL, Decider.TIMEOUT, REASON_TIMEOUT, current.option1?.freeDetail)
    }

    /** Option 2 đã báo free và báo sau mốc tin cậy. */
    private fun option2Trusted(): Boolean {
        val at = current.option2AtMs ?: return false
        return current.network.isFreeMark() && at >= timing.trustAfterMs
    }

    private fun decide(
        verdict: Verdict,
        certainty: Certainty,
        by: Decider,
        reason: String,
        detail: String?,
    ) {
        if (current.isPaid && verdict != Verdict.PAID) return
        val upgrade = current.verdict == Verdict.FREE && verdict == Verdict.PAID
        current = current.copy(
            verdict = verdict,
            certainty = certainty,
            decidedBy = by,
            reason = reason,
            detail = detail,
            // Chỉ đo ở lần mở đầu; lần sau mốc thời gian không có ý nghĩa.
            decidedAtMs = if (current.firstRun) elapsed else null,
        )
        publish(if (upgrade) EVENT_UPGRADE else if (by == Decider.TIMEOUT) EVENT_TIMEOUT else EVENT_DECIDE)
    }

    private fun publish(event: String) {
        current = current.copy(event = event)
        save()
        onChange(current)
    }

    private fun save() {
        // Chưa quyết định gì thì không lưu, để lần mở sau chạy lại từ đầu.
        if (current.verdict != Verdict.UNKNOWN) store.save(current)
    }

    companion object {
        const val REASON_O1_FREE = "option1_free"
        const val REASON_O2_NETWORK = "option2_network"
        const val REASON_BOTH_FREE = "both_free"
        const val REASON_TIMEOUT = "timeout"
        const val REASON_LEGACY = "legacy"

        const val EVENT_CACHE = "cache"
        const val EVENT_DECIDE = "decide"
        const val EVENT_UPGRADE = "upgrade"
        const val EVENT_INFO = "info"
        const val EVENT_TIMEOUT = "timeout"
    }
}
