package com.ntt.crosstest.work

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.ntt.crosstest.data.PendingAttachment
import com.ntt.crosstest.data.PendingReport
import com.ntt.crosstest.data.PendingReportStore
import com.ntt.crosstest.data.ReportAttachment
import com.ntt.crosstest.data.ReportAttachmentInput
import com.ntt.crosstest.data.SubmitTesterReportResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.ntt.crosstest.util.PreparedScreenshot
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MissionWindowWorkerIndependentTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var request: PendingReport
    @Before fun before() {
        PendingReportStore.clear(context)
        val requestId=UUID.randomUUID().toString()
        val images=(0..2).map { position ->
            val imageId=UUID.randomUUID().toString()
            val file=PendingReportStore.newAttachmentScreenshotFile(context,requestId,imageId).apply { writeText("bytes-$position") }
            PendingAttachment(id=imageId,position=position,screenshotPath=file.absolutePath,
                imageHash="0123456789abcdef",sha256=PendingReportStore.screenshotSha256(file))
        }
        request=PendingReport(id=requestId,campaignId=UUID.randomUUID().toString(),appName="Mission",
            userId="22222222-2222-2222-2222-222222222222",rating=5,comment="Frozen three-image evidence",
            missionId=UUID.randomUUID().toString(),submissionContext="mission",attachments=images,
            expectedDate=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toString())
        PendingReportStore.write(context,request)
    }
    @After fun after() { PendingReportStore.clear(context) }
    private inner class Operations : ReportUploadOperations {
        override var isReady=true
        override var currentUserId: String?=request.userId
        val uploads=mutableListOf<String>()
        val submissions=mutableListOf<List<ReportAttachmentInput>>()
        var failThirdOnce=false
        var loseFirstRpcResponse=false
        var committedReceipt: SubmitTesterReportResult?=null
        override suspend fun upload(prepared: PreparedScreenshot,campaignId:String,userId:String):String {
            uploads+=prepared.uploadId
            if(failThirdOnce&&prepared.uploadId==request.attachments[2].id){failThirdOnce=false;throw IOException("third image interrupted")}
            return "https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2F$campaignId%2F$userId%2F${prepared.uploadId}.jpg?alt=media&token=fixture"
        }
        override suspend fun submit(request:PendingReport,url:String?,hash:String?):SubmitTesterReportResult =
            throw AssertionError("multi-image work must submit the complete v3 payload")
        override suspend fun submitV3(request:PendingReport,attachments:List<ReportAttachmentInput>,missionId:String?):SubmitTesterReportResult {
            assertEquals(this@MissionWindowWorkerIndependentTest.request.id,request.id)
            assertEquals("mission",request.submissionContext)
            assertEquals(this@MissionWindowWorkerIndependentTest.request.expectedDate,request.expectedDate)
            assertEquals(this@MissionWindowWorkerIndependentTest.request.comment,request.comment)
            submissions+=attachments.toList()
            if(committedReceipt==null)committedReceipt=SubmitTesterReportResult(success=true,reportId=UUID.randomUUID().toString(),
                submissionContext="mission",missionId=missionId,reportDate=request.expectedDate,
                screenshotUrl=attachments.first().screenshotUrl,imageHash=attachments.first().imageHash,
                attachments=attachments.map { ReportAttachment(id=it.id,position=it.position,screenshotUrl=it.screenshotUrl,
                    thumbnailUrl=it.thumbnailUrl,imageHash=it.imageHash,sha256=it.sha256) })
            if(loseFirstRpcResponse){loseFirstRpcResponse=false;throw IOException("response lost after commit")}
            return committedReceipt!!
        }
    }
    private fun worker(operations:Operations,attempt:Int=0):ReportUploadWorker =
        TestListenableWorkerBuilder<ReportUploadWorker>(context)
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to request.id))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object:WorkerFactory(){
                override fun createWorker(appContext:Context,workerClassName:String,workerParameters:WorkerParameters):ListenableWorker =
                    ReportUploadWorker(appContext,workerParameters,operations)
            }).build()
    @Test fun thirdImageRetryKeepsFirstTwoCheckpointsAndStableOrder()=runBlocking {
        val operations=Operations().apply { failThirdOnce=true }
        assertTrue(worker(operations).doWork() is ListenableWorker.Result.Retry)
        val partial=PendingReportStore.read(context,request.id)!!
        assertNotNull(partial.attachments[0].uploadedUrl)
        assertNotNull(partial.attachments[1].uploadedUrl)
        assertNull(partial.attachments[2].uploadedUrl)
        assertTrue(operations.submissions.isEmpty())
        assertTrue(worker(operations,1).doWork() is ListenableWorker.Result.Success)
        assertEquals(listOf(request.attachments[0].id,request.attachments[1].id,request.attachments[2].id,request.attachments[2].id),operations.uploads)
        assertEquals(1,operations.submissions.size)
        assertEquals(request.attachments.map { it.id },operations.submissions.single().map { it.id })
        assertEquals(listOf(0,1,2),operations.submissions.single().map { it.position })
        assertEquals(3,PendingReportStore.read(context,request.id)!!.completedResult!!.attachments.size)
    }
    @Test fun lostRpcResponseAndUnconfirmedWorkSuccessReplayTheFullReceipt()=runBlocking {
        val operations=Operations().apply { loseFirstRpcResponse=true }
        assertTrue(worker(operations).doWork() is ListenableWorker.Result.Retry)
        assertEquals(3,operations.uploads.size)
        assertNull(PendingReportStore.read(context,request.id)!!.completedResult)
        assertTrue(worker(operations,1).doWork() is ListenableWorker.Result.Success)
        assertEquals(3,operations.uploads.size)
        assertEquals(2,operations.submissions.size)
        assertEquals(operations.submissions[0],operations.submissions[1])
        assertEquals(operations.committedReceipt,PendingReportStore.read(context,request.id)!!.completedResult)
        operations.isReady=false
        assertTrue(worker(operations,2).doWork() is ListenableWorker.Result.Success)
        assertEquals(2,operations.submissions.size)
        assertEquals(3,PendingReportStore.read(context,request.id)!!.completedResult!!.attachments.size)
    }
}
