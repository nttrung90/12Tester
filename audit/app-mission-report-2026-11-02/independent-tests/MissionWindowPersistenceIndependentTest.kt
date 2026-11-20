package com.ntt.crosstest.audit

import android.app.Application
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.data.PendingAttachment
import com.ntt.crosstest.data.PendingReport
import com.ntt.crosstest.data.PendingReportStore
import com.ntt.crosstest.data.ReportAttachment
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.data.SubmitTesterReportResult
import com.ntt.crosstest.util.PreparedScreenshotCache
import com.ntt.crosstest.work.ReportUploadWorker
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MissionWindowPersistenceIndependentTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    @Before fun before() {
        PendingReportStore.clear(context)
        PreparedScreenshotCache.clear(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }
    @After fun after() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        PendingReportStore.clear(context)
        PreparedScreenshotCache.clear(context)
    }
    private fun request(bytes: Long = 16, createdAt: Long = System.currentTimeMillis()): PendingReport {
        val requestId = UUID.randomUUID().toString()
        val images = (0..2).map { position ->
            val imageId = UUID.randomUUID().toString()
            val file = PendingReportStore.newAttachmentScreenshotFile(context, requestId, imageId)
            RandomAccessFile(file, "rw").use { it.setLength(bytes) }
            PendingAttachment(id = imageId, position = position, screenshotPath = file.absolutePath,
                imageHash = "0123456789abcdef")
        }
        return PendingReport(id=requestId,campaignId=UUID.randomUUID().toString(),appName="Mission",userId="22222222-2222-2222-2222-222222222222",rating=5,comment="evidence",
            screenshotPath=images.first().screenshotPath,imageHash=images.first().imageHash,
            missionId=UUID.randomUUID().toString(),submissionContext="mission",createdAt=createdAt,attachments=images,
            expectedDate=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toString())
    }
    private fun queue(report: PendingReport) {
        val work = OneTimeWorkRequestBuilder<ReportUploadWorker>()
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to report.id))
            .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
            .addTag(ReportSubmitManager.TAG).build()
        val manager=WorkManager.getInstance(context)
        manager.enqueueUniqueWork("report_submit_${report.id}",ExistingWorkPolicy.KEEP,work).result.get()
        assertFalse("fixture work must remain unresolved",manager.getWorkInfoById(work.id).get()!!.state.isFinished)
    }
    @Test fun uploadedCheckpointRestoresOnlyTheMatchingAttachment() {
        val report=request();PendingReportStore.write(context,report)
        val second=report.attachments[1]
        PendingReportStore.markAttachmentUploaded(context,report.id,second.id,"uploaded-second",second.imageHash,"thumb-second")
        val restored=PendingReportStore.read(context,report.id)!!
        assertEquals(report.attachments.map { it.id },restored.attachments.map { it.id })
        assertNull(restored.attachments[0].uploadedUrl)
        assertEquals("uploaded-second",restored.attachments[1].uploadedUrl)
        assertEquals("thumb-second",restored.attachments[1].uploadedThumbnailUrl)
        assertNull(restored.attachments[2].uploadedUrl)
        assertNull("legacy primary checkpoint must not be populated by image two",restored.uploadedUrl)
    }
    @Test fun completedReceiptRetainsAllImagesAndLeasedPreviewFiles() {
        var report=request()
        val prepared=report.attachments.map { image ->
            val file=PreparedScreenshotCache.newFile(context,image.id).apply { writeText("preview") }
            PreparedScreenshotCache.acquireLease(file.absolutePath)
            image.copy(preparedPath=file.absolutePath)
        }
        report=report.copy(attachments=prepared)
        PendingReportStore.write(context,report)
        val receipt=SubmitTesterReportResult(success=true,reportId="server-report",submissionContext="mission",
            attachments=prepared.map { ReportAttachment(id=it.id,position=it.position,screenshotUrl="url-${it.position}",imageHash=it.imageHash) })
        PendingReportStore.markCompleted(context,report.id,receipt)
        assertEquals(receipt,PendingReportStore.read(context,report.id)!!.completedResult)
        assertEquals(3,PendingReportStore.read(context,report.id)!!.completedResult!!.attachments.size)
        prepared.forEach {
            assertFalse(File(it.screenshotPath).exists())
            assertTrue("leased preview must survive receipt cleanup",File(it.preparedPath!!).exists())
        }
    }
    @Test fun preparedCleanupRetainsAFileStillLeasedByTheDraft() {
        val file=PreparedScreenshotCache.newFile(context,UUID.randomUUID().toString()).apply { writeText("draft") }
        file.setLastModified(System.currentTimeMillis()-49L*60*60*1000)
        PreparedScreenshotCache.acquireLease(file.absolutePath)
        PreparedScreenshotCache.cleanup(context)
        assertTrue("age cleanup must not delete an image still referenced by an active draft",file.isFile)
    }
    @Test fun ageCleanupDoesNotEraseQueuedThreeImageWork() {
        val report=request(createdAt=System.currentTimeMillis()-49L*60*60*1000)
        PendingReportStore.write(context,report);queue(report)
        PendingReportStore.cleanup(context)
        assertNotNull("pending request still belongs to unresolved WorkManager work",PendingReportStore.read(context,report.id))
        report.attachments.forEach { assertTrue("queued image must remain readable",File(it.screenshotPath).isFile) }
    }
    @Test fun budgetDoesNotCountTheSamePrimaryPathTwiceOrEvictQueuedWork() {
        val reports=(1..3).map { request(bytes=1_900_000) }
        reports.forEach { PendingReportStore.write(context,it);queue(it) }
        val actualUniqueBytes=reports.flatMap { it.attachments }.map { it.screenshotPath }.distinct().sumOf { File(it).length() }
        assertTrue("fixture should fit the existing20MiB image budget",actualUniqueBytes<20L*1024*1024)
        PendingReportStore.cleanup(context)
        reports.forEach { report ->
            assertNotNull("cleanup must preserve each accepted unresolved request",PendingReportStore.read(context,report.id))
            report.attachments.forEach { assertTrue(File(it.screenshotPath).isFile) }
        }
    }
}
