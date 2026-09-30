package com.adjust.helper

import com.adjust.helper.model.Option1Info
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Option1InfoTest {

    private fun reason(raw: String?, clickTs: Long = 0) =
        Option1Info(raw, clickTs = clickTs).paidReason

    @Test
    fun clickKeys_arePaid() {
        listOf(
            "gclid=abc&gbraid=x&gad_source=1&gad_campaignid=2" to "gclid",
            "gclid=abc&gbraid=x&gad_source=1" to "gclid",
            "gclid=abc" to "gclid",
            "adjust_external_click_id=E.C.P.xyz" to "adjust_external_click_id",
            "adjust_reftag=abc" to "adjust_reftag",
            "adjust_tracker=abc" to "adjust_tracker",
            "gad_source=1" to "gad_source",
            "utm_source=apps.facebook.com&utm_campaign=fb&gclid=123456789" to "gclid",
        ).forEach { (raw, key) ->
            val r = reason(raw)
            assertEquals(raw, Option1Info.REASON_KEY, r?.code)
            assertEquals(raw, key, r?.detail)
        }
    }

    @Test
    fun encodedOnce_isDecoded() {
        assertEquals("gclid", reason("gclid%3Dabc%26gbraid%3Dx")?.detail)
    }

    @Test
    fun otherSource_isPaid() {
        val r = reason("utm_source=newsletter")
        assertEquals(Option1Info.REASON_SOURCE, r?.code)
        assertEquals("newsletter", r?.detail)
    }

    @Test
    fun keys_areCaseInsensitive() {
        val r = reason("UTM_Source=TikTok&UTM_Medium=CPC")
        assertEquals(Option1Info.REASON_SOURCE, r?.code)
        assertEquals("TikTok", r?.detail)
    }

    @Test
    fun storeSourceOnly_isFree() {
        assertNull(reason("utm_source=google-play"))
    }

    @Test
    fun paidMedium_isPaid() {
        assertEquals(Option1Info.REASON_MEDIUM, reason("utm_source=google-play&utm_medium=cpc")?.code)
    }

    @Test
    fun storeFree_isFree() {
        val free = "cinagro".reversed()
        assertNull(reason("utm_source=google-play&utm_medium=$free"))
        assertNull(reason("utm_source=(not set)&utm_medium=(not set)"))
    }

    @Test
    fun emptyOrJunk_isFree() {
        assertNull(reason(null))
        assertNull(reason(""))
        assertNull(reason("not set"))
        assertNull(reason("network=d"))
        assertEquals("empty", Option1Info("").freeDetail)
        assertEquals("not_set", Option1Info("not set").freeDetail)
    }

    @Test
    fun clickTimestamp_withoutParams_isPaid() {
        assertEquals(Option1Info.REASON_CLICK, reason("network=d", clickTs = 1_700_000_000)?.code)
        assertEquals(Option1Info.REASON_CLICK, reason("", clickTs = 1_700_000_000)?.code)
    }

    @Test
    fun error_isFree() {
        val info = Option1Info(null, clickTs = 5, error = "TIMEOUT")
        assertNull(info.paidReason)
        assertEquals("error:TIMEOUT", info.freeDetail)
    }
}
