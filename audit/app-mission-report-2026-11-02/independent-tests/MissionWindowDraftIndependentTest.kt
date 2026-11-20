package com.ntt.crosstest.audit

import android.app.Application
import android.os.Looper
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.PendingReportStore
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.SubmitTesterReportResult
import com.ntt.crosstest.work.ReportUploadWorker
import com.ntt.crosstest.util.PreparedScreenshotCache
import com.ntt.crosstest.util.VnTime
import com.ntt.crosstest.ui.viewmodels.MissionReportViewModel
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.Duration
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
class MissionWindowDraftIndependentTest {
    private val context:Application get()=RuntimeEnvironment.getApplication()
    private val store=ViewModelStore()
    private lateinit var manager:ReportSubmitManager
    private lateinit var completionGate:CompletableDeferred<ListenableWorker.Result>
    private class HeldWorker(context:android.content.Context,params:WorkerParameters,private val gate:CompletableDeferred<Result>):CoroutineWorker(context,params){
        override suspend fun doWork():Result=gate.await()
    }
    @Before fun before(){
        Dispatchers.setMain(UnconfinedTestDispatcher())
        PendingReportStore.clear(context);SessionCacheManager.clear(context)
        completionGate=CompletableDeferred()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(object:WorkerFactory(){
            override fun createWorker(appContext:android.content.Context,workerClassName:String,workerParameters:WorkerParameters):ListenableWorker?=
                if(workerClassName==ReportUploadWorker::class.java.name)HeldWorker(appContext,workerParameters,completionGate)else null
        }).build())
        manager=ReportSubmitManager(context)
    }
    @After fun after()=runBlocking{
        store.clear();manager.cancelAllPendingWork();completionGate.cancel()
        WorkManager.getInstance(context).cancelAllWork().result.get()
        PendingReportStore.clear(context);SessionCacheManager.clear(context)
        WorkManagerTestInitHelper.closeWorkDatabase();Dispatchers.resetMain()
    }
    private suspend fun until(predicate:()->Boolean){withTimeout(10_000){while(!predicate()){shadowOf(Looper.getMainLooper()).idle();delay(20)}}}
    @Test fun unsubmittedDraftSurvivesPoppingRouteAndStaysPrivateToItsAccount()=runBlocking{
        val campaign=UUID.randomUUID().toString();val mission=UUID.randomUUID().toString()
        val owner="11111111-1111-1111-1111-111111111111"
        val userA="22222222-2222-2222-2222-222222222222";val userB="33333333-3333-3333-3333-333333333333"
        val campJson="""{"id":"$campaign","owner_id":"$owner","app_name":"Draft fixture","package_name":"invalid.draft.app","status":"open"}"""
        val missionJson="""{"id":"$mission","campaign_id":"$campaign","creator_id":"$owner","title":"Draft mission","status":"open","has_joined":true,"ends_at":"${Instant.now().plusSeconds(86400)}"}"""
        val client=createSupabaseClient("https://fixture.invalid","anon-key"){
            install(Auth){sessionManager=MemorySessionManager();codeVerifierCache=MemoryCodeVerifierCache();autoLoadFromStorage=false;autoSaveToStorage=false;enableLifecycleCallbacks=false}
            install(Postgrest)
            httpEngine=MockEngine{request->
                val path=request.url.encodedPath
                val body=when{
                    path.endsWith("/get_campaign_detail")->"""{"campaign":$campJson,"is_member":true,"reports":[]}"""
                    path.endsWith("/campaigns")->"[$campJson]"
                    path.endsWith("/get_open_missions")->"""{"missions":[$missionJson],"server_now":"${Instant.now()}"}"""
                    path.endsWith("/get_campaign_mission")->"""{"mission":$missionJson}"""
                    else->"[]"
                }
                respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            }
        }
        suspend fun signIn(user:String){
            client.auth.awaitInitialization()
            client.auth.importSession(Json{ignoreUnknownKeys=true}.decodeFromString<UserSession>(
                """{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"$user","aud":"authenticated"}}"""))
        }
        fun window(key:String):MissionReportViewModel=MissionReportViewModel(campaign,mission,
            CampaignRepository(client,Localizer(context)),manager,context,SavedStateHandle()).also{store.put(key,it)}
        try{
            signIn(userA)
            val first=window("first");until{!first.uiState.value.isLoading}
            val text="Unsubmitted draft ${UUID.randomUUID()}"
            first.updateComment(text);first.updateRating(3)
            val sources=(0..2).map { index ->
                File(context.cacheDir,"draft-source-${UUID.randomUUID()}.png").also { file ->
                    val bitmap=Bitmap.createBitmap(40,80,Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.rgb(80+index*40,120,170))
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
            }
            first.addScreenshots(sources.map { Uri.fromFile(it) })
            until{!first.uiState.value.isCompressing}
            assertEquals("fixture must prepare three real local images",3,first.uiState.value.preparedScreenshots.size)
            val prepared=first.uiState.value.preparedScreenshots.toList()
            val beforeBytes=prepared.map { it.file.readBytes() }
            store.clear() // a popped navigation route has a new SavedStateHandle when reopened
            // A process restart loses RAM leases. A still-referenced draft must retain its files.
            PreparedScreenshotCache.clearLeases()
            prepared.forEach { image ->
                image.file.setLastModified(System.currentTimeMillis()-49L*60*60*1000)
                image.thumbnailFile?.setLastModified(System.currentTimeMillis()-49L*60*60*1000)
            }
            PreparedScreenshotCache.cleanup(context)
            val reopened=window("reopened");until{!reopened.uiState.value.isLoading}
            assertEquals("closing a mission window must retain its unsubmitted draft",text,reopened.uiState.value.comment)
            assertEquals(3,reopened.uiState.value.rating)
            assertEquals("draft photos must survive process-start cleanup before the window reopens",3,reopened.uiState.value.preparedScreenshots.size)
            reopened.uiState.value.preparedScreenshots.forEachIndexed { index,image ->
                assertEquals(prepared[index].uploadId,image.uploadId)
                assertEquals(prepared[index].imageHash,image.imageHash)
                assertArrayEquals("retry must reuse the same compressed bytes",beforeBytes[index],image.file.readBytes())
                assertTrue("draft thumbnail must also survive",image.thumbnailFile?.isFile==true)
            }
            reopened.recordTestStart()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
            reopened.submitReport()
            val scopeKey=ReportSubmitManager.submitStateKey(userA,campaign,"mission",mission,VnTime.todayString())
            until{manager.currentStateForScope(scopeKey)!=null}
            delay(250);shadowOf(Looper.getMainLooper()).idle()
            assertNotEquals("fixture must not have a successful server receipt yet",ReportSubmitManager.Stage.DONE,manager.currentStateForScope(scopeKey)?.stage)
            store.clear()
            val pendingWindow=window("reopened-during-upload");until{!pendingWindow.uiState.value.isLoading}
            assertEquals("admission without a successful receipt must preserve the draft",text,pendingWindow.uiState.value.comment)
            assertEquals("pending draft must retain photos while upload/retry is unresolved",3,pendingWindow.uiState.value.preparedScreenshots.size)
            val newerText="New draft typed while previous snapshot uploads ${UUID.randomUUID()}"
            pendingWindow.updateComment(newerText);pendingWindow.updateRating(2)
            val pendingId=manager.currentStateForScope(scopeKey)!!.requestId!!
            val work=WorkManager.getInstance(context).getWorkInfosForUniqueWork("report_submit_$pendingId").get().single()
            store.clear()
            PreparedScreenshotCache.clearLeases() // receipt may arrive after process recreation, with no open form
            PendingReportStore.markCompleted(context,pendingId,SubmitTesterReportResult(success=true,reportId=UUID.randomUUID().toString(),
                submissionContext="mission",missionId=mission,reportDate=VnTime.todayString()))
            completionGate.complete(ListenableWorker.Result.success(workDataOf(ReportUploadWorker.KEY_SUCCESS to true,ReportUploadWorker.KEY_REPORT_ID to "committed-report")))
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work.id)
            until{manager.currentStateForScope(scopeKey)?.stage==ReportSubmitManager.Stage.DONE}
            delay(100);shadowOf(Looper.getMainLooper()).idle()
            store.clear()
            val afterReceipt=window("reopened-after-old-receipt");until{!afterReceipt.uiState.value.isLoading}
            assertEquals("an older successful snapshot must not erase newer durable edits",newerText,afterReceipt.uiState.value.comment)
            assertEquals("newer draft rating belongs to the newer snapshot",2,afterReceipt.uiState.value.rating)
            assertEquals("background receipt cleanup must preserve photos referenced by a newer durable draft",3,afterReceipt.uiState.value.preparedScreenshots.size)
            // The converse is also required: an unchanged submitted draft is acknowledged even
            // when no mission ViewModel remains alive when its durable receipt arrives.
            completionGate=CompletableDeferred()
            afterReceipt.recordTestStart()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
            afterReceipt.submitReport()
            until{manager.currentStateForScope(scopeKey)?.requestId!=pendingId && manager.currentStateForScope(scopeKey)?.requestId!=null}
            val secondId=manager.currentStateForScope(scopeKey)!!.requestId!!
            val secondWork=WorkManager.getInstance(context).getWorkInfosForUniqueWork("report_submit_$secondId").get().single()
            store.clear()
            PendingReportStore.markCompleted(context,secondId,SubmitTesterReportResult(success=true,reportId=UUID.randomUUID().toString(),
                submissionContext="mission",missionId=mission,reportDate=VnTime.todayString()))
            completionGate.complete(ListenableWorker.Result.success(workDataOf(ReportUploadWorker.KEY_SUCCESS to true,ReportUploadWorker.KEY_REPORT_ID to "second-committed-report")))
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(secondWork.id)
            until{manager.currentStateForScope(scopeKey)?.stage==ReportSubmitManager.Stage.DONE}
            val acknowledged=window("reopened-after-matching-receipt");until{!acknowledged.uiState.value.isLoading}
            assertTrue("matching committed snapshot must clear its durable comment without requiring an open UI",acknowledged.uiState.value.comment.isBlank())
            assertTrue("matching committed snapshot must clear its durable selected photos",acknowledged.uiState.value.preparedScreenshots.isEmpty())
            store.clear();signIn(userB)
            val other=window("other-account");until{!other.uiState.value.isLoading}
            assertTrue("the other tester must not see the first account's draft",other.uiState.value.comment.isBlank())
            assertTrue("the other tester must not see the first account's photos",other.uiState.value.preparedScreenshots.isEmpty())
            assertNull("the other tester must not see the first account's pending submission",other.uiState.value.submitState)
        }finally{store.clear();client.close()}
    }
    @Test fun expiredMissionRejectsLocallyBeforeCreatingAnUploadAndKeepsTheDraft()=runBlocking{
        val campaign=UUID.randomUUID().toString();val mission=UUID.randomUUID().toString()
        val user="22222222-2222-2222-2222-222222222222"
        val campJson="""{"id":"$campaign","owner_id":"11111111-1111-1111-1111-111111111111","app_name":"Expired fixture","package_name":"invalid.expired.app","status":"open"}"""
        val missionJson="""{"id":"$mission","campaign_id":"$campaign","title":"Expired mission","status":"open","has_joined":true,"ends_at":"${Instant.now().minusSeconds(300)}"}"""
        val client=createSupabaseClient("https://fixture.invalid","anon-key"){
            install(Auth){sessionManager=MemorySessionManager();codeVerifierCache=MemoryCodeVerifierCache();autoLoadFromStorage=false;autoSaveToStorage=false;enableLifecycleCallbacks=false}
            install(Postgrest)
            httpEngine=MockEngine{request->
                val body=when{
                    request.url.encodedPath.endsWith("/get_campaign_detail")->"""{"campaign":$campJson,"is_member":true,"reports":[]}"""
                    request.url.encodedPath.endsWith("/campaigns")->"[$campJson]"
                    request.url.encodedPath.endsWith("/get_open_missions")->"""{"missions":[],"server_now":"${Instant.now()}"}"""
                    request.url.encodedPath.endsWith("/get_campaign_mission")->"""{"mission":$missionJson}"""
                    else->"[]"
                }
                respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            }
        }
        try{
            client.auth.awaitInitialization()
            client.auth.importSession(Json{ignoreUnknownKeys=true}.decodeFromString<UserSession>(
                """{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"$user","aud":"authenticated"}}"""))
            val vm=MissionReportViewModel(campaign,mission,CampaignRepository(client,Localizer(context)),manager,context,SavedStateHandle())
            store.put("expired-window",vm);until{!vm.uiState.value.isLoading}
            assertEquals("expired detail fixture must load read-only",mission,vm.uiState.value.mission?.id)
            val source=File(context.cacheDir,"expired-source.png")
            val bitmap=Bitmap.createBitmap(40,80,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.BLUE)}
            source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            vm.addScreenshots(listOf(Uri.fromFile(source)));until{!vm.uiState.value.isCompressing}
            assertEquals(1,vm.uiState.value.preparedScreenshots.size)
            vm.updateComment("Keep my expired mission draft")
            vm.recordTestStart();org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
            vm.submitReport()
            until{vm.uiState.value.errorMessage!=null || manager.state.value!=null}
            assertNull("expired mission must be blocked before upload admission",manager.state.value)
            assertTrue(PendingReportStore.list(context).isEmpty())
            assertFalse(vm.uiState.value.errorMessage.isNullOrBlank())
            assertEquals("Keep my expired mission draft",vm.uiState.value.comment)
            assertTrue(vm.uiState.value.preparedScreenshots.single().file.isFile)
        }finally{store.clear();client.close()}
    }
    @Test fun newlyPreparedPrimaryIsAllowedWhenOnlyPerceptualHashMatchesRejectedPhoto()=runBlocking{
        val campaign=UUID.randomUUID().toString();val mission=UUID.randomUUID().toString();val report=UUID.randomUUID().toString()
        val user="22222222-2222-2222-2222-222222222222"
        val campJson="""{"id":"$campaign","owner_id":"11111111-1111-1111-1111-111111111111","app_name":"Resubmit fixture","package_name":"invalid.resubmit.app","status":"open"}"""
        val missionJson="""{"id":"$mission","campaign_id":"$campaign","title":"Resubmit mission","status":"open","has_joined":true,"ends_at":"${Instant.now().plusSeconds(86400)}"}"""
        val reportJson="""{"id":"$report","campaign_id":"$campaign","user_id":"$user","submission_context":"mission","mission_id":"$mission","status":"rejected","content":"Old report","screenshot_url":"https://fixture.invalid/reports/old.jpg","image_hash":"0000000000000000","created_at":"${Instant.now()}","report_date":"${VnTime.todayString()}"}"""
        val client=createSupabaseClient("https://fixture.invalid","anon-key"){
            install(Auth){sessionManager=MemorySessionManager();codeVerifierCache=MemoryCodeVerifierCache();autoLoadFromStorage=false;autoSaveToStorage=false;enableLifecycleCallbacks=false}
            install(Postgrest)
            httpEngine=MockEngine{request->
                val body=when{
                    request.url.encodedPath.endsWith("/get_campaign_detail")->"""{"campaign":$campJson,"is_member":true,"reports":[$reportJson]}"""
                    request.url.encodedPath.endsWith("/tester_reports")->"[$reportJson]"
                    request.url.encodedPath.endsWith("/campaigns")->"[$campJson]"
                    request.url.encodedPath.endsWith("/get_open_missions")->"""{"missions":[$missionJson],"server_now":"${Instant.now()}"}"""
                    request.url.encodedPath.endsWith("/get_campaign_mission")->"""{"mission":$missionJson}"""
                    else->"[]"
                }
                respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            }
        }
        try{
            client.auth.awaitInitialization()
            client.auth.importSession(Json{ignoreUnknownKeys=true}.decodeFromString<UserSession>(
                """{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"$user","aud":"authenticated"}}"""))
            val vm=MissionReportViewModel(campaign,mission,CampaignRepository(client,Localizer(context)),manager,context,SavedStateHandle())
            store.put("resubmit-window",vm);until{!vm.uiState.value.isLoading}
            assertTrue("fixture must load today's rejected mission report",vm.uiState.value.todayReport?.isRejected==true)
            val source=File(context.cacheDir,"new-resubmit-source.png")
            val bitmap=Bitmap.createBitmap(40,80,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.BLUE)}
            source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            vm.addScreenshots(listOf(Uri.fromFile(source)));until{!vm.uiState.value.isCompressing}
            assertEquals("uniform but newly selected images can share a dHash","0000000000000000",vm.uiState.value.preparedScreenshots.single().imageHash)
            vm.updateComment("New primary selected for resubmission")
            vm.recordTestStart();org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(11))
            vm.submitReport();until{vm.uiState.value.errorMessage!=null || manager.state.value!=null}
            assertNotNull("dHash equality alone must not block a genuinely new primary; error=${vm.uiState.value.errorMessage}",manager.state.value)
            assertEquals(1,PendingReportStore.list(context).size)
        }finally{store.clear();client.close()}
    }
    @Test fun participantWhoseFirstDayWasApprovedRemainsJoinedDuringTheMissionWindow()=runBlocking{
        val campaign=UUID.randomUUID().toString();val mission=UUID.randomUUID().toString()
        val user="22222222-2222-2222-2222-222222222222"
        val campJson="""{"id":"$campaign","owner_id":"11111111-1111-1111-1111-111111111111","app_name":"Daily fixture","package_name":"invalid.daily.app","status":"open"}"""
        // Existing server marks the participant completed after the first settlement.
        // The global mission is still open; there is no report for today's extra slot.
        val missionJson="""{"id":"$mission","campaign_id":"$campaign","title":"Day two of three","status":"open","duration_days":3,"has_joined":false,"has_completed":true,"created_at":"${Instant.now().minusSeconds(86400)}","ends_at":"${Instant.now().plusSeconds(172800)}"}"""
        val client=createSupabaseClient("https://fixture.invalid","anon-key"){
            install(Auth){sessionManager=MemorySessionManager();codeVerifierCache=MemoryCodeVerifierCache();autoLoadFromStorage=false;autoSaveToStorage=false;enableLifecycleCallbacks=false}
            install(Postgrest)
            httpEngine=MockEngine{request->
                val path=request.url.encodedPath
                val body=when{
                    path.endsWith("/get_campaign_detail")->"""{"campaign":$campJson,"is_member":true,"reports":[]}"""
                    path.endsWith("/campaigns")->"[$campJson]"
                    path.endsWith("/get_open_missions")->"""{"missions":[$missionJson],"server_now":"${Instant.now()}"}"""
                    path.endsWith("/get_campaign_mission")->"""{"mission":$missionJson}"""
                    else->"[]"
                }
                respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            }
        }
        try{
            client.auth.awaitInitialization()
            client.auth.importSession(Json{ignoreUnknownKeys=true}.decodeFromString<UserSession>(
                """{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"$user","aud":"authenticated"}}"""))
            val vm=MissionReportViewModel(campaign,mission,CampaignRepository(client,Localizer(context)),manager,context,SavedStateHandle())
            store.put("next-reporting-day",vm);until{!vm.uiState.value.isLoading}
            assertEquals("fixture mission must load",mission,vm.uiState.value.mission?.id)
            assertTrue("settling day one must not require rejoining or remove later daily report rights",vm.uiState.value.isJoined)
            val yesterday=VnTime.today().minusDays(1).toString()
            val oldRequest=ReportSubmitManager.SubmitRequest(campaignId=campaign,appName="Daily fixture",userId=user,
                rating=5,comment="Yesterday pending retry",needsJoin=false,missionId=mission,submissionContext="mission",
                expectedDate=yesterday,fallbackScreenshotUrl="https://fixture.invalid/reports/yesterday.jpg",fallbackImageHash="0123456789abcdef")
            assertTrue(manager.submit(oldRequest) is ReportSubmitManager.SubmitAdmissionResult.Accepted)
            until{manager.currentStateForScope(ReportSubmitManager.submitStateKey(user,campaign,"mission",mission,yesterday))!=null}
            delay(250);shadowOf(Looper.getMainLooper()).idle()
            assertNull("yesterday's queued work must not occupy today's mission window",vm.uiState.value.submitState)
        }finally{store.clear();client.close()}
    }
}
