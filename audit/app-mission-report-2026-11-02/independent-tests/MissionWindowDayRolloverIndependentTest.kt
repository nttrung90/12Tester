package com.ntt.crosstest.audit

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.data.*
import com.ntt.crosstest.ui.viewmodels.MissionReportViewModel
import com.ntt.crosstest.util.PreparedScreenshotCache
import com.ntt.crosstest.util.VnTime
import com.ntt.crosstest.work.ReportUploadWorker
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
class MissionWindowDayRolloverIndependentTest {
    private val context:Application get()=RuntimeEnvironment.getApplication()
    private val store=ViewModelStore()
    private lateinit var manager:ReportSubmitManager
    private lateinit var gate:CompletableDeferred<ListenableWorker.Result>
    private class HeldWorker(context:Context,params:WorkerParameters,private val gate:CompletableDeferred<Result>):CoroutineWorker(context,params){
        override suspend fun doWork():Result=gate.await()
    }
    @Before fun before(){
        Dispatchers.setMain(UnconfinedTestDispatcher())
        PendingReportStore.clear(context);MissionReportDraftStore.clearAll(context);SessionCacheManager.clear(context)
        gate=CompletableDeferred()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(object:WorkerFactory(){
            override fun createWorker(appContext:Context,workerClassName:String,workerParameters:WorkerParameters):ListenableWorker?=
                if(workerClassName==ReportUploadWorker::class.java.name)HeldWorker(appContext,workerParameters,gate)else null
        }).build())
        manager=ReportSubmitManager(context)
    }
    @After fun after()=runBlocking{
        store.clear();manager.cancelAllPendingWork();gate.cancel()
        WorkManager.getInstance(context).cancelAllWork().result.get()
        PendingReportStore.clear(context);MissionReportDraftStore.clearAll(context);PreparedScreenshotCache.clear(context);SessionCacheManager.clear(context)
        WorkManagerTestInitHelper.closeWorkDatabase();Dispatchers.resetMain()
    }
    private suspend fun until(predicate:()->Boolean){withTimeout(10_000){while(!predicate()){shadowOf(Looper.getMainLooper()).idle();delay(20)}}}
    @Test fun dayRolloverKeepsOldDraftAndIgnoresLateOldReceipt()=runBlocking{
        val campaign=UUID.randomUUID().toString();val mission=UUID.randomUUID().toString();val reportId=UUID.randomUUID().toString()
        val uid="22222222-2222-2222-2222-222222222222"
        val dayOne=VnTime.todayString();val dayTwo=LocalDate.parse(dayOne).plusDays(1).toString()
        val day=AtomicReference(dayOne);val baseInstant=Instant.now()
        val campaignJson="""{"id":"$campaign","owner_id":"11111111-1111-1111-1111-111111111111","app_name":"Rollover fixture","package_name":"invalid.rollover.app","status":"open"}"""
        val missionJson="""{"id":"$mission","campaign_id":"$campaign","title":"Three day mission","status":"open","has_joined":true,"ends_at":"${baseInstant.plusSeconds(3*86400)}"}"""
        val reportJson="""{"id":"$reportId","campaign_id":"$campaign","user_id":"$uid","submission_context":"mission","mission_id":"$mission","status":"pending","content":"server day one","rating":4,"screenshot_url":"https://fixture.invalid/reports/day-one.jpg","image_hash":"0000000000000000","created_at":"$baseInstant","report_date":"$dayOne"}"""
        val client=createSupabaseClient("https://fixture.invalid","anon-key"){
            install(Auth){sessionManager=MemorySessionManager();codeVerifierCache=MemoryCodeVerifierCache();autoLoadFromStorage=false;autoSaveToStorage=false;enableLifecycleCallbacks=false}
            install(Postgrest)
            httpEngine=MockEngine{request->
                val offset=LocalDate.parse(day.get()).toEpochDay()-LocalDate.parse(dayOne).toEpochDay()
                val serverNow=baseInstant.plusSeconds(offset*86400)
                val path=request.url.encodedPath
                val requestedDate=request.url.parameters["report_date"]
                val lowerCreated=request.url.parameters.getAll("created_at")?.firstOrNull{it.startsWith("gte.")}
                val excludesOldByDate=requestedDate!=null && requestedDate!="eq.$dayOne"
                val excludesOldByTime=lowerCreated?.removePrefix("gte.")?.let{runCatching{Instant.parse(it).isAfter(baseInstant)}.getOrDefault(false)}==true
                val body=when{
                    path.endsWith("/get_campaign_detail")->"""{"campaign":$campaignJson,"is_member":true,"reports":[$reportJson]}"""
                    path.endsWith("/campaigns")->"[$campaignJson]"
                    path.endsWith("/tester_reports")->if(excludesOldByDate||excludesOldByTime)"[]" else "[$reportJson]"
                    path.endsWith("/get_open_missions")->"""{"missions":[$missionJson],"server_now":"$serverNow"}"""
                    path.endsWith("/get_campaign_mission")->"""{"mission":$missionJson}"""
                    else->"[]"
                }
                respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            }
        }
        try{
            client.auth.awaitInitialization()
            client.auth.importSession(Json{ignoreUnknownKeys=true}.decodeFromString<UserSession>(
                """{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"$uid","aud":"authenticated"}}"""))
            MissionReportDraftStore.saveDraft(context,MissionReportDraft(userId=uid,campaignId=campaign,missionId=mission,reportDate=dayOne,
                testStartedAtMs=android.os.SystemClock.elapsedRealtime()-11_000L,bootCount=Int.MAX_VALUE))
            val vm=MissionReportViewModel(campaign,mission,CampaignRepository(client,Localizer(context)),manager,context,SavedStateHandle(),todayProvider={day.get()})
            store.put("across-midnight",vm);until{!vm.uiState.value.isLoading}
            assertTrue("day-one pending report fixture must load",vm.uiState.value.todayReport?.isPending==true)
            assertNull("restoring a timer from another boot must not resurrect its stale elapsed timestamp",vm.uiState.value.testStartedAtMs)
            val source=File(context.cacheDir,"day-one-source.png")
            val bitmap=Bitmap.createBitmap(40,80,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.BLUE)}
            source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            vm.addScreenshots(listOf(Uri.fromFile(source)));until{!vm.uiState.value.isCompressing}
            vm.updateComment("local day one draft")
            val oldImage=vm.uiState.value.preparedScreenshots.single()
            val oldRequest=ReportSubmitManager.SubmitRequest(campaignId=campaign,appName="Rollover",userId=uid,rating=4,comment="older queued comment",
                needsJoin=false,missionId=mission,submissionContext="mission",expectedDate=dayOne,
                fallbackScreenshotUrl="https://fixture.invalid/reports/day-one.jpg",fallbackImageHash="0000000000000000")
            assertTrue(manager.submit(oldRequest) is ReportSubmitManager.SubmitAdmissionResult.Accepted)
            until{vm.uiState.value.submitState?.requestId==oldRequest.clientSubmitId}
            day.set(dayTwo);vm.onForegroundResume();until{!vm.uiState.value.isLoading}
            assertNull("yesterday report must not give today's form a pending/approved exemption",vm.uiState.value.todayReport)
            assertTrue("old content cannot migrate silently into a new day's form",vm.uiState.value.comment.isBlank())
            assertTrue(vm.uiState.value.preparedScreenshots.isEmpty())
            assertFalse("the new form must release its old photo lease",PreparedScreenshotCache.isLeased(oldImage.file.absolutePath))
            assertTrue("yesterday's durable draft still owns the old file",oldImage.file.isFile)
            vm.updateComment("local day two draft")
            assertEquals("local day one draft",MissionReportDraftStore.loadDraft(context,uid,campaign,mission,dayOne)?.comment)
            assertEquals("local day two draft",MissionReportDraftStore.loadDraft(context,uid,campaign,mission,dayTwo)?.comment)
            val work=WorkManager.getInstance(context).getWorkInfosForUniqueWork("report_submit_${oldRequest.clientSubmitId}").get().single()
            PendingReportStore.markCompleted(context,oldRequest.clientSubmitId,SubmitTesterReportResult(success=true,reportId=reportId,submissionContext="mission",missionId=mission,reportDate=dayOne))
            gate.complete(ListenableWorker.Result.success(workDataOf(ReportUploadWorker.KEY_SUCCESS to true,ReportUploadWorker.KEY_REPORT_ID to reportId)))
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work.id)
            val oldKey=ReportSubmitManager.submitStateKey(uid,campaign,"mission",mission,dayOne)
            until{manager.currentStateForScope(oldKey)?.stage==ReportSubmitManager.Stage.DONE && PendingReportStore.read(context,oldRequest.clientSubmitId)==null}
            delay(100);shadowOf(Looper.getMainLooper()).idle()
            assertNull("late old-day completion must not publish into today's window",vm.uiState.value.submitState)
            assertEquals("local day two draft",vm.uiState.value.comment)
            vm.submitReport();delay(100)
            val newKey=ReportSubmitManager.submitStateKey(uid,campaign,"mission",mission,dayTwo)
            assertNull("new daily report still requires today's test/photo prerequisites",manager.currentStateForScope(newKey))
            assertEquals("local day one draft",MissionReportDraftStore.loadDraft(context,uid,campaign,mission,dayOne)?.comment)
            val todayRequest=oldRequest.copy(clientSubmitId=UUID.randomUUID().toString(),expectedDate=dayTwo,comment="today queued report")
            assertTrue(manager.submit(todayRequest) is ReportSubmitManager.SubmitAdmissionResult.Accepted)
            until{manager.currentStateForScope(newKey)?.requestId==todayRequest.clientSubmitId}
            delay(100);shadowOf(Looper.getMainLooper()).idle()
            assertEquals("today's report must appear even with yesterday's scope retained",todayRequest.clientSubmitId,vm.uiState.value.submitState?.requestId)
        }finally{store.clear();client.close()}
    }
}
