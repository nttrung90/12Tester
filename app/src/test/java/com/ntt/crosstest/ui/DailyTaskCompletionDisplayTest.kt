package com.ntt.crosstest.ui

import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.DailyTaskAction
import com.ntt.crosstest.data.DailyTaskActionGate
import com.ntt.crosstest.data.DailyTaskStatus
import com.ntt.crosstest.data.UserNotification
import com.ntt.crosstest.ui.components.dailyTaskCompletionForDisplay
import com.ntt.crosstest.ui.viewmodels.forCurrentUi
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyTaskCompletionDisplayTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val complete = DailyTaskStatus(statusDate = "2026-11-15", mode = "owner", met = true, effectiveRequiredTests = 3)
    @Test fun verifiedCompletionIsDisplayed() {
        assertTrue(dailyTaskCompletionForDisplay(true, false, complete, false, false))
    }
    @Test fun staleCompletionCannotOverrideRefresh() {
        assertFalse(dailyTaskCompletionForDisplay(true, false, complete, true, false))
    }
    @Test fun missingSnapshotCannotConfirmCompletion() {
        assertFalse(dailyTaskCompletionForDisplay(true, false, null, false, false))
    }
    @Test fun unknownReadinessCannotConfirmCompletion() {
        assertFalse(dailyTaskCompletionForDisplay(true, false, complete.copy(rawHistoryReady = null), false, false))
    }
    @Test fun pendingHistoryCannotConfirmCompletion() {
        assertFalse(dailyTaskCompletionForDisplay(true, false, complete.copy(historyReady = false), false, false))
    }
    @Test fun failedRefreshCannotConfirmCompletion() {
        assertFalse(dailyTaskCompletionForDisplay(true, false, complete, false, true))
    }
    @Test fun owner12Of12CompletionIsDisplayedIncludingWithPreservedDebt() {
        val owner12 = DailyTaskStatus(
            statusDate = "2026-11-15",
            mode = "owner",
            effectiveRequiredTests = 12,
            completedTests = 12,
            carryInTests = 3,
            carryoverRemainingTests = 3,
            carryoverReady = true,
            historyReady = true,
            met = true,
        )
        assertTrue(dailyTaskCompletionForDisplay(true, false, owner12, false, false))
    }
    @Test fun memberForCurrentUiUpdatesRawMetAndRoundTripsThroughSerializer() {
        val wire = """{"status_date":"2026-11-15","mode":"member","logged_in_today":false,"met":false,"history_ready":true,"carryover_ready":true,"carryover_remaining_tests":0}"""
        val decoded = json.decodeFromString(DailyTaskStatus.serializer(), wire)
        val uiTask = decoded.forCurrentUi("2026-11-15")!!
        assertTrue(uiTask.loggedInToday)
        assertTrue(uiTask.met)
        assertEquals(true, uiTask.rawMet)
        assertTrue(dailyTaskCompletionForDisplay(true, false, uiTask, false, false))

        val campaign = Campaign(id = "c1", ownerId = "u1", appName = "App", packageName = "com.app", status = "open")
        assertTrue(DailyTaskActionGate.evaluate(DailyTaskAction.DELETE, campaign, uiTask, todayVn = "2026-11-15").isAllowed)

        val persisted = json.encodeToString(DailyTaskStatus.serializer(), uiTask)
        val restored = json.decodeFromString(DailyTaskStatus.serializer(), persisted)
        assertTrue(restored.met)
        assertEquals(true, restored.rawMet)
        assertTrue(dailyTaskCompletionForDisplay(true, false, restored, false, false))
    }
    @Test fun memberForCurrentUiPreservesMissingRawMetWhenOmitted() {
        val wire = """{"status_date":"2026-11-15","mode":"member","logged_in_today":false,"history_ready":true,"carryover_ready":true,"carryover_remaining_tests":0}"""
        val decoded = json.decodeFromString(DailyTaskStatus.serializer(), wire)
        val uiTask = decoded.forCurrentUi("2026-11-15")!!
        assertNull(uiTask.rawMet)
        assertFalse(dailyTaskCompletionForDisplay(true, false, uiTask, false, false))
    }
    @Test fun userNotificationIsCreditIncludesPenalty() {
        assertTrue(UserNotification(id = "n1", type = "credit").isCredit)
        assertTrue(UserNotification(id = "n2", type = "penalty").isCredit)
        assertFalse(UserNotification(id = "n3", type = "warning").isCredit)
    }
}
