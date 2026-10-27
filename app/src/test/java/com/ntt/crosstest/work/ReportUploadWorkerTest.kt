package com.ntt.crosstest.work

import android.app.Application
import android.content.Context
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.ntt.crosstest.data.*
import com.ntt.crosstest.util.PreparedScreenshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReportUploadWorkerTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { PendingReportStore.clear(context) }
    @After fun cleanup() { PendingReportStore.clear(context) }

    private class Operations : ReportUploadOperations {
        override var isReady = true
        override var currentUserId: String? = "user"
        var uploads = 0
        var submissions = 0
        var onUpload: () -> Unit = {}
        var onJoin: () -> Unit = {}
        var onSubmit: () -> Unit = {}
        var result = SubmitTesterReportResult(success = true, reportId = "server-id", provisionalGranted = true, provisionalAmount = 10)
        override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String {
            uploads++; onUpload(); return "https://fixture.invalid/image"
        }
        override suspend fun join(campaignId: String) { onJoin() }
        override suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult {
            submissions++; onSubmit(); return result
        }
    }
    private fun persist(image: Boolean = false, needsJoin: Boolean = false): PendingReport {
        val file = if (image) PendingReportStore.newScreenshotFile(context, "request").apply { writeText("image-bytes") } else null
        return PendingReport("request", "campaign", "app", "user", 5, "feedback",
            screenshotPath = file?.path, imageHash = if (image) "0000000000000000" else null,
            needsJoin = needsJoin, screenshotSha256 = file?.let(PendingReportStore::screenshotSha256)
        ).also { PendingReportStore.write(context, it) }
    }
    private fun worker(ops: Operations, attempt: Int = 0): ReportUploadWorker =
        TestListenableWorkerBuilder<ReportUploadWorker>(context)
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to "request"))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    ReportUploadWorker(appContext, workerParameters, ops)
            }).build()

    @Test fun restartAfterReceiptDoesNotSubmitAgainAndWorksOffline() = runBlocking {
        persist(image = true)
        val ops = Operations()
        val first = worker(ops).doWork()
        assertTrue(first is ListenableWorker.Result.Success)
        assertNotNull("receipt JSON: " + java.io.File(PendingReportStore.directory(context), "request.json").readText(),
            PendingReportStore.read(context, "request")!!.completedResult)
        ops.isReady = false
        // Discard first success: WorkManager has not committed it before the simulated restart.
        val replay = worker(ops, 1).doWork()
        assertEquals(first, replay)
        assertEquals(1, ops.uploads)
        assertEquals(1, ops.submissions)
        assertFalse(PendingReportStore.pendingCampaignIds(context).contains("campaign"))
    }
    @Test fun lostRpcResponseRetriesSameRequestAndSkipsSavedUpload() = runBlocking {
        persist(image = true)
        val ops = Operations()
        var credited = false
        var credits = 0
        ops.onSubmit = {
            if (!credited) { credited = true; credits += 10; throw IOException("response lost after commit") }
            ops.result = ops.result.copy(provisionalGranted = false, provisionalAmount = 0)
        }
        assertTrue(worker(ops).doWork() is ListenableWorker.Result.Retry)
        assertNotNull("uploaded URL JSON: " + java.io.File(PendingReportStore.directory(context), "request.json").readText(),
            PendingReportStore.read(context, "request")!!.uploadedUrl)
        assertNull(PendingReportStore.read(context, "request")!!.completedResult)
        assertTrue(worker(ops, 1).doWork() is ListenableWorker.Result.Success)
        assertEquals(1, ops.uploads)
        assertEquals(2, ops.submissions)
        assertEquals(10, credits)
        assertEquals("request", PendingReportStore.read(context, "request")!!.id)
        assertEquals(0, PendingReportStore.read(context, "request")!!.completedResult!!.provisionalAmount)
    }
    @Test fun checksumRetriesWithoutChangingBytesHashOrId() = runBlocking {
        val original = persist(image = true)
        val ops = Operations()
        ops.onUpload = {
            if (ops.uploads == 1) throw com.google.firebase.storage.StorageException.fromExceptionAndHttpCode(null, 409)!!
        }
        assertTrue(worker(ops).doWork() is ListenableWorker.Result.Retry)
        assertEquals(original, PendingReportStore.read(context, "request"))
        assertTrue(worker(ops, 1).doWork() is ListenableWorker.Result.Success)
        assertEquals(2, ops.uploads)
        assertEquals(1, ops.submissions)
    }
    @Test fun changedFileFailsBeforeAnyNetworkOperation() = runBlocking {
        val report = persist(image = true)
        java.io.File(report.screenshotPath!!).writeText("corruption")
        val ops = Operations()
        assertTrue(worker(ops).doWork() is ListenableWorker.Result.Failure)
        assertEquals(0, ops.uploads)
        assertEquals(0, ops.submissions)
    }
    @Test fun cancellationDuringJoinIsNeverSwallowed() = runBlocking {
        persist(needsJoin = true)
        val ops = Operations().apply { onJoin = { throw CancellationException("cancelled") } }
        try { worker(ops).doWork(); fail() } catch (_: CancellationException) {}
        assertEquals(0, ops.submissions)
        assertNull(PendingReportStore.read(context, "request")!!.completedResult)
    }
    @Test fun accountChangeAfterUploadCannotSubmitForAnotherUser() = runBlocking {
        persist(image = true)
        val ops = Operations().apply { onUpload = { currentUserId = "other-user" } }
        assertTrue(worker(ops).doWork() is ListenableWorker.Result.Failure)
        assertEquals(0, ops.submissions)
    }
    @Test fun finalAttemptStopsAndKeepsPendingForManualRetry() = runBlocking {
        persist()
        val ops = Operations().apply { onSubmit = { throw IOException("offline") } }
        assertTrue(worker(ops, 2).doWork() is ListenableWorker.Result.Failure)
        assertNotNull(PendingReportStore.read(context, "request"))
    }
}
