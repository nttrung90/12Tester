package com.ntt.crosstest.audit

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.data.*
import com.ntt.crosstest.util.PreparedScreenshot
import com.ntt.crosstest.util.PreparedScreenshotCache
import com.ntt.crosstest.util.VnTime
import com.ntt.crosstest.work.ReportUploadWorker
import com.ntt.crosstest.ui.viewmodels.CampaignDetailViewModel
import androidx.lifecycle.ViewModelStore
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(application=Application::class,sdk=[34])
@LooperMode(LooperMode.Mode.PAUSED)
class MissionWindowManagerIndependentTest {
    private val context:Application get()=RuntimeEnvironment.getApplication()
    private lateinit var manager:ReportSubmitManager
    private lateinit var gate:CompletableDeferred<ListenableWorker.Result>
    private val user="22222222-2222-2222-2222-222222222222"
    private val campaign="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    private val mission="bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
    private class GateWorker(context:Context,params:WorkerParameters,val gate:CompletableDeferred<Result>):CoroutineWorker(context,params){
        override suspend fun doWork():Result=gate.await()
    }
    @Before fun before(){
        PendingReportStore.clear(context);PreparedScreenshotCache.clear(context);MissionReportDraftStore.clearAll(context);gate=CompletableDeferred()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setWorkerFactory(object:WorkerFactory(){
            override fun createWorker(appContext:Context,workerClassName:String,workerParameters:WorkerParameters):ListenableWorker?=
                if(workerClassName==ReportUploadWorker::class.java.name)GateWorker(appContext,workerParameters,gate)else null
        }).build())
        manager=ReportSubmitManager(context)
    }
    @After fun after()=runBlocking{
        manager.cancelAllPendingWork();gate.cancel()
        WorkManager.getInstance(context).cancelAllWork().result.get(5,TimeUnit.SECONDS)
        PendingReportStore.clear(context);PreparedScreenshotCache.clear(context);MissionReportDraftStore.clearAll(context)
        WorkManagerTestInitHelper.closeWorkDatabase()
    }
    private suspend fun until(predicate:()->Boolean){withTimeout(10_000){while(!predicate()){shadowOf(Looper.getMainLooper()).idle();delay(20)}}}
    private fun request(isMission:Boolean):ReportSubmitManager.SubmitRequest{
        val images=(1..if(isMission)3 else 1).map {
            val id=UUID.randomUUID().toString()
            val file=PreparedScreenshotCache.newFile(context,id).apply{writeText("prepared-$id")}
            PreparedScreenshot(uploadId=id,file=file,imageHash="0123456789abcdef",sizeBytes=file.length(),contentType="image/jpeg")
        }
        return ReportSubmitManager.SubmitRequest(campaignId=campaign,appName="Window",userId=user,rating=5,comment="frozen snapshot",
            needsJoin=false,clientSubmitId=UUID.randomUUID().toString(),missionId=if(isMission)mission else null,
            submissionContext=if(isMission)"mission" else "app_test",expectedDate=VnTime.today().toString(),
            preparedScreenshot=if(isMission)null else images.single(),preparedScreenshots=if(isMission)images else emptyList())
    }
    private fun key(request:ReportSubmitManager.SubmitRequest)=ReportSubmitManager.submitStateKey(request.userId,request.campaignId,request.submissionContext,request.missionId,request.expectedDate)
    @Test fun sameCampaignKindsAdmitIndependentlyAndLogoutPurgesBothScopes()=runBlocking{
        val ordinary=request(false);val special=request(true)
        assertTrue(manager.submit(ordinary) is ReportSubmitManager.SubmitAdmissionResult.Accepted)
        assertTrue(manager.submit(special) is ReportSubmitManager.SubmitAdmissionResult.Accepted)
        until{manager.currentStateForScope(key(ordinary))!=null&&manager.currentStateForScope(key(special))!=null}
        assertEquals(ordinary.clientSubmitId,manager.currentStateForScope(key(ordinary))!!.requestId)
        assertEquals(special.clientSubmitId,manager.currentStateForScope(key(special))!!.requestId)
        manager.cancelAllPendingWork()
        assertNull("ordinary account scope must be erased on logout",manager.currentStateForScope(key(ordinary)))
        assertNull("mission account scope must be erased on logout",manager.currentStateForScope(key(special)))
    }
    @Test fun committedWorkPublishesTheWholeDurableReceiptBeforeDeletingPendingMetadata()=runBlocking{
        val request=request(true)
        assertTrue(manager.submit(request) is ReportSubmitManager.SubmitAdmissionResult.Accepted)
        val wm=WorkManager.getInstance(context)
        until{wm.getWorkInfosForUniqueWork("report_submit_${request.clientSubmitId}").get().isNotEmpty()}
        val work=wm.getWorkInfosForUniqueWork("report_submit_${request.clientSubmitId}").get().single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work.id)
        until{wm.getWorkInfoById(work.id).get()?.state==WorkInfo.State.RUNNING}
        val receipt=SubmitTesterReportResult(success=true,reportId=UUID.randomUUID().toString(),submissionContext="mission",missionId=mission,
            reportDate=request.expectedDate,screenshotUrl="https://fixture.invalid/first",imageHash="0123456789abcdef",
            attachments=request.preparedScreenshots.mapIndexed{index,image->ReportAttachment(id=image.uploadId,position=index,
                screenshotUrl=if(index==0)"https://fixture.invalid/first" else "https://fixture.invalid/$index",imageHash=image.imageHash)})
        PendingReportStore.markCompleted(context,request.clientSubmitId,receipt)
        gate.complete(ListenableWorker.Result.success(workDataOf(ReportUploadWorker.KEY_SUCCESS to true,
            ReportUploadWorker.KEY_REPORT_ID to receipt.reportId,ReportUploadWorker.KEY_SCREENSHOT_URL to receipt.screenshotUrl,
            ReportUploadWorker.KEY_IMAGE_HASH to receipt.imageHash,ReportUploadWorker.KEY_IS_MISSION_REPORT to true,
            ReportUploadWorker.KEY_MISSION_ID to mission)))
        until{manager.currentStateForScope(key(request))?.stage==ReportSubmitManager.Stage.DONE}
        val delivered=manager.currentStateForScope(key(request))!!.result!!
        assertEquals("completion must retain all durable attachments",receipt.attachments,delivered.attachments)
        assertEquals("completion must retain its original reporting date",receipt.reportDate,delivered.reportDate)
        until{PendingReportStore.read(context,request.clientSubmitId)==null}
    }
    @Test fun ordinaryDetailDoesNotAdoptAnotherMissionSendingState()=runBlocking{
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val client=createSupabaseClient("https://mock.supabase.co","anon-key"){
            httpEngine=MockEngine {respond("{}",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))}
            install(Auth){sessionManager=MemorySessionManager();codeVerifierCache=MemoryCodeVerifierCache();enableLifecycleCallbacks=false}
            install(Postgrest)
        }
        val store=ViewModelStore()
        try{
            val vm=CampaignDetailViewModel(campaign,CampaignRepository(client,Localizer(context)),manager,user,Localizer(context),context)
            store.put("ordinary-window",vm)
            val missionRequest=request(true)
            assertTrue(manager.submit(missionRequest) is ReportSubmitManager.SubmitAdmissionResult.Accepted)
            until{manager.currentStateForScope(key(missionRequest))?.stage==ReportSubmitManager.Stage.SENDING}
            shadowOf(Looper.getMainLooper()).idle();delay(700)
            assertNull("ordinary detail must keep its own slot available while a mission uploads",vm.uiState.value.submitStage)
            val yesterdayOrdinary=request(false).copy(expectedDate=VnTime.today().minusDays(1).toString())
            assertTrue(manager.submit(yesterdayOrdinary) is ReportSubmitManager.SubmitAdmissionResult.Accepted)
            until{manager.currentStateForScope(key(yesterdayOrdinary))?.stage==ReportSubmitManager.Stage.SENDING}
            shadowOf(Looper.getMainLooper()).idle();delay(300)
            assertNull("yesterday's ordinary queue must not occupy today's ordinary report window",vm.uiState.value.submitStage)
        }finally{store.clear();client.close();Dispatchers.resetMain()}
    }
    @Test fun admissionBlocksBeforeExceedingPendingBudgetWithoutEvictingAcceptedWork()=runBlocking{
        fun largeRequest():ReportSubmitManager.SubmitRequest{
            val req=request(true).copy(campaignId=UUID.randomUUID().toString())
            req.preparedScreenshots.forEach { image -> java.io.RandomAccessFile(image.file,"rw").use { it.setLength(1_900_000) } }
            return req
        }
        val accepted=(1..3).map { largeRequest() }
        accepted.forEach { assertTrue(manager.submit(it) is ReportSubmitManager.SubmitAdmissionResult.Accepted) }
        val excess=largeRequest()
        val outcome=manager.submit(excess)
        assertTrue("a full pending store must explain capacity before accepting more work: $outcome",outcome is ReportSubmitManager.SubmitAdmissionResult.Blocked)
        assertNull("blocked admission must not leave a runnable pending record",PendingReportStore.read(context,excess.clientSubmitId))
        accepted.forEach { assertNotNull("accepted work must never be evicted to admit a new request",PendingReportStore.read(context,it.clientSubmitId)) }
        val actualBytes=PendingReportStore.directory(context).walkTopDown().filter{it.isFile}.sumOf{it.length()}
        assertTrue("queue budget counts each actual physical file once",actualBytes<=20L*1024*1024)
        // A capacity refusal must release admission identity so the same unsent draft can retry.
        val first=accepted.first()
        val firstWork=WorkManager.getInstance(context).getWorkInfosForUniqueWork("report_submit_${first.clientSubmitId}").get().single()
        val receipt=SubmitTesterReportResult(success=true,reportId=UUID.randomUUID().toString(),submissionContext="mission",missionId=mission,reportDate=first.expectedDate)
        PendingReportStore.markCompleted(context,first.clientSubmitId,receipt)
        gate.complete(ListenableWorker.Result.success(workDataOf(ReportUploadWorker.KEY_SUCCESS to true,ReportUploadWorker.KEY_REPORT_ID to receipt.reportId)))
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(firstWork.id)
        until{manager.currentStateForScope(key(first))?.stage==ReportSubmitManager.Stage.DONE && PendingReportStore.read(context,first.clientSubmitId)==null}
        assertTrue("capacity-blocked request must be admissible once real space is freed",manager.submit(excess) is ReportSubmitManager.SubmitAdmissionResult.Accepted)
    }
    @Test fun ordinaryAdmissionFreezesItsDayBeforeWorkIsEnqueued()=runBlocking{
        val req=request(false).copy(expectedDate=null)
        val today=VnTime.todayString()
        assertTrue(manager.submit(req) is ReportSubmitManager.SubmitAdmissionResult.Accepted)
        val durable=PendingReportStore.read(context,req.clientSubmitId)!!
        assertEquals("ordinary queued request must retain one immutable reporting day",today,durable.expectedDate)
        assertEquals(req.clientSubmitId,manager.currentStateForScope(ReportSubmitManager.submitStateKey(user,campaign,"app_test",null,today))?.requestId)
    }
    @Test fun photoOnlyNewerDraftSurvivesEarlierReceiptWithoutAnOpenWindow()=runBlocking{
        val req=request(true)
        val draft=MissionReportDraft(user,campaign,mission,req.expectedDate!!,rating=req.rating,comment=req.comment,
            preparedImages=req.preparedScreenshots.map { DraftAttachment(it.uploadId,it.file.absolutePath,imageHash=it.imageHash,sizeBytes=it.file.length()) })
        MissionReportDraftStore.saveDraft(context,draft)
        assertTrue(manager.submit(req) is ReportSubmitManager.SubmitAdmissionResult.Accepted)
        // User removes only the first photo; text/rating are deliberately unchanged.
        val newer=draft.copy(preparedImages=draft.preparedImages.drop(1),updatedAt=System.currentTimeMillis()+1)
        MissionReportDraftStore.saveDraft(context,newer)
        val wm=WorkManager.getInstance(context)
        val work=wm.getWorkInfosForUniqueWork("report_submit_${req.clientSubmitId}").get().single()
        val receipt=SubmitTesterReportResult(success=true,reportId=UUID.randomUUID().toString(),submissionContext="mission",missionId=mission,
            reportDate=req.expectedDate,attachments=req.preparedScreenshots.mapIndexed { index,image ->
                ReportAttachment(image.uploadId,index,"https://fixture.invalid/reports/$index.jpg",imageHash=image.imageHash)
            })
        PendingReportStore.markCompleted(context,req.clientSubmitId,receipt)
        gate.complete(ListenableWorker.Result.success(workDataOf(ReportUploadWorker.KEY_SUCCESS to true,ReportUploadWorker.KEY_REPORT_ID to receipt.reportId)))
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work.id)
        until{manager.currentStateForScope(key(req))?.stage==ReportSubmitManager.Stage.DONE}
        val restored=MissionReportDraftStore.loadDraft(context,user,campaign,mission,req.expectedDate!!)
        assertNotNull("changing only photos creates a newer durable draft",restored)
        assertEquals(newer.preparedImages.map{it.uploadId},restored!!.preparedImages.map{it.uploadId})
        assertTrue("newer draft photos must survive background receipt cleanup",restored.preparedImages.all{java.io.File(it.filePath).isFile})
    }
    @Test fun concurrentAdmissionsShareOnePhysicalPendingBudget()=runBlocking{
        fun large():ReportSubmitManager.SubmitRequest{
            val req=request(true).copy(campaignId=UUID.randomUUID().toString())
            req.preparedScreenshots.forEach { java.io.RandomAccessFile(it.file,"rw").use { file -> file.setLength(1_900_000) } }
            return req
        }
        val prior=(1..2).map{large()}
        prior.forEach{assertTrue(manager.submit(it) is ReportSubmitManager.SubmitAdmissionResult.Accepted)}
        val candidates=(1..4).map{large()}
        val start=CompletableDeferred<Unit>()
        val submissions=candidates.map { req -> async(Dispatchers.IO){start.await();manager.submit(req)} }
        start.complete(Unit)
        val results=withTimeout(15_000){submissions.awaitAll()}
        assertEquals("only one remaining 5.7MB slot is available",1,results.count{it is ReportSubmitManager.SubmitAdmissionResult.Accepted})
        assertEquals(3,results.count{it is ReportSubmitManager.SubmitAdmissionResult.Blocked})
        prior.forEach { assertNotNull("accepted earlier work must survive concurrent admissions",PendingReportStore.read(context,it.clientSubmitId)) }
        assertTrue(PendingReportStore.directory(context).walkTopDown().filter{it.isFile}.sumOf{it.length()}<=20L*1024*1024)
    }
    @Test fun admissionCountsActualMetadataWithoutDeletingTheUnsentDraft()=runBlocking{
        fun large():ReportSubmitManager.SubmitRequest{
            val req=request(true).copy(campaignId=UUID.randomUUID().toString())
            req.preparedScreenshots.forEach{java.io.RandomAccessFile(it.file,"rw").use{file->file.setLength(1_900_000)}}
            return req
        }
        val accepted=(1..3).map{large()}
        accepted.forEach{assertTrue(manager.submit(it) is ReportSubmitManager.SubmitAdmissionResult.Accepted)}
        val limit=20L*1024*1024
        val existingBytes=PendingReportStore.directory(context).walkTopDown().filter{it.isFile}.sumOf{it.length()}
        val availableImageBytes=limit-existingBytes-8_192L
        assertTrue(availableImageBytes>0)
        val req=request(true).copy(campaignId=UUID.randomUUID().toString(),comment="stack trace line ".repeat(1_500))
        req.preparedScreenshots.forEachIndexed{index,image->
            val size=if(index<2)availableImageBytes/3 else availableImageBytes-2*(availableImageBytes/3)
            java.io.RandomAccessFile(image.file,"rw").use{it.setLength(size)}
        }
        val outcome=manager.submit(req)
        assertTrue("JSON is larger than a fixed8KiB allowance; actual total must be checked before accepting: $outcome",outcome is ReportSubmitManager.SubmitAdmissionResult.Blocked)
        assertNull(PendingReportStore.read(context,req.clientSubmitId))
        assertTrue("refused report must keep the tester's prepared draft files",req.preparedScreenshots.all{it.file.isFile})
        accepted.forEach{assertNotNull(PendingReportStore.read(context,it.clientSubmitId))}
        assertTrue(PendingReportStore.directory(context).walkTopDown().filter{it.isFile}.sumOf{it.length()}<=limit)
        // Small content still has real attachment paths, IDs, SHA values and default fields.
        // A skeleton JSON estimate must not omit these bytes at the queue boundary.
        val small=request(true).copy(campaignId=UUID.randomUUID().toString(),comment="short")
        val tightImageBytes=limit-existingBytes-1_024L
        small.preparedScreenshots.forEachIndexed{index,image->
            val size=if(index<2)tightImageBytes/3 else tightImageBytes-2*(tightImageBytes/3)
            java.io.RandomAccessFile(image.file,"rw").use{it.setLength(size)}
        }
        val tightOutcome=manager.submit(small)
        assertTrue("full attachment metadata exceeds1KiB even with short content: $tightOutcome",tightOutcome is ReportSubmitManager.SubmitAdmissionResult.Blocked)
        assertNotEquals("a refused admission cannot leave the form stuck in sending",ReportSubmitManager.Stage.SENDING,manager.currentStateForScope(key(small))?.stage)
        assertNull(PendingReportStore.read(context,small.clientSubmitId))
        assertTrue(small.preparedScreenshots.all{it.file.isFile})
        accepted.forEach{assertNotNull(PendingReportStore.read(context,it.clientSubmitId))}
        assertTrue(PendingReportStore.directory(context).walkTopDown().filter{it.isFile}.sumOf{it.length()}<=limit)
    }
}
