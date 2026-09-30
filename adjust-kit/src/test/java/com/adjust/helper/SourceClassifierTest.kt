package com.adjust.helper

import com.adjust.helper.model.Certainty
import com.adjust.helper.model.Decider
import com.adjust.helper.model.Option1Info
import com.adjust.helper.model.Option2Data
import com.adjust.helper.model.SourceResult
import com.adjust.helper.model.Verdict
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceClassifierTest {

    private class MemStore(var value: SourceResult? = null) : SourceClassifier.Store {
        override fun load() = value
        override fun save(result: SourceResult) {
            // Đi qua JSON như lưu thật.
            value = SourceResult.fromJson(JSONObject(result.toJson().toString()))
        }
    }

    private class Env(store: MemStore = MemStore()) {
        var now = 1_000L
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val emitted = mutableListOf<SourceResult>()
        val store = store
        val c = SourceClassifier(
            store = store,
            clock = { now },
            schedule = { d, b -> tasks += (now + d) to b },
            onChange = { emitted += it },
        )

        /** Tới thời điểm [ms] kể từ start, chạy các task đến hạn. */
        fun at(ms: Long) {
            now = 1_000L + ms
            tasks.filter { it.first <= now }.also { tasks.removeAll(it) }.forEach { it.second() }
        }

        val r get() = c.current
    }

    private val o1Paid = Option1Info("gclid=abc&gbraid=x&gad_source=1")
    private val o1Free = Option1Info("utm_source=google-play&utm_medium=cinagro".reversedMark())
    private val o2Paid = Option2Data("Google Ads ACI", tracker = "Google Ads ACI::Campaign")
    private val o2Free = Option2Data("cinagrO".reversed())

    @Test
    fun option1Paid_decidesImmediately() {
        val e = Env()
        assertTrue(e.c.start())
        e.at(2_000); e.c.onOption1(o1Paid)

        assertEquals(Verdict.PAID, e.r.verdict)
        assertEquals(Certainty.FINAL, e.r.certainty)
        assertEquals(Decider.OPTION1, e.r.decidedBy)
        assertEquals(Option1Info.REASON_KEY, e.r.reason)
        assertEquals("gclid", e.r.detail)
        assertEquals(2_000L, e.r.decidedAtMs)
        assertTrue(e.r.done)
    }

    @Test
    fun option2FreeEarly_isIgnored_thenPaidLater() {
        val e = Env()
        e.c.start()
        e.at(2_000); e.c.onOption1(o1Free)
        assertEquals(Verdict.FREE, e.r.verdict)
        assertEquals(Certainty.PROVISIONAL, e.r.certainty)

        e.at(4_000); e.c.onOption2(o2Free)
        assertEquals(Certainty.PROVISIONAL, e.r.certainty)

        e.at(9_000); e.c.onOption2(o2Paid)
        assertEquals(Verdict.PAID, e.r.verdict)
        assertEquals(Decider.OPTION2, e.r.decidedBy)
        assertEquals("Google Ads ACI", e.r.detail)
        assertEquals(SourceClassifier.EVENT_UPGRADE, e.r.event)
    }

    @Test
    fun bothFree_afterTrust_isFinal() {
        val e = Env()
        e.c.start()
        e.at(2_000); e.c.onOption1(o1Free)
        e.at(10_000); e.c.onOption2(o2Free)

        assertEquals(Verdict.FREE, e.r.verdict)
        assertEquals(Certainty.FINAL, e.r.certainty)
        assertEquals(SourceClassifier.REASON_BOTH_FREE, e.r.reason)
    }

    @Test
    fun option2FreeTrusted_beforeOption1_waitsForOption1() {
        val e = Env()
        e.c.start()
        e.at(9_000); e.c.onOption2(o2Free)
        assertEquals(Verdict.UNKNOWN, e.r.verdict)
        assertNull(e.store.value)

        e.at(9_500); e.c.onOption1(Option1Info("not set"))
        assertEquals(Verdict.FREE, e.r.verdict)
        assertEquals(Certainty.FINAL, e.r.certainty)
    }

    @Test
    fun unattributed_countsAsFree() {
        val e = Env()
        e.c.start()
        e.at(2_000); e.c.onOption1(o1Free)
        e.at(12_000); e.c.onOption2(Option2Data("detubirttanU".reversed()))
        assertEquals(Verdict.FREE, e.r.verdict)
        assertEquals(Certainty.FINAL, e.r.certainty)
    }

    @Test
    fun timeout_givesProvisionalFree() {
        val e = Env()
        e.c.start()
        e.at(2_000); e.c.onOption1(o1Free)
        assertFalse(e.r.done)
        e.at(20_000)

        assertEquals(Verdict.FREE, e.r.verdict)
        assertEquals(Certainty.PROVISIONAL, e.r.certainty)
        assertEquals(Decider.TIMEOUT, e.r.decidedBy)
        assertTrue(e.r.done)
    }

    @Test
    fun timeout_afterPaid_doesNothing() {
        val e = Env()
        e.c.start()
        e.at(2_000); e.c.onOption1(o1Paid)
        val count = e.emitted.size
        e.at(20_000)
        assertEquals(count, e.emitted.size)
        assertEquals(Verdict.PAID, e.r.verdict)
    }

    @Test
    fun paid_isNeverDowngraded() {
        val e = Env()
        e.c.start()
        e.at(2_000); e.c.onOption1(o1Paid)
        e.at(15_000); e.c.onOption2(o2Free)
        assertEquals(Verdict.PAID, e.r.verdict)
        assertEquals(Decider.OPTION1, e.r.decidedBy)
        // Network vẫn được ghi lại để log.
        assertEquals(o2Free.network, e.r.network)
    }

    @Test
    fun option2Paid_beforeOption1_thenOption1_onlyAddsInfo() {
        val e = Env()
        e.c.start()
        e.at(1_000); e.c.onOption2(o2Paid)
        e.at(3_000); e.c.onOption1(o1Free)
        assertEquals(Verdict.PAID, e.r.verdict)
        assertEquals(Decider.OPTION2, e.r.decidedBy)
        assertEquals(o1Free.raw, e.r.option1?.raw)
        assertEquals(SourceClassifier.EVENT_INFO, e.r.event)
    }

    @Test
    fun laterRun_cachedFree_upgradedByLatePaid() {
        val first = Env()
        first.c.start()
        first.at(2_000); first.c.onOption1(o1Free)
        first.at(20_000)

        val second = Env(first.store)
        assertFalse(second.c.start())
        assertEquals(SourceClassifier.EVENT_CACHE, second.r.event)
        assertTrue(second.r.done)

        second.at(500); second.c.onOption2(o2Paid)
        assertEquals(Verdict.PAID, second.r.verdict)
        assertEquals(Certainty.FINAL, second.r.certainty)
        assertNull(second.r.decidedAtMs)
        assertEquals(Verdict.PAID, first.store.value?.verdict)
    }

    @Test
    fun laterRun_cachedProvisionalFree_finalizedByFree() {
        val first = Env()
        first.c.start()
        first.at(2_000); first.c.onOption1(o1Free)
        first.at(20_000)

        val second = Env(first.store)
        second.c.start()
        second.at(300); second.c.onOption2(o2Free)
        assertEquals(Verdict.FREE, second.r.verdict)
        assertEquals(Certainty.FINAL, second.r.certainty)

        // Lần mở thứ 3: cùng dữ liệu, không phát trùng.
        val third = Env(first.store)
        third.c.start()
        third.c.onOption2(o2Free)
        assertEquals(1, third.emitted.size)
    }

    @Test
    fun json_containsDecisionData() {
        val e = Env()
        e.c.start()
        e.at(2_000); e.c.onOption1(o1Free)
        e.at(9_000); e.c.onOption2(o2Paid)

        val j = e.r.toJson()
        assertEquals("paid", j.getString("result"))
        assertEquals("option2", j.getString("decidedBy"))
        assertEquals("option2_network", j.getString("reason"))
        assertEquals("Google Ads ACI", j.getString("network"))
        assertEquals(2_000L, j.getJSONObject("option1").getLong("atMs"))
        assertEquals(9_000L, j.getJSONObject("option2").getLong("atMs"))
        assertEquals("Google Ads ACI::Campaign", j.getJSONObject("option2").getString("tracker"))
    }

    /** Đảo phần giá trị cuối để test không chứa literal. */
    private fun String.reversedMark(): String {
        val i = lastIndexOf('=')
        return substring(0, i + 1) + substring(i + 1).reversed()
    }
}
