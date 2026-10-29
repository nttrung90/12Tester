package com.ntt.crosstest.work

import android.app.Application
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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReportSubmissionLatencyTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { PendingReportStore.clear(context) }
    @After fun cleanup() { PendingReportStore.clear(context) }

    private fun persist(needsJoin: Boolean = false) {
        val full = PendingReportStore.newScreenshotFile(context, "latency").apply { writeText("full") }
        val thumb = PendingReportStore.newThumbnailFile(context, "latency", "jpg").apply { writeText("thumb") }
        PendingReportStore.write(context, PendingReport("latency", "campaign", "app", "tester", 5, "tested",
            screenshotPath = full.path, thumbnailPath = thumb.path, imageHash = "0123456789abcdef", needsJoin = needsJoin,
            screenshotSha256 = PendingReportStore.screenshotSha256(full)))
    }

    private class Operations : ReportUploadOperations {
        override val isReady = true
        override val currentUserId = "tester"
        var fullUploads = 0
        var submissions = 0
        var joins = 0
        var thumbStarted = false
        var thumbCancelled = false
        override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String {
            fullUploads++
            return "https://fixture.invalid/full.jpg"
        }
        override suspend fun uploadThumbnail(thumbnailFile: File, campaignId: String, userId: String, uploadId: String): String? {
            thumbStarted = true
            try { awaitCancellation() } finally { thumbCancelled = true }
        }
        override suspend fun join(campaignId: String) { joins++ }
        override suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult {
            submissions++
            assertEquals("https://fixture.invalid/full.jpg", url)
            assertEquals("0123456789abcdef", hash)
            return SubmitTesterReportResult(success = true, reportId = "server-report")
        }
    }

    private fun worker(operations: Operations) = TestListenableWorkerBuilder<ReportUploadWorker>(context)
        .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to "latency"))
        .setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                ReportUploadWorker(appContext, workerParameters, operations)
        }).build()

    @Test fun slowOptionalThumbnailMustNotBlockReportRpcBeyondTenSeconds() = runTest {
        persist()
        val operations = Operations()
        val job = async { worker(operations).doWork() }
        try {
            runCurrent()
            assertTrue(operations.thumbStarted)
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue("Optional thumbnail must have a bounded deadline; full screenshot is already uploaded", job.isCompleted)
            assertTrue(job.await() is ListenableWorker.Result.Success)
            assertEquals(1, operations.submissions)
            assertTrue("Expired optional work must be cancelled", operations.thumbCancelled)
        } finally { job.cancelAndJoin() }
    }

    @Test fun completedFullUploadMustBeDurableBeforeWaitingForThumbnail() = runTest {
        persist()
        val operations = Operations()
        val job = async { worker(operations).doWork() }
        runCurrent()
        assertTrue(operations.thumbStarted)
        job.cancelAndJoin()
        assertTrue(operations.thumbCancelled)
        assertEquals(0, operations.submissions)
        assertEquals("Full upload URL must survive cancellation/process retry during optional thumbnail",
            "https://fixture.invalid/full.jpg", PendingReportStore.read(context, "latency")!!.uploadedUrl)
    }

    @Test fun externalDeadlineDuringThumbnailMustNotContinueToJoinOrRpc() = runTest {
        persist(needsJoin = true)
        val operations = Operations()
        val job = async { withTimeout(1_000) { worker(operations).doWork() } }
        runCurrent()
        assertTrue(operations.thumbStarted)
        advanceTimeBy(1_000)
        runCurrent()
        try { job.await(); fail("External deadline must propagate") } catch (_: TimeoutCancellationException) {}
        assertTrue(operations.thumbCancelled)
        assertEquals("Do not start membership mutation after parent cancellation", 0, operations.joins)
        assertEquals(0, operations.submissions)
    }
}
