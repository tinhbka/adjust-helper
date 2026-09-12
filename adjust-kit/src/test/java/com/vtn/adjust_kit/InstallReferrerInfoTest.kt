package com.vtn.adjust_kit

import com.adjust.helper.model.InstallReferrerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallReferrerInfoTest {

    @Test
    fun `organic google play referrer is organic`() {
        val info = InstallReferrerInfo("utm_source=google-play&utm_medium=organic")
        assertFalse(info.isNonOrganic)
        assertEquals("google-play", info.utmSource)
        assertEquals("organic", info.utmMedium)
    }

    @Test
    fun `empty or null referrer is not conclusive`() {
        assertFalse(InstallReferrerInfo(null).isNonOrganic)
        assertFalse(InstallReferrerInfo("").isNonOrganic)
        assertFalse(InstallReferrerInfo(null, errorMessage = "SERVICE_UNAVAILABLE").isNonOrganic)
    }

    @Test
    fun `not set values are ignored`() {
        val info = InstallReferrerInfo("utm_source=(not%20set)&utm_medium=(not%20set)")
        assertFalse(info.isNonOrganic)
        assertNull(info.utmSource)
        assertNull(info.utmMedium)
    }

    @Test
    fun `gclid means paid install`() {
        val info = InstallReferrerInfo("gclid=abc123&utm_source=google&utm_medium=cpc&utm_campaign=x")
        assertTrue(info.isNonOrganic)
        assertEquals("gclid", info.paidClickKey)
        assertEquals("referrer:google", info.networkName)
    }

    @Test
    fun `adjust reftag means tracker link install`() {
        val info = InstallReferrerInfo("adjust_reftag=cQx1&utm_source=adjust_test")
        assertTrue(info.isNonOrganic)
        assertEquals("adjust_reftag", info.paidClickKey)
    }

    @Test
    fun `gclid only without utm uses click key as network`() {
        val info = InstallReferrerInfo("gclid=abc123")
        assertTrue(info.isNonOrganic)
        assertEquals("referrer:gclid", info.networkName)
    }

    @Test
    fun `custom utm_source without organic medium is non organic`() {
        assertTrue(InstallReferrerInfo("utm_source=facebook&utm_medium=paid").isNonOrganic)
        assertTrue(InstallReferrerInfo("utm_source=tiktok").isNonOrganic)
    }

    @Test
    fun `google-play source without medium is treated as organic`() {
        assertFalse(InstallReferrerInfo("utm_source=google-play").isNonOrganic)
    }

    @Test
    fun `single encoded referrer is decoded`() {
        val info = InstallReferrerInfo("gclid%3Dabc%26utm_source%3Dgoogle%26utm_medium%3Dcpc")
        assertTrue(info.isNonOrganic)
        assertEquals("google", info.utmSource)
    }

    @Test
    fun `keys are case insensitive`() {
        val info = InstallReferrerInfo("UTM_Source=Google&UTM_Medium=CPC")
        assertTrue(info.isNonOrganic)
        assertEquals("Google", info.utmSource)
    }
}
