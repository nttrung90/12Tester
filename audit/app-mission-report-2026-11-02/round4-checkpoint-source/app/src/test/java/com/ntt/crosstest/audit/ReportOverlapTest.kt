package com.ntt.crosstest.audit

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.data.*
import com.ntt.crosstest.ui.viewmodels.CampaignDetailViewModel
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReportOverlapTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()
    private lateinit var manager: ReportSubmitManager
    private lateinit var gate: CompletableDeferred<ListenableWorker.Result>
    private val clients = mutableListOf<io.github.jan.supabase.SupabaseClient>()

    private class HeldWorker(context: Context, params: WorkerParameters, private val gate: CompletableDeferred<Result>) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = gate.await()
    }

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        gate = CompletableDeferred()
        PendingReportStore.clear(context)
        SessionCacheManager.clear(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    HeldWorker(appContext, workerParameters, gate)
            }).build())
        manager = ReportSubmitManager(context)
    }
    @After fun cleanup() = runBlocking {
        store.clear()
        manager.cancelAllPendingWork()
        clients.forEach { it.close() }
        PendingReportStore.clear(context)
        SessionCacheManager.clear(context)
        Dispatchers.resetMain()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private suspend fun viewModel(
        reportStatus: String = "pending", reportMission: String? = null,
        initialMission: String? = null, returnedMission: String = "mission-new",
        missionError: Boolean = false, hasJoinedMission: Boolean = true,
        key: String = "vm",
        reportPresent: Boolean = true,
    ): CampaignDetailViewModel {
        val report = """{"id":"report","campaign_id":"campaign","user_id":"tester","status":"$reportStatus",
          "created_at":"${Instant.now()}","submission_context":"${if (reportMission == null) "app_test" else "mission"}",
          "mission_id":${reportMission?.let { "\"$it\"" } ?: "null"},"content":"old comment",
          "screenshot_url":"https://fixture.invalid/old.jpg","image_hash":"0123456789abcdef"}"""
        val detail = """{"campaign":{"id":"campaign","owner_id":"owner","app_name":"Fixture","status":"open"},
            "is_member":true,"reports":[${if (reportPresent) report else ""}]}"""
        val client = createSupabaseClient("https://fixture.invalid", "fixture-key") {
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            httpEngine = MockEngine { req ->
                val path = req.url.encodedPath
                val body = when {
                    path.endsWith("/get_campaign_detail") -> detail
                    path.endsWith("/get_campaign_mission") -> if (missionError) """{"message":"network unavailable"}"""
                        else {
                            val reqBody = req.body.toByteArray().decodeToString()
                            val requestedMissionId = Regex("\"p_mission_id\"\\s*:\\s*\"([^\"]+)\"").find(reqBody)?.groupValues?.get(1)
                            val missionId = requestedMissionId ?: returnedMission
                            """{"mission":{"id":"$missionId","campaign_id":"campaign","title":"Mission $missionId",
                                "status":"open","has_joined":$hasJoinedMission,"ends_at":"${Instant.now().plusSeconds(3600)}"}}"""
                        }
                    else -> "[]"
                }
                respond(body, if (missionError && path.endsWith("/get_campaign_mission")) HttpStatusCode.InternalServerError else HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        clients += client
        client.auth.awaitInitialization()
        client.auth.importSession(Json { ignoreUnknownKeys = true }.decodeFromString<UserSession>(
            """{"access_token":"fixture-access","refresh_token":"fixture-refresh","expires_in":3600,"token_type":"bearer","user":{"id":"tester","aud":"authenticated"}}"""))
        val vm = CampaignDetailViewModel("campaign", CampaignRepository(client, Localizer(context)), manager,
            "tester", Localizer(context), context, initialMissionId = initialMission)
        store.put(key, vm)
        until { vm.uiState.value.let { !it.isLoading && !it.isBackgroundSyncing && (!reportPresent || it.myTodayReport != null) } }
        return vm
    }

    private suspend fun until(predicate: () -> Boolean) {
        withTimeout(10_000) {
            while (!predicate()) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(20)
            }
        }
    }

    private suspend fun completeSubmission() {
        val id = manager.state.value!!.requestId!!
        val wm = WorkManager.getInstance(context)
        until { wm.getWorkInfosForUniqueWork("report_submit_$id").get().isNotEmpty() }
        val work = wm.getWorkInfosForUniqueWork("report_submit_$id").get().first()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work.id)
        gate.complete(ListenableWorker.Result.success(workDataOf(
            com.ntt.crosstest.work.ReportUploadWorker.KEY_REPORT_ID to "server-report",
            com.ntt.crosstest.work.ReportUploadWorker.KEY_STATUS to "pending"
        )))
        until { manager.currentStateForCampaign("campaign")?.stage == ReportSubmitManager.Stage.DONE }
    }

    @Test fun ordinaryPendingReportMustKeepItsTypeAfterJoiningMission() = runBlocking {
        val vm = viewModel()
        val accepted = vm.startSubmit()
        assertTrue("Submit must be accepted", accepted)
        assertNotNull(manager.state.value)
        assertFalse("Existing ordinary pending report is enqueued as a mission; backend rejects conversion",
            manager.state.value!!.isMissionReport)
    }
    @Test fun newDailyReportMustNotBecomeJoinedMission() = runBlocking {
        val vm = viewModel(reportPresent = false, hasJoinedMission = true)
        assertFalse(vm.uiState.value.isMissionReport)
        assertNull(vm.uiState.value.intendedMissionId)
        assertNull(vm.uiState.value.activeMission)
    }

    @Test fun explicitMissionMustNotSilentlyBecomeOrdinaryApprovedComment() = runBlocking {
        val vm = viewModel(reportStatus = "approved", initialMission = "mission-new")
        val accepted = vm.startSubmit()
        assertFalse("Explicit mission intent on approved ordinary report must be blocked before enqueue", accepted)
        val submitted = manager.state.value
        assertTrue("Explicit mission intent became ordinary comment update: $submitted",
            submitted == null || submitted.isMissionReport)
        assertNotNull("Must show conflict error to user", vm.uiState.value.submitError)
    }

    @Test fun missionContextMustMatchTheRequestedMissionIdentity() = runBlocking {
        val vm = viewModel(reportMission = "mission-old", initialMission = "mission-old")
        assertEquals("A campaign's newest mission is not necessarily the report's mission",
            vm.uiState.value.intendedMissionId, vm.uiState.value.activeMission?.id)
    }

    @Test fun approvedMissionCommentMustNotDependOnLiveMissionFetch() = runBlocking {
        val vm = viewModel(reportStatus = "approved", reportMission = "mission-old", initialMission = "mission-old", missionError = true)
        vm.onCommentChange("supplemental comment")
        val accepted = vm.startSubmit()
        assertTrue("Approved mission comment should be accepted even if live mission fetch fails", accepted)
        assertNotNull("Approved comment cannot enqueue when unrelated live mission context fails; error=${vm.uiState.value.submitError}",
            manager.state.value)
    }

    @Test fun formSubmissionBlockedKeepsFormAndDraft() = runBlocking {
        val vm = viewModel(reportStatus = "pending", initialMission = "mission-new")
        vm.onCommentChange("Unsent comment")
        val accepted = vm.startSubmit()
        assertFalse("Submission should be blocked due to converting pending report", accepted)
        assertEquals("Comment draft preserved", "Unsent comment", vm.uiState.value.myComment)
        assertNotNull("Must present submit error in state", vm.uiState.value.submitError)
    }

    @Test fun alreadyInFlightSubmissionReturnsConflictReason() = runBlocking {
        val vm = viewModel()
        val accepted1 = vm.startSubmit()
        assertTrue("First submit accepted", accepted1)

        val accepted2 = vm.startSubmit()
        assertFalse("Second submit while in-flight must be rejected", accepted2)
        assertNotNull("Must present in-flight error", vm.uiState.value.submitError)
    }

    @Test fun remainingForeignCompletionMustPreserveDraftInOtherViewModel() = runBlocking {
        val vm1 = viewModel(key = "owner")
        val vm2 = viewModel(key = "foreign")
        vm2.onCommentChange("unsent draft owned by VM2")
        assertTrue(vm1.startSubmit())
        until { vm2.uiState.value.submitStage == ReportSubmitManager.Stage.SENDING }
        completeSubmission()
        until { vm2.uiState.value.submitStage == ReportSubmitManager.Stage.DONE }
        assertEquals("A real completion from VM1 must not consume VM2's draft",
            "unsent draft owned by VM2", vm2.uiState.value.myComment)
    }

    @Test fun remainingCompletionMustPreserveNewerDraftInSameViewModel() = runBlocking {
        val vm = viewModel()
        vm.onCommentChange("submitted draft")
        assertTrue(vm.startSubmit())
        vm.onCommentChange("new draft typed while previous request sends")
        completeSubmission()
        until { vm.uiState.value.submitStage == ReportSubmitManager.Stage.DONE }
        assertEquals("Matching request ID alone does not authorize clearing a newer draft revision",
            "new draft typed while previous request sends", vm.uiState.value.myComment)
    }

    @Test fun remainingMissingScreenshotMustNotBeAcceptedBeforePersistence() = runBlocking {
        val file = java.io.File(context.noBackupFilesDir, "missing-audit-screenshot.jpg")
        assertFalse(file.exists())
        val result = manager.submit(ReportSubmitManager.SubmitRequest(
            "campaign", "app", "tester", 5, "draft", com.ntt.crosstest.util.PreparedScreenshot(
                uploadId = "image-id", file = file, imageHash = "0123456789abcdef", sizeBytes = 10L,
                contentType = "image/jpeg"
            ), null, null, false, clientSubmitId = "failed-persist"
        ))
        until { manager.state.value?.stage == ReportSubmitManager.Stage.FAILED }
        assertNull(PendingReportStore.read(context, "failed-persist"))
        assertFalse("Accepted is returned despite no durable request or enqueued worker: $result",
            result is ReportSubmitManager.SubmitAdmissionResult.Accepted)
    }

    @Test fun remainingExplicitDifferentMissionMustNotSubmitOldMission() = runBlocking {
        val vm = viewModel(reportMission = "mission-old", initialMission = "mission-new")
        vm.onCommentChange("submission intended for new mission")
        assertFalse("Opening mission-new while report belongs to mission-old must explain conflict, not enqueue an update to the old mission",
            vm.startSubmit())
    }

    @Test fun round2AdmissionMustNotPerformStorageIoOnCallingThread() = runBlocking {
        val caller = Thread.currentThread()
        val inlineIo = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val tracedContext = object : android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir(): java.io.File {
                if (Thread.currentThread() === caller) inlineIo.add(Thread.currentThread().name)
                return super.getNoBackupFilesDir()
            }
        }
        val tracedManager = ReportSubmitManager(tracedContext)
        try {
            inlineIo.clear()
            val result = tracedManager.submit(ReportSubmitManager.SubmitRequest(
                "thread-audit", "app", "tester", 5, "comment", null, null, null, false,
                clientSubmitId = "thread-audit-request"
            ))
            assertTrue(result is ReportSubmitManager.SubmitAdmissionResult.Accepted)
            assertTrue("Snapshot storage runs inline on the caller ($caller), also used by the Compose click handler: $inlineIo",
                inlineIo.isEmpty())
        } finally { tracedManager.cancelAllPendingWork() }
    }

    @Test fun round2NewDraftUsingSubmittedPhotoMustKeepItsPhotoAfterCompletion() = runBlocking {
        val vm = viewModel()
        val photo = java.io.File(context.noBackupFilesDir, "audit-retained-draft-photo.jpg").apply { writeText("image bytes") }
        val prepared = com.ntt.crosstest.util.PreparedScreenshot(
            uploadId = "draft-photo", file = photo, imageHash = "0123456789abcdef",
            sizeBytes = photo.length(), contentType = "image/jpeg"
        )
        val field = CampaignDetailViewModel::class.java.getDeclaredField("_uiState").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val state = field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<com.ntt.crosstest.ui.viewmodels.CampaignDetailUiState>
        vm.onCommentChange("submitted comment with photo")
        state.value = state.value.copy(preparedScreenshot = prepared, selectedScreenshotUri = android.net.Uri.fromFile(photo))
        assertTrue(vm.startSubmit())
        val requestId = manager.state.value!!.requestId!!
        vm.onCommentChange("new unsent comment still using the selected photo")
        // Execute the real worker's durable-receipt/image-cleanup step before WM success.
        PendingReportStore.markCompleted(context, requestId,
            SubmitTesterReportResult(success = true, reportId = "server-report"))
        completeSubmission()
        until { vm.uiState.value.submitStage == ReportSubmitManager.Stage.DONE }
        assertEquals("new unsent comment still using the selected photo", vm.uiState.value.myComment)
        assertTrue("The newer draft retained text but lost its selected photo: fileExists=" + photo.isFile + ", prepared=" + vm.uiState.value.preparedScreenshot,
            photo.isFile && vm.uiState.value.preparedScreenshot?.file?.absolutePath == photo.absolutePath)
    }

    @Test fun round3CancelAllMustPreventLateEnqueueFromAdmission() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val held = java.util.concurrent.atomic.AtomicBoolean(false)
        val tracedContext = object : android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir(): java.io.File {
                val isPersist = Thread.currentThread().stackTrace.any {
                    it.className == ReportSubmitManager::class.java.name && it.methodName == "persist"
                }
                if (isPersist && held.compareAndSet(false, true)) {
                    entered.complete(Unit)
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Audit persist gate timed out" }
                }
                return super.getNoBackupFilesDir()
            }
        }
        val target = ReportSubmitManager(tracedContext)
        val submission = async(Dispatchers.Default) {
            target.submit(ReportSubmitManager.SubmitRequest("cancel-audit", "app", "tester", 5, "private draft",
                null, null, null, false, clientSubmitId = "cancel-admission"))
        }
        try {
            withTimeout(5_000) { entered.await() }
            val cancelling = async(start = CoroutineStart.UNDISPATCHED) { target.cancelAllPendingWork() }
            withTimeoutOrNull(1_000) { cancelling.await() }
            release.countDown()
            cancelling.await()
            val outcome = try { submission.await() } catch (e: CancellationException) { e }
            val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("report_submit_cancel-admission").get()
            assertTrue("Cancellation completed but admission later returned " + outcome + " and left work=" + infos.map { it.state },
                infos.none { !it.state.isFinished } && target.state.value == null)
        } finally {
            release.countDown()
            submission.cancelAndJoin()
            target.cancelAllPendingWork()
        }
    }

    @Test fun round3DuplicateSubmitDuringPreparingDoesNotOverwriteOwnership() = runBlocking {
        val vm = viewModel()
        vm.onCommentChange("first draft")
        val accepted1 = vm.startSubmit()
        assertTrue("First submit should be accepted", accepted1)

        vm.onCommentChange("second draft while first is in flight")
        val accepted2 = vm.startSubmit()
        assertFalse("Second submit while first is preparing/sending must be rejected", accepted2)
        assertNotNull("In-flight error presented", vm.uiState.value.submitError)

        completeSubmission()
        until { vm.uiState.value.submitStage == ReportSubmitManager.Stage.DONE }
        assertEquals("Second draft must not be destroyed by AlreadyInFlight rejection",
            "second draft while first is in flight", vm.uiState.value.myComment)
    }

    @Test fun round3CallerCancellationDoesNotCancelManagerAdmission() = runBlocking {
        val id = "caller-cancel-admission"
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val held = java.util.concurrent.atomic.AtomicBoolean(false)
        val tracedContext = object : android.content.ContextWrapper(context) {
            override fun getNoBackupFilesDir(): java.io.File {
                val isPersist = Thread.currentThread().stackTrace.any {
                    it.className == ReportSubmitManager::class.java.name && it.methodName == "persist"
                }
                if (isPersist && held.compareAndSet(false, true)) {
                    entered.complete(Unit)
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Persist gate timed out" }
                }
                return super.getNoBackupFilesDir()
            }
        }
        manager = ReportSubmitManager(tracedContext)
        val callerJob = async(Dispatchers.Default) {
            manager.submit(ReportSubmitManager.SubmitRequest(
                "campaign-nav", "app", "tester", 5, "nav draft",
                null, null, null, false, clientSubmitId = id
            ))
        }
        try {
            // Wait until admission actually owns the request before cancelling its caller.
            withTimeout(5_000) { entered.await() }
            callerJob.cancel()
            val outcome = try { callerJob.await() } catch (e: CancellationException) { e }
            assertTrue("Caller receives CancellationException when UI wait is cancelled", outcome is CancellationException)
        } finally {
            release.countDown()
        }

        // But admission owned by manager scope continues to persist and enqueue into WorkManager
        val wm = WorkManager.getInstance(context)
        until { wm.getWorkInfosForUniqueWork("report_submit_$id").get().isNotEmpty() }
        val infos = wm.getWorkInfosForUniqueWork("report_submit_$id").get()
        assertTrue("Work must be properly enqueued by background manager", infos.any { it.state == WorkInfo.State.ENQUEUED })
        assertNotNull("Pending report store must persist the request", PendingReportStore.read(context, id))
    }
}
