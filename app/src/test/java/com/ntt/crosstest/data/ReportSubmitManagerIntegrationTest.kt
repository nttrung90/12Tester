package com.ntt.crosstest.data

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.work.ReportUploadWorker
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ReportSubmitManagerIntegrationTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var manager: ReportSubmitManager
    private lateinit var gate: CompletableDeferred<ListenableWorker.Result>

    private class GateWorker(context: Context, params: WorkerParameters, val gate: CompletableDeferred<Result>) :
        CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = gate.await()
    }

    @Before fun setup() {
        PendingReportStore.clear(context)
        gate = CompletableDeferred()
        val config = Configuration.Builder().setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == ReportUploadWorker::class.java.name) GateWorker(appContext, workerParameters, gate) else null
        }).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        manager = ReportSubmitManager(context)
    }
    @After fun cleanup() = runBlocking {
        manager.cancelAllPendingWork()
        gate.cancel()
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
        PendingReportStore.clear(context)
        WorkManagerTestInitHelper.closeWorkDatabase()
    }
    private suspend fun until(predicate: () -> Boolean) {
        withTimeout(10_000) {
            while (!predicate()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(20)
            }
        }
    }

    @Test fun realWorkManagerFirstRunAndCompletionStayAtAttemptOne() = runBlocking {
        manager.submit(ReportSubmitManager.SubmitRequest("campaign", "app", "user", 5, "test", null, null, null, false, "request"))
        val wm = WorkManager.getInstance(context)
        until { wm.getWorkInfosForUniqueWork("report_submit_request").get().isNotEmpty() }
        val work = wm.getWorkInfosForUniqueWork("report_submit_request").get().first()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work.id)
        until { wm.getWorkInfoById(work.id).get()?.state == WorkInfo.State.RUNNING }
        // Leave it running long enough for the production polling observer to consume RUNNING.
        delay(700)
        assertEquals(1, wm.getWorkInfoById(work.id).get()!!.runAttemptCount)
        assertEquals(ReportSubmitManager.Stage.SENDING, manager.state.value!!.stage)
        assertEquals(1, manager.state.value!!.attempt)
        PendingReportStore.markCompleted(context, "request", SubmitTesterReportResult(success = true, reportId = "server-id"))
        manager.reconcileCompletedReports()
        assertNotNull(PendingReportStore.read(context, "request"))
        gate.complete(ListenableWorker.Result.success(workDataOf(
            ReportUploadWorker.KEY_SUCCESS to true, ReportUploadWorker.KEY_REPORT_ID to "server-id"
        )))
        until { manager.state.value?.stage == ReportSubmitManager.Stage.DONE }
        assertEquals(1, manager.state.value!!.attempt)
        until { PendingReportStore.read(context, "request") == null }
    }

    @Test fun startupReconciliationOnlyDeletesReceiptsAfterCommittedSuccess() = runBlocking {
        val result = SubmitTesterReportResult(success = true, reportId = "server-id")
        PendingReportStore.write(context, PendingReport("receipt", "campaign", "app", "user", 5, "test", completedResult = result))
        // No WorkInfo means it has already been pruned. The durable receipt is authoritative.
        manager.reconcileCompletedReports()
        assertNull(PendingReportStore.read(context, "receipt"))
        PendingReportStore.write(context, PendingReport("unfinished", "campaign", "app", "user", 5, "test"))
        manager.reconcileCompletedReports()
        assertNotNull(PendingReportStore.read(context, "unfinished"))
    }
}
