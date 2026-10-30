package com.xjb.medicalquestionai

import com.xjb.medicalquestionai.data.update.DailyUpdateCheckPolicy
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyUpdateCheckPolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun day(instant: String) = DailyUpdateCheckPolicy.dayKey(Instant.parse(instant).toEpochMilli(), zone)

    @Test fun firstEntryChecksAndRepeatedEntryDoesNot() {
        val today = day("2026-10-30T06:00:00Z")
        assertTrue(DailyUpdateCheckPolicy.isDue(null, today))
        assertFalse(DailyUpdateCheckPolicy.isDue(today, day("2026-10-30T15:59:59Z")))
    }

    @Test fun localMidnightChecksAgainWithoutWaitingTwentyFourHours() {
        val yesterday = day("2026-10-30T15:59:59Z")
        val today = day("2026-10-30T16:00:00Z")
        assertEquals("2026-10-30", yesterday)
        assertEquals("2026-10-31", today)
        assertTrue(DailyUpdateCheckPolicy.isDue(yesterday, today))
    }

    @Test fun yearBoundaryAndFutureStoredDateDoNotBlockChecks() {
        assertTrue(DailyUpdateCheckPolicy.isDue(day("2026-12-31T15:59:59Z"), day("2026-12-31T16:00:00Z")))
        assertTrue(DailyUpdateCheckPolicy.isDue("2026-11-01", "2026-10-30"))
    }

    @Test fun offlineFirstEntryWaitsForNetworkThenOnlyOneRecovery() {
        val today = "2026-10-30"
        assertEquals(DailyUpdateCheckPolicy.Attempt.FIRST,
            DailyUpdateCheckPolicy.nextAttempt(null, null, null, today, false))
        assertEquals(null, DailyUpdateCheckPolicy.nextAttempt(today, today, null, today, false))
        assertEquals(DailyUpdateCheckPolicy.Attempt.NETWORK_RECOVERY,
            DailyUpdateCheckPolicy.nextAttempt(today, today, null, today, true))
        // Restoring persisted preferences after a process restart must not reset the allowance.
        assertEquals(null, DailyUpdateCheckPolicy.nextAttempt(today, today, today, today, true))
    }

    @Test fun ordinaryFailureDoesNotRetryAndNextDayGetsFreshAllowance() {
        assertEquals(null, DailyUpdateCheckPolicy.nextAttempt("2026-10-30", null, null, "2026-10-30", true))
        assertEquals(DailyUpdateCheckPolicy.Attempt.FIRST,
            DailyUpdateCheckPolicy.nextAttempt("2026-10-30", "2026-10-30", "2026-10-30", "2026-10-31", true))
    }

    @Test fun ignoredVersionOnlySuppressesItsOwnAutomaticPrompt() {
        assertFalse(DailyUpdateCheckPolicy.shouldPrompt(10, 10, null))
        assertTrue(DailyUpdateCheckPolicy.shouldPrompt(11, 10, null))
        assertFalse(DailyUpdateCheckPolicy.shouldPrompt(11, 10, 11))
        assertTrue(DailyUpdateCheckPolicy.shouldPrompt(12, 10, 11))
    }
}
