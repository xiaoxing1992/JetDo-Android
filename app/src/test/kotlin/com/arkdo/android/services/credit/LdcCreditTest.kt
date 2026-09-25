package com.arkdo.android.services.credit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LdcCreditParserTest {

    @Test
    fun parseUserInfoKeepsAmountsAsStringsAndConvertsDailyLimit() {
        val body = """{"error_msg":"","data":{"username":"neo","nickname":"Neo","trust_level":3,"available_balance":"1395.01","community_balance":"16","pending_balance":"0.50","remain_quota":"8.00","daily_limit":20,"total_receive":"100","total_payment":"1"}}"""

        val info = LdcCreditParser.parseUserInfo(body)!!

        assertEquals("neo", info.username)
        assertEquals("Neo", info.nickname)
        assertEquals(3, info.trustLevel)
        assertEquals("1395.01", info.availableBalance)
        assertEquals("16", info.communityBalance)
        assertEquals("0.50", info.pendingBalance)
        assertEquals("8.00", info.remainQuota)
        assertEquals("20", info.dailyLimit)
        assertEquals("100", info.totalReceive)
        assertEquals("1", info.totalPayment)
    }

    @Test
    fun parseUserInfoReturnsNullWhenLoggedOut() {
        assertNull(LdcCreditParser.parseUserInfo("""{"error_msg":"unauthorized"}"""))
        assertNull(LdcCreditParser.parseUserInfo("not json"))
        assertNull(LdcCreditParser.parseUserInfo(null))
    }

    @Test
    fun parseUserInfoDefaultsMissingAmountsToZero() {
        val info = LdcCreditParser.parseUserInfo("""{"data":{}}""")!!

        assertEquals("", info.username)
        assertEquals(0, info.trustLevel)
        assertEquals("0", info.availableBalance)
        assertEquals("", info.dailyLimit)
    }

    @Test
    fun parseDailyStatsSkipsBlankDatesAndKeepsEmptyList() {
        val body = """{"data":[{"date":"2026-07-29","income":"16.00","expense":"1"},{"date":"","income":"9"},{"income":"3"}]}"""

        val stats = LdcCreditParser.parseDailyStats(body)!!

        assertEquals(1, stats.size)
        assertEquals("2026-07-29", stats.single().date)
        assertEquals("16.00", stats.single().income)
        assertEquals("1", stats.single().expense)
        assertTrue(LdcCreditParser.parseDailyStats("""{"data":[]}""")!!.isEmpty())
    }

    @Test
    fun parseDailyStatsReturnsNullWhenDataMissing() {
        assertNull(LdcCreditParser.parseDailyStats("""{"error_msg":"no"}"""))
        assertNull(LdcCreditParser.parseDailyStats("""{"data":{}}"""))
    }
}

class LdcFormatTest {

    @Test
    fun formatLdcAmountTrimsTrailingZerosWithoutTouchingSignificantDigits() {
        assertEquals("1395.01", formatLdcAmount("1395.01"))
        assertEquals("16", formatLdcAmount("16"))
        assertEquals("16", formatLdcAmount("16.00"))
        assertEquals("0.5", formatLdcAmount("0.50"))
        assertEquals("0", formatLdcAmount("0.0"))
        assertEquals("0", formatLdcAmount("  "))
        assertEquals("not-a-number", formatLdcAmount("not-a-number"))
    }

    @Test
    fun ldcAmountValueAndDayLabel() {
        assertEquals(1395.01, ldcAmountValue("1395.01"), 0.0)
        assertEquals(0.0, ldcAmountValue("oops"), 0.0)
        assertEquals("07-29", ldcDayLabel("2026-07-29"))
        assertEquals("raw", ldcDayLabel("raw"))
    }
}

class LdcCreditSessionTest {

    @Test
    fun cacheExpiresAfterFiveMinutes() {
        LdcCreditSession.clearCache()
        val info = com.arkdo.android.data.models.LdcUserInfo(availableBalance = "9")
        LdcCreditSession.setCachedInfo(info, now = 1_000L)

        assertEquals("9", LdcCreditSession.getCachedInfo()?.availableBalance)
        assertEquals(true, LdcCreditSession.isCacheFresh(now = 1_000L + 5 * 60 * 1000L - 1))
        assertEquals(false, LdcCreditSession.isCacheFresh(now = 1_000L + 5 * 60 * 1000L))

        LdcCreditSession.clearCache()
        assertEquals(null, LdcCreditSession.getCachedInfo())
        assertEquals(false, LdcCreditSession.isCacheFresh(now = 2_000L))
    }
}

class LdcEntryReducerTest {

    @Test
    fun freshCacheSkipsAnotherFetch() {
        val info = com.arkdo.android.data.models.LdcUserInfo(availableBalance = "12.5")
        val next = reduceLdcEntry(LdcEntryState(), LdcEntryEvent.Refresh(hasSession = true, cached = info, cacheFresh = true))

        assertEquals(LdcEntryPhase.READY, next.phase)
        assertEquals("12.5", next.info?.availableBalance)
        assertEquals(false, next.hostActive)
    }

    @Test
    fun staleSessionKeepsLastBalanceWhileFetching() {
        val stale = com.arkdo.android.data.models.LdcUserInfo(availableBalance = "3")
        val next = reduceLdcEntry(LdcEntryState(), LdcEntryEvent.Refresh(hasSession = true, cached = stale, cacheFresh = false))

        assertEquals(LdcEntryPhase.LOADING, next.phase)
        assertEquals("3", next.info?.availableBalance)
        assertEquals(true, next.hostActive)
    }

    @Test
    fun missingSessionShowsLoginInsteadOfRetry() {
        val leftover = com.arkdo.android.data.models.LdcUserInfo(availableBalance = "3")
        val next = reduceLdcEntry(
            LdcEntryState(phase = LdcEntryPhase.READY, info = leftover),
            LdcEntryEvent.Refresh(hasSession = false, cached = leftover, cacheFresh = true)
        )

        assertEquals(LdcEntryPhase.ANON, next.phase)
        assertEquals(null, next.info)
        assertEquals(false, next.hostActive)
    }

    @Test
    fun loggedOutClearsTheRow() {
        val next = reduceLdcEntry(
            LdcEntryState(phase = LdcEntryPhase.READY, info = com.arkdo.android.data.models.LdcUserInfo(availableBalance = "1")),
            LdcEntryEvent.Reset
        )

        assertEquals(LdcEntryPhase.LOADING, next.phase)
        assertEquals(null, next.info)
        assertEquals(false, next.hostActive)
    }

    @Test
    fun failedFetchKeepsCachedBalanceInsteadOfRetry() {
        val shown = com.arkdo.android.data.models.LdcUserInfo(availableBalance = "8")
        val next = reduceLdcEntry(
            LdcEntryState(phase = LdcEntryPhase.LOADING, info = shown, hostActive = true),
            LdcEntryEvent.LoadFailed
        )

        assertEquals(LdcEntryPhase.READY, next.phase)
        assertEquals("8", next.info?.availableBalance)
        assertEquals(false, next.hostActive)
    }

    @Test
    fun failedFetchWithoutCacheBecomesRetry() {
        val next = reduceLdcEntry(
            LdcEntryState(phase = LdcEntryPhase.LOADING, hostActive = true),
            LdcEntryEvent.LoadFailed
        )

        assertEquals(LdcEntryPhase.ERROR, next.phase)
        assertEquals(false, next.hostActive)
    }

    @Test
    fun unauthorizedClearsBalanceAndShowsLogin() {
        val next = reduceLdcEntry(
            LdcEntryState(phase = LdcEntryPhase.LOADING, info = com.arkdo.android.data.models.LdcUserInfo(availableBalance = "8"), hostActive = true),
            LdcEntryEvent.Unauthorized
        )

        assertEquals(LdcEntryPhase.ANON, next.phase)
        assertEquals(null, next.info)
        assertEquals(false, next.hostActive)
    }

    @Test
    fun loadedBalanceStopsTheHost() {
        val info = com.arkdo.android.data.models.LdcUserInfo(availableBalance = "20")
        val next = reduceLdcEntry(
            LdcEntryState(phase = LdcEntryPhase.LOADING, hostActive = true),
            LdcEntryEvent.Loaded(info)
        )

        assertEquals(LdcEntryPhase.READY, next.phase)
        assertEquals("20", next.info?.availableBalance)
        assertEquals(false, next.hostActive)
    }
}
