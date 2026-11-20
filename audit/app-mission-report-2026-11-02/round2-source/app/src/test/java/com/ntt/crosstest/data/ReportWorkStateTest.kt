package com.ntt.crosstest.data

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportWorkStateTest {
    @Test fun firstRunningAttemptDoesNotClaimRetry() {
        assertEquals(ReportWorkState(ReportSubmitManager.Stage.SENDING, 1), reportWorkState(WorkInfo.State.RUNNING, 1))
    }
    @Test fun secondRunningAttemptIsTwo() {
        assertEquals(ReportWorkState(ReportSubmitManager.Stage.RETRYING, 2), reportWorkState(WorkInfo.State.RUNNING, 2))
    }
    @Test fun enqueuedInitiallyAndAfterRetryUseNextAttempt() {
        assertEquals(ReportWorkState(ReportSubmitManager.Stage.SENDING, 1), reportWorkState(WorkInfo.State.ENQUEUED, 0))
        assertEquals(ReportWorkState(ReportSubmitManager.Stage.RETRYING, 2), reportWorkState(WorkInfo.State.ENQUEUED, 1))
    }
    @Test fun terminalCountsNeverAdvanceToTheNextAttempt() {
        for (state in listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)) {
            assertEquals(1, reportWorkState(state, 1).attempt)
            assertEquals(3, reportWorkState(state, 3).attempt)
        }
    }
}
