package com.ntt.crosstest.data

import androidx.work.WorkInfo

internal data class ReportWorkState(val stage: ReportSubmitManager.Stage, val attempt: Int)

/** WorkInfo counts started runs; WorkerParameters counts runs before this one. */
internal fun reportWorkState(state: WorkInfo.State, runAttemptCount: Int): ReportWorkState {
    val waiting = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED
    val attempt = if (waiting) runAttemptCount + 1 else runAttemptCount
    val stage = when (state) {
        WorkInfo.State.SUCCEEDED -> ReportSubmitManager.Stage.DONE
        WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> ReportSubmitManager.Stage.FAILED
        else -> if (attempt > 1) ReportSubmitManager.Stage.RETRYING else ReportSubmitManager.Stage.SENDING
    }
    return ReportWorkState(stage, attempt.coerceAtLeast(1))
}
