package com.ntt.crosstest.work

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.ntt.crosstest.data.*
import com.ntt.crosstest.util.PreparedScreenshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ReportBackgroundSubmissionTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { PendingReportStore.clear(context) }
    @After fun cleanup() { PendingReportStore.clear(context) }

    private class Operations : ReportUploadOperations {
        override val isReady = true
        override var currentUserId: String? = "tester"
        var awaitSessionAction: suspend () -> Unit = {}
        var uploadAction: suspend () -> String = { "https://fixture.invalid/full.jpg" }
        var submitAction: suspend () -> Unit = {}
        var uploads = 0
        var submissions = 0
        val requestIds = mutableListOf<String>()
        override suspend fun awaitSession() = awaitSessionAction()
        override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String {
            uploads++
            return uploadAction()
        }
        override suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult {
            submissions++
            requestIds += request.id
            submitAction()
            return SubmitTesterReportResult(success = true, reportId = "server-report")
        }
    }

    private fun persist() {
        val file = PendingReportStore.newScreenshotFile(context, "background").apply { writeText("image") }
        PendingReportStore.write(context, PendingReport("background", "campaign", "app", "tester", 5, "tested",
            screenshotPath = file.path, imageHash = "0123456789abcdef", needsJoin = true,
            screenshotSha256 = PendingReportStore.screenshotSha256(file)))
    }

    private fun worker(operations: Operations, attempt: Int = 0) = TestListenableWorkerBuilder<ReportUploadWorker>(context)
        .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to "background"))
        .setRunAttemptCount(attempt)
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                ReportUploadWorker(appContext, workerParameters, operations)
        }).build()

    @Test fun stalledUploadIsCancelledAndRetriesWithoutDiscardingEvidence() = runTest {
        persist()
        var cancelled = false
        val ops = Operations().apply {
            uploadAction = { try { awaitCancellation() } finally { cancelled = true } }
        }
        val job = async { worker(ops).doWork() }
        runCurrent()
        advanceTimeBy(ReportUploadWorker.UPLOAD_TIMEOUT_MS)
        runCurrent()
        assertTrue(job.await() is ListenableWorker.Result.Retry)
        assertTrue(cancelled)
        assertEquals(0, ops.submissions)
        val pending = PendingReportStore.read(context, "background")!!
        assertTrue(java.io.File(pending.screenshotPath!!).isFile)
        assertNull(pending.completedResult)
        ops.uploadAction = { "https://fixture.invalid/full.jpg" }
        assertTrue(worker(ops, 1).doWork() is ListenableWorker.Result.Success)
        assertEquals(listOf("background"), ops.requestIds)
    }

    @Test fun stalledRpcRetriesSameIdAndReusesUploadedImage() = runTest {
        persist()
        var cancelled = false
        val ops = Operations().apply {
            submitAction = { try { awaitCancellation() } finally { cancelled = true } }
        }
        val job = async { worker(ops).doWork() }
        runCurrent()
        advanceTimeBy(ReportUploadWorker.RPC_TIMEOUT_MS)
        runCurrent()
        assertTrue(job.await() is ListenableWorker.Result.Retry)
        assertTrue(cancelled)
        assertNotNull(PendingReportStore.read(context, "background")!!.uploadedUrl)
        ops.submitAction = {}
        assertTrue(worker(ops, 1).doWork() is ListenableWorker.Result.Success)
        assertEquals(1, ops.uploads)
        assertEquals(listOf("background", "background"), ops.requestIds)
    }

    @Test fun coldStartWaitsForRestoredSessionBeforeUploading() = runTest {
        persist()
        val ready = CompletableDeferred<Unit>()
        val ops = Operations().apply {
            currentUserId = null
            awaitSessionAction = { ready.await(); currentUserId = "tester" }
        }
        val job = async { worker(ops).doWork() }
        runCurrent()
        assertEquals(0, ops.uploads)
        ready.complete(Unit)
        runCurrent()
        assertTrue(job.await() is ListenableWorker.Result.Success)
        assertEquals(1, ops.submissions)
    }

    @Test fun parentCancellationDuringRpcDoesNotBecomeNetworkRetry() = runTest {
        persist()
        val ops = Operations().apply { submitAction = { awaitCancellation() } }
        val job = async { withTimeout(1_000) { worker(ops).doWork() } }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        try { job.await(); fail("Worker cancellation must propagate") } catch (_: TimeoutCancellationException) {}
        assertNull(PendingReportStore.read(context, "background")!!.completedResult)
        assertNotNull(PendingReportStore.read(context, "background")!!.uploadedUrl)
    }

    @Test fun sessionTimeoutRetriesWithoutUploadingOrSubmitting() = runTest {
        persist()
        val ops = Operations().apply { awaitSessionAction = { awaitCancellation() } }
        val job = async { worker(ops).doWork() }
        runCurrent()
        advanceTimeBy(ReportUploadWorker.SESSION_TIMEOUT_MS)
        runCurrent()
        assertTrue(job.await() is ListenableWorker.Result.Retry)
        assertEquals(0, ops.uploads)
        assertEquals(0, ops.submissions)
    }

    @Test fun expeditedWorkerSuppliesForegroundNotificationOnOlderAndroid() = runTest {
        val foreground = worker(Operations()).getForegroundInfo()
        assertNotNull(foreground.notification)
        assertNotEquals(0, foreground.notification.smallIcon.resId)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(foreground.notification.channelId).importance)
    }
}
