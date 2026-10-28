package com.ntt.crosstest.work

import android.app.Application
import android.content.Context
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.data.*
import com.ntt.crosstest.util.PreparedScreenshot
import com.ntt.crosstest.util.PreparedScreenshotCache
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EgressThumbnailPipelineAuditTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        PendingReportStore.clear(context)
        PreparedScreenshotCache.clear(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }
    @After fun cleanup() {
        PendingReportStore.clear(context)
        PreparedScreenshotCache.clear(context)
    }

    @Test fun durableThumbnailReachesRpcAcrossRetryAndAllImageFilesAreCleaned() = runBlocking {
        val id = UUID.randomUUID().toString()
        val fullBytes = byteArrayOf(1, 2, 3, 4)
        val thumbBytes = byteArrayOf(5, 6, 7)
        val full = PreparedScreenshotCache.newFile(context, id).apply { writeBytes(fullBytes) }
        val thumb = PreparedScreenshotCache.newThumbnailFile(context, id).apply { writeBytes(thumbBytes) }
        val prepared = PreparedScreenshot(id, full, "0123456789abcdef", full.length(), "image/jpeg", thumb, thumb.length())
        val request = ReportSubmitManager.SubmitRequest("campaign", "Fixture", "tester", 5, "fixture", prepared, null, null, false, id)
        val manager = ReportSubmitManager(context)
        val persist = ReportSubmitManager::class.java.getDeclaredMethod("persist", ReportSubmitManager.SubmitRequest::class.java).apply { isAccessible = true }
        persist.invoke(manager, request)
        val durable = PendingReportStore.read(context, id)!!
        assertArrayEquals(thumbBytes, File(durable.thumbnailPath!!).readBytes())
        var fullUploads = 0
        var thumbUploads = 0
        var submissions = 0
        val thumbUrl = "https://fixture.invalid/thumb.jpg"
        val ops = object : ReportUploadOperations {
            override val isReady = true
            override val currentUserId = "tester"
            override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String {
                fullUploads++
                assertArrayEquals(fullBytes, prepared.file.readBytes())
                assertArrayEquals(thumbBytes, prepared.thumbnailFile!!.readBytes())
                return "https://fixture.invalid/full.jpg"
            }
            override suspend fun uploadThumbnail(thumbnailFile: File, campaignId: String, userId: String, uploadId: String): String {
                thumbUploads++
                assertEquals(id, uploadId)
                assertArrayEquals(thumbBytes, thumbnailFile.readBytes())
                return thumbUrl
            }
            override suspend fun join(campaignId: String) = Unit
            override suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult =
                error("Thumbnail must reach the thumbnail-aware RPC path")
            override suspend fun submit(request: PendingReport, url: String?, hash: String?, thumbnailUrl: String?): SubmitTesterReportResult {
                submissions++
                assertEquals(thumbUrl, thumbnailUrl)
                if (submissions == 1) throw IOException("fixture lost response")
                return SubmitTesterReportResult(success = true, reportId = "fixture-report", thumbnailUrl = thumbnailUrl)
            }
        }
        fun worker(attempt: Int) = TestListenableWorkerBuilder<ReportUploadWorker>(context)
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to id))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    ReportUploadWorker(appContext, workerParameters, ops)
            }).build()
        assertTrue(worker(0).doWork() is ListenableWorker.Result.Retry)
        assertEquals(thumbUrl, PendingReportStore.read(context, id)!!.uploadedThumbnailUrl)
        assertTrue(worker(1).doWork() is ListenableWorker.Result.Success)
        assertEquals(1, fullUploads)
        assertEquals(1, thumbUploads)
        assertEquals(2, submissions)
        assertFalse("Prepared full must be removed after receipt", full.exists())
        assertFalse("Prepared thumbnail must be removed after receipt", thumb.exists())
        assertFalse(File(durable.screenshotPath!!).exists())
        assertFalse(File(durable.thumbnailPath!!).exists())
    }
}
