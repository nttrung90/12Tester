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
        // Room is a runtime dependency of WorkManager, not on this module's
        // compile classpath. Read the persisted scheduling policy reflectively.
        val database = wm.javaClass.getMethod("getWorkDatabase").invoke(wm)
        val dao = database.javaClass.getMethod("workSpecDao").invoke(database)
        val spec = dao.javaClass.getMethod("getWorkSpec", String::class.java)
            .invoke(dao, work.id.toString()) as androidx.work.impl.model.WorkSpec
        assertTrue("User submission must be persisted as expedited work", spec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, spec.outOfQuotaPolicy)
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

    @Test fun restartRestoresOrphanedRequestAndIgnoresOtherAccounts() = runBlocking {
        PendingReportStore.write(context, PendingReport("orphan", "campaign", "app", "user", 5, "test"))
        PendingReportStore.write(context, PendingReport("foreign", "other", "app", "other-user", 5, "private"))
        manager.restorePendingReports("user")
        manager.restorePendingReports("user")
        val wm = WorkManager.getInstance(context)
        until { wm.getWorkInfosForUniqueWork("report_submit_orphan").get().isNotEmpty() }
        val infos = wm.getWorkInfosForUniqueWork("report_submit_orphan").get()
        assertEquals(1, infos.size)
        assertEquals("orphan", manager.state.value?.requestId)
        assertTrue(wm.getWorkInfosForUniqueWork("report_submit_foreign").get().isEmpty())
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(infos.single().id)
        gate.complete(ListenableWorker.Result.success(workDataOf(ReportUploadWorker.KEY_REPORT_ID to "restored")))
        until { manager.state.value?.stage == ReportSubmitManager.Stage.DONE }
        assertEquals("restored", manager.state.value?.result?.reportId)
    }

    @Test fun concurrentCampaignDoesNotAllowDuplicateOfEarlierPendingReport() = runBlocking {
        fun request(id: String, campaign: String) = ReportSubmitManager.SubmitRequest(
            campaign, "app", "user", 5, "test", null, null, null, false, id)
        manager.submit(request("first", "campaign-a"))
        manager.submit(request("second", "campaign-b"))
        manager.submit(request("duplicate", "campaign-a"))
        val wm = WorkManager.getInstance(context)
        until { wm.getWorkInfosForUniqueWork("report_submit_second").get().isNotEmpty() }
        assertNull(PendingReportStore.read(context, "duplicate"))
        assertTrue(wm.getWorkInfosForUniqueWork("report_submit_duplicate").get().isEmpty())
        assertEquals("second", manager.state.value?.requestId)
        assertEquals("first", manager.currentStateForCampaign("campaign-a")?.requestId)
        assertEquals("second", manager.currentStateForCampaign("campaign-b")?.requestId)
    }

    @Test fun restartReattachesToExistingWorkWithoutReplacingIt() = runBlocking {
        PendingReportStore.write(context, PendingReport("existing", "campaign", "app", "user", 5, "test"))
        val wm = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<ReportUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(ReportSubmitManager.TAG)
            .build()
        wm.enqueueUniqueWork("report_submit_existing", ExistingWorkPolicy.KEEP, request).result.get()
        manager.restorePendingReports("user")
        until { manager.state.value?.requestId == "existing" }
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)
        gate.complete(ListenableWorker.Result.success(workDataOf(ReportUploadWorker.KEY_REPORT_ID to "existing-server")))
        until { manager.state.value?.stage == ReportSubmitManager.Stage.DONE }
        assertEquals(listOf(request.id), wm.getWorkInfosForUniqueWork("report_submit_existing").get().map { it.id })
    }

    @Test fun eachCampaignKeepsItsOwnFailureAndRetryTargetsThatCampaign() = runBlocking {
        fun request(id: String, campaign: String) = ReportSubmitManager.SubmitRequest(
            campaign, "app", "user", 5, "test", null, null, null, false, id)
        manager.submit(request("a", "campaign-a"))
        manager.submit(request("b", "campaign-b"))
        val wm = WorkManager.getInstance(context)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        until { wm.getWorkInfosForUniqueWork("report_submit_b").get().isNotEmpty() &&
            wm.getWorkInfosForUniqueWork("report_submit_a").get().isNotEmpty() }
        for (id in listOf("a", "b")) {
            driver.setAllConstraintsMet(wm.getWorkInfosForUniqueWork("report_submit_$id").get().single().id)
        }
        gate.complete(ListenableWorker.Result.failure(workDataOf(ReportUploadWorker.KEY_ERROR to "network failure")))
        until { listOf("campaign-a", "campaign-b").all {
            manager.currentStateForCampaign(it)?.stage == ReportSubmitManager.Stage.FAILED } }
        gate = CompletableDeferred()
        manager.retry("campaign-a")
        until { wm.getWorkInfosForUniqueWork("report_submit_a").get().any { !it.state.isFinished } }
        val retry = wm.getWorkInfosForUniqueWork("report_submit_a").get().first { !it.state.isFinished }
        driver.setAllConstraintsMet(retry.id)
        gate.complete(ListenableWorker.Result.success(workDataOf(ReportUploadWorker.KEY_REPORT_ID to "retried-a")))
        until { manager.currentStateForCampaign("campaign-a")?.stage == ReportSubmitManager.Stage.DONE }
        assertEquals("retried-a", manager.currentStateForCampaign("campaign-a")?.result?.reportId)
        assertEquals(ReportSubmitManager.Stage.FAILED, manager.currentStateForCampaign("campaign-b")?.stage)
        assertEquals("a", manager.state.value?.requestId)
        assertNotNull(PendingReportStore.read(context, "b"))
    }
}
