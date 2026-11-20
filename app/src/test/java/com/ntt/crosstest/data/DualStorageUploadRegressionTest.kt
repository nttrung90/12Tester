package com.ntt.crosstest.data

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.ntt.crosstest.BuildConfig
import com.ntt.crosstest.util.PreparedScreenshot
import com.ntt.crosstest.util.ScreenshotCachePolicy
import com.ntt.crosstest.util.ScreenshotDescriptor
import com.ntt.crosstest.util.ScreenshotManager
import com.ntt.crosstest.util.ScreenshotRequestFactory
import com.ntt.crosstest.util.ScreenshotVariant
import com.ntt.crosstest.util.StripAuthOnCrossOriginRedirectInterceptor
import com.ntt.crosstest.util.SupabaseStorageHelper
import com.ntt.crosstest.work.ReportUploadOperations
import com.ntt.crosstest.work.ReportUploadWorker
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
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
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DualStorageUploadRegressionTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        PendingReportStore.clear(context)
        ScreenshotManager.clear(context)
    }

    @After
    fun tearDown() {
        PendingReportStore.clear(context)
        ScreenshotManager.clear(context)
        SupabaseStorageHelper.clientOverride = null
    }

    @Test
    fun testSupabaseVsGoogleRetentionCutoffCalculation() {
        val now = Instant.now()
        val created10DaysAgo = now.minus(Duration.ofHours(240)).toString()
        val created8DaysAgo = now.minus(Duration.ofHours(192)).toString()
        val created25DaysAgo = now.minus(Duration.ofDays(25)).toString()
        val created35DaysAgo = now.minus(Duration.ofDays(35)).toString()

        // 1. Supabase Report: 9-day (216 hours) cutoff
        val supabaseBase = BuildConfig.SUPABASE_URL.trimEnd('/')
        val supabaseReportOld = TesterReport(
            id = "rep-sup-old",
            campaignId = "camp-1",
            userId = "user-1",
            screenshotUrl = "$supabaseBase/storage/v1/object/public/report-screenshots/camp-1/rep-sup-old/full.webp",
            storageProvider = "supabase",
            createdAt = created10DaysAgo
        )
        assertTrue("Supabase report created 10 days ago (>216h) must be expired", supabaseReportOld.isScreenshotExpired)

        val supabaseReportFresh = TesterReport(
            id = "rep-sup-fresh",
            campaignId = "camp-1",
            userId = "user-1",
            screenshotUrl = "$supabaseBase/storage/v1/object/public/report-screenshots/camp-1/rep-sup-fresh/full.webp",
            storageProvider = "supabase",
            createdAt = created8DaysAgo
        )
        assertFalse("Supabase report created 8 days ago (<216h) must not be expired", supabaseReportFresh.isScreenshotExpired)

        // 2. Google Report: 30-day cutoff
        val googleReportOld = TesterReport(
            id = "rep-gcs-old",
            campaignId = "camp-1",
            userId = "user-1",
            screenshotUrl = "https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/camp-1/user-1/rep-gcs-old.jpg",
            storageProvider = "gcs",
            createdAt = created35DaysAgo
        )
        assertTrue("Google report created 35 days ago (>30d) must be expired", googleReportOld.isScreenshotExpired)

        val googleReportStillValidAt25Days = TesterReport(
            id = "rep-gcs-mid",
            campaignId = "camp-1",
            userId = "user-1",
            screenshotUrl = "https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/camp-1/user-1/rep-gcs-mid.jpg",
            storageProvider = "gcs",
            createdAt = created25DaysAgo
        )
        assertFalse("Google report created 25 days ago (<30d) must not be expired", googleReportStillValidAt25Days.isScreenshotExpired)
    }

    @Test
    fun testIndependentPerAttachmentExpiry() {
        val now = Instant.now()
        val created10DaysAgo = now.minus(Duration.ofHours(240)).toString()
        val created2DaysAgo = now.minus(Duration.ofHours(48)).toString()

        val attSupabaseExpired = ReportAttachment(
            id = "att-1",
            position = 0,
            screenshotUrl = "https://supabase.co/storage/v1/object/public/report-screenshots/camp/rep/att1.webp",
            storageProvider = "supabase",
            createdAt = created10DaysAgo,
            mediaExpired = null
        )
        val attSupabaseActive = ReportAttachment(
            id = "att-2",
            position = 1,
            screenshotUrl = "https://supabase.co/storage/v1/object/public/report-screenshots/camp/rep/att2.webp",
            storageProvider = "supabase",
            createdAt = created2DaysAgo,
            mediaExpired = null
        )

        assertTrue("Attachment 1 (10 days old) must be expired", attSupabaseExpired.isExpired)
        assertFalse("Attachment 2 (2 days old) must not be expired", attSupabaseActive.isExpired)

        // View expires at override
        val attExplicitExpiry = ReportAttachment(
            id = "att-3",
            position = 2,
            screenshotUrl = "https://supabase.co/storage/v1/object/public/report-screenshots/camp/rep/att3.webp",
            storageProvider = "supabase",
            createdAt = created2DaysAgo,
            viewExpiresAt = now.minusSeconds(60).toString()
        )
        assertTrue("Attachment with viewExpiresAt in the past must be expired", attExplicitExpiry.isExpired)
    }

    @Test
    fun testPendingReportStorePinningAcrossProcessDeath() {
        val requestId = "req-pinned-123"
        val attFile = PendingReportStore.newScreenshotFile(context, requestId).apply {
            writeText("dummy screenshot bytes")
        }

        val initialAttachment = PendingAttachment(
            id = "att-pin-0",
            position = 0,
            screenshotPath = attFile.absolutePath,
            imageHash = "1234567890abcdef",
            sha256 = PendingReportStore.screenshotSha256(attFile)
        )

        val report = PendingReport(
            id = requestId,
            campaignId = "camp-pin",
            appName = "App Pin",
            userId = "user-pin",
            rating = 5,
            comment = "Dual storage pinning verification",
            attachments = listOf(initialAttachment)
        )

        PendingReportStore.write(context, report)

        // Simulate reservation response pinning Supabase destination
        PendingReportStore.markAttachmentReservation(
            context = context,
            id = requestId,
            attachmentId = "att-pin-0",
            provider = "supabase",
            bucket = "report-screenshots",
            storagePath = "camp-pin/user-pin/att-pin-0.webp",
            thumbnailStoragePath = "camp-pin/user-pin/thumb_att-pin-0.webp",
            reservationId = "res-uuid-999"
        )

        // Read back as if a new worker or process started
        val restored = PendingReportStore.read(context, requestId)
        assertNotNull(restored)
        val restoredAtt = restored!!.attachments.first()

        assertEquals("supabase", restoredAtt.targetProvider)
        assertEquals("report-screenshots", restoredAtt.bucket)
        assertEquals("camp-pin/user-pin/att-pin-0.webp", restoredAtt.storagePath)
        assertEquals("camp-pin/user-pin/thumb_att-pin-0.webp", restoredAtt.thumbnailStoragePath)
        assertEquals("res-uuid-999", restoredAtt.reservationId)
    }

    @Test
    fun testScreenshotManagerRecognizesSupabasePathsAndExpiry() {
        val now = Instant.now()
        val created10DaysAgo = now.minus(Duration.ofHours(240)).toString()

        val supabaseBase = BuildConfig.SUPABASE_URL.trimEnd('/')
        val supabaseUrl = "$supabaseBase/storage/v1/object/public/report-screenshots/camp-1/rep-1/full.webp"
        val gcsUrl = "https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/camp-1/user-1/rep-1.jpg"

        assertTrue("Should recognize Supabase report URL", ScreenshotManager.isReportScreenshotUrl(supabaseUrl))
        assertTrue("Should recognize GCS report URL", ScreenshotManager.isReportScreenshotUrl(gcsUrl))

        // Expiry calculation in ScreenshotManager
        assertTrue(
            "Supabase URL at 10 days (>216h) must be expired in ScreenshotManager",
            ScreenshotManager.isExpired(
                url = supabaseUrl,
                createdAt = created10DaysAgo,
                provider = "supabase"
            )
        )

        assertFalse(
            "GCS URL at 10 days (<30d) must NOT be expired in ScreenshotManager",
            ScreenshotManager.isExpired(
                url = gcsUrl,
                createdAt = created10DaysAgo,
                provider = "gcs"
            )
        )
    }

    @Test
    fun testScreenshotRequestFactoryPopulatesProviderAndViewExpiresAt() {
        val now = Instant.now()
        val supabaseBase = BuildConfig.SUPABASE_URL.trimEnd('/')
        val report = TesterReport(
            id = "rep-factory-1",
            campaignId = "camp-fac",
            userId = "user-fac",
            screenshotUrl = "$supabaseBase/storage/v1/object/public/report-screenshots/camp-fac/rep-factory-1/full.webp",
            thumbnailUrl = "$supabaseBase/storage/v1/object/public/report-screenshots/camp-fac/rep-factory-1/thumb.webp",
            storageProvider = "supabase",
            viewExpiresAt = now.plus(Duration.ofHours(216)).toString(),
            createdAt = now.toString()
        )

        val desc = ScreenshotRequestFactory.createDescriptor(
            url = report.screenshotUrl,
            report = report,
            variant = ScreenshotVariant.FULL
        )

        assertNotNull(desc)
        assertEquals("supabase", desc!!.provider)
        assertEquals(report.viewExpiresAt, desc.viewExpiresAt)
        assertFalse(desc.mediaExpired ?: false)
    }

    @Test
    fun testReportUploadWorkerDispatchesToSupabaseWhenReserved() = runBlocking {
        val requestId = "req-sup-dispatch"
        val imageFile = PendingReportStore.newScreenshotFile(context, requestId).apply {
            writeText("dummy image content")
        }
        val thumbFile = PendingReportStore.newScreenshotFile(context, "thumb_$requestId").apply {
            writeText("dummy thumb content")
        }

        val pending = PendingReport(
            id = requestId,
            campaignId = "camp-dispatch",
            appName = "App Dispatch",
            userId = "user-dispatch",
            rating = 5,
            comment = "Testing Supabase dispatch routing",
            screenshotPath = imageFile.absolutePath,
            thumbnailPath = thumbFile.absolutePath,
            imageHash = "0000000000000000",
            screenshotSha256 = PendingReportStore.screenshotSha256(imageFile)
        )
        PendingReportStore.write(context, pending)

        var supabaseUploadCalled = 0
        var gcsUploadCalled = 0
        var submitCalled = 0

        val ops = object : ReportUploadOperations {
            override val isReady: Boolean = true
            override val currentUserId: String = "user-dispatch"

            override suspend fun reserve(
                campaignId: String,
                uploadId: String,
                estimatedSize: Long,
                hasThumbnail: Boolean,
                attachmentId: String?
            ): MediaReservationResult {
                return MediaReservationResult(
                    success = true,
                    provider = "supabase",
                    uploadId = uploadId,
                    bucket = "report-screenshots",
                    objectPath = "$campaignId/user-dispatch/$uploadId.webp",
                    thumbnailObjectPath = "$campaignId/user-dispatch/thumb_$uploadId.webp",
                    reservationId = "res-uuid-test"
                )
            }

            override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String {
                gcsUploadCalled++
                return "https://gcs.invalid/reports/image.jpg"
            }

            override suspend fun uploadSupabase(file: File, bucket: String, objectPath: String): String {
                supabaseUploadCalled++
                assertEquals("report-screenshots", bucket)
                assertTrue(objectPath.contains("camp-dispatch/user-dispatch"))
                val base = BuildConfig.SUPABASE_URL.trimEnd('/')
                return "$base/storage/v1/object/public/$bucket/$objectPath"
            }

            override suspend fun uploadSupabaseThumbnail(thumbnailFile: File, bucket: String, thumbnailPath: String): String? {
                assertEquals("report-screenshots", bucket)
                assertTrue(thumbnailPath.contains("thumb_"))
                val base = BuildConfig.SUPABASE_URL.trimEnd('/')
                return "$base/storage/v1/object/public/$bucket/$thumbnailPath"
            }

            override suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult {
                throw UnsupportedOperationException()
            }

            override suspend fun submit(
                request: PendingReport,
                url: String?,
                hash: String?,
                thumbnailUrl: String?
            ): SubmitTesterReportResult {
                submitCalled++
                assertNotNull(url)
                assertTrue(url!!.contains("report-screenshots"))
                assertNotNull(thumbnailUrl)
                assertTrue(thumbnailUrl!!.contains("thumb_"))
                return SubmitTesterReportResult(
                    success = true,
                    reportId = "server-rep-id-123",
                    provisionalGranted = true,
                    provisionalAmount = 10
                )
            }
        }

        val worker = TestListenableWorkerBuilder<ReportUploadWorker>(context)
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to requestId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    ReportUploadWorker(appContext, workerParameters, ops)
            }).build()

        val result = worker.doWork()
        assertTrue("Worker should succeed", result is ListenableWorker.Result.Success)
        assertEquals("Supabase upload must be called once", 1, supabaseUploadCalled)
        assertEquals("GCS upload must NOT be called", 0, gcsUploadCalled)
        assertEquals("Submit must be called once", 1, submitCalled)

        // Verify pinning was persisted
        val saved = PendingReportStore.read(context, requestId)
        assertNotNull(saved)
        assertEquals("supabase", saved!!.targetProvider)
        assertEquals("report-screenshots", saved.bucket)
        assertNotNull(saved.completedResult)
    }

    @Test
    fun testTrustedHostAuthTokenIsolation() {
        val supabaseBaseUrl = BuildConfig.SUPABASE_URL
        val supabaseHost = java.net.URI(supabaseBaseUrl).host
        assertNotNull("Supabase host must be present in BuildConfig", supabaseHost)

        // 1. Host Supabase chính thức chuẩn HTTPS -> Trusted
        val officialSupabaseUrl = "$supabaseBaseUrl/storage/v1/object/authenticated/report-screenshots/test.jpg"
        assertTrue(ScreenshotManager.isTrustedAuthHost(officialSupabaseUrl))

        // 2. Gateway host được cấu hình chuẩn HTTPS -> Trusted
        ScreenshotCachePolicy.mediaGatewayBaseUrl = "https://media-gateway.crosstest.internal/v1"
        try {
            val gatewayUrl = "https://media-gateway.crosstest.internal/v1/media?report_id=123"
            assertTrue(ScreenshotManager.isTrustedAuthHost(gatewayUrl))

            // 3. Scheme HTTP không phải localhost -> NOT trusted
            assertFalse(ScreenshotManager.isTrustedAuthHost("http://media-gateway.crosstest.internal/v1/media"))
            assertFalse(ScreenshotManager.isTrustedAuthHost("http://$supabaseHost/storage/v1/object/authenticated/test.jpg"))

            // 4. Sai port (khác default port) -> NOT trusted
            assertFalse(ScreenshotManager.isTrustedAuthHost("https://$supabaseHost:8443/storage/v1/object/authenticated/test.jpg"))

            // 5. Chứa userinfo -> NOT trusted
            assertFalse(ScreenshotManager.isTrustedAuthHost("https://user:pass@$supabaseHost/storage/v1/object/authenticated/test.jpg"))

            // 6. Localhost / emulator exceptions (cho phép HTTP)
            assertTrue(ScreenshotManager.extractCanonicalOrigin("http://localhost:54321/auth") != null)
            assertTrue(ScreenshotManager.extractCanonicalOrigin("http://10.0.2.2:54321/auth") != null)

            // 7. Google Storage host -> NOT trusted (không gửi Bearer JWT sang GCS)
            val gcsUrl = "https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/image.jpg"
            assertFalse(ScreenshotManager.isTrustedAuthHost(gcsUrl))

            // 8. External / Attacker host -> NOT trusted
            val attackerUrl = "https://attacker.example.com/steal-token"
            assertFalse(ScreenshotManager.isTrustedAuthHost(attackerUrl))

            val fakeSupabaseUrl = "https://${supabaseHost}.attacker.com/fake"
            assertFalse(ScreenshotManager.isTrustedAuthHost(fakeSupabaseUrl))

            // 9. Test StripAuthOnCrossOriginRedirectInterceptor không forward auth qua redirect sang origin khác
            val interceptor = StripAuthOnCrossOriginRedirectInterceptor()
            val untrustedReq = okhttp3.Request.Builder()
                .url(attackerUrl)
                .header("Authorization", "Bearer sensitive_token")
                .header("apikey", "sensitive_anon_key")
                .build()

            var forwardedReq: okhttp3.Request? = null
            val interceptorClient = OkHttpClient.Builder()
                .addInterceptor(StripAuthOnCrossOriginRedirectInterceptor())
                .addInterceptor { chain ->
                    forwardedReq = chain.request()
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("".toResponseBody())
                        .build()
                }.build()

            interceptorClient.newCall(untrustedReq).execute().close()
            assertNotNull(forwardedReq)
            assertNull("Authorization must be stripped on untrusted origin", forwardedReq!!.header("Authorization"))
            assertNull("apikey must be stripped on untrusted origin", forwardedReq!!.header("apikey"))
        } finally {
            ScreenshotCachePolicy.mediaGatewayBaseUrl = ""
        }
    }

    @Test
    fun testSupabaseStorageHelper409ConflictVerification() = runBlocking {
        val testFile = File(context.cacheDir, "test_conflict.jpg").apply {
            writeBytes(ByteArray(1024) { 0x42 }) // Kích thước 1024 bytes
        }

        try {
            // Case 1: 409 Conflict và GET xác nhận đúng nội dung (SHA256 khớp) -> Phục hồi idempotent thành công!
            val matchingClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.method == "POST") {
                    okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(409)
                        .message("Conflict")
                        .body("{\"error\":\"Duplicate\"}".toResponseBody("application/json".toMediaType()))
                        .build()
                } else if (request.method == "GET") {
                    okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Length", "1024")
                        .body(ByteArray(1024) { 0x42 }.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                } else {
                    chain.proceed(request)
                }
            }.build()

            SupabaseStorageHelper.clientOverride = matchingClient
            val successUrl = SupabaseStorageHelper.uploadReportScreenshot(
                file = testFile,
                bucket = "report-screenshots",
                objectPath = "test/conflict_ok.jpg",
                customToken = "valid_token"
            )
            assertTrue("Phục hồi idempotent phải trả về authenticated download URL", successUrl.contains("/storage/v1/object/authenticated/"))

            // Case 2: 409 Conflict, CÙNG KÍCH THƯỚC (1024 bytes) nhưng KHÁC NỘI DUNG (khác SHA256) -> Bắt buộc FAIL!
            val sameSizeMismatchClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.method == "POST") {
                    okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(409)
                        .message("Conflict")
                        .body("{\"error\":\"Duplicate\"}".toResponseBody("application/json".toMediaType()))
                        .build()
                } else if (request.method == "GET") {
                    okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Content-Length", "1024")
                        .body(ByteArray(1024) { 0x99.toByte() }.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                } else {
                    chain.proceed(request)
                }
            }.build()

            SupabaseStorageHelper.clientOverride = sameSizeMismatchClient
            try {
                SupabaseStorageHelper.uploadReportScreenshot(
                    file = testFile,
                    bucket = "report-screenshots",
                    objectPath = "test/conflict_same_size_diff_content.jpg",
                    customToken = "valid_token"
                )
                fail("Phải ném IOException khi remote object cùng kích thước nhưng khác nội dung SHA256")
            } catch (e: IOException) {
                assertTrue("Lỗi phải nêu rõ verification failed", e.message?.contains("supabase_upload_conflict_verification_failed") == true)
            }

            // Case 3: 409 Conflict nhưng GET trả về 404 Not Found -> Ném lỗi verification failed!
            val getNotFoundClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                if (request.method == "POST") {
                    okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(409)
                        .message("Conflict")
                        .body("{\"error\":\"Duplicate\"}".toResponseBody("application/json".toMediaType()))
                        .build()
                } else if (request.method == "GET") {
                    okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("".toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    chain.proceed(request)
                }
            }.build()

            SupabaseStorageHelper.clientOverride = getNotFoundClient
            try {
                SupabaseStorageHelper.uploadReportScreenshot(
                    file = testFile,
                    bucket = "report-screenshots",
                    objectPath = "test/conflict_404.jpg",
                    customToken = "valid_token"
                )
                fail("Phải ném IOException khi GET trả về 404")
            } catch (e: IOException) {
                assertTrue("Lỗi phải nêu rõ verification failed", e.message?.contains("supabase_upload_conflict_verification_failed") == true)
            }
        } finally {
            SupabaseStorageHelper.clientOverride = null
            testFile.delete()
        }
    }

    @Test
    fun testExpiredPinnedReservationReRequestedFromServer() = runBlocking {
        val requestId = "req-expired-res-1"
        val imageFile = PendingReportStore.newScreenshotFile(context, requestId).apply {
            writeText("expired reservation test file")
        }

        val twentyFiveMinutesAgo = System.currentTimeMillis() - Duration.ofMinutes(25).toMillis()
        val staleReport = PendingReport(
            id = requestId,
            campaignId = "camp-stale",
            appName = "App Stale",
            userId = "user-stale",
            rating = 5,
            comment = "Testing stale reservation renewal",
            screenshotPath = imageFile.absolutePath,
            imageHash = "1111222233334444",
            screenshotSha256 = PendingReportStore.screenshotSha256(imageFile),
            createdAt = twentyFiveMinutesAgo,
            targetProvider = "supabase",
            bucket = "report-screenshots",
            storagePath = "camp-stale/user-stale/old_path.webp",
            reservationId = "old-res-uuid"
        )
        PendingReportStore.write(context, staleReport)

        var reserveCallCount = 0
        var uploadedProvider = ""

        val ops = object : ReportUploadOperations {
            override val isReady: Boolean = true
            override val currentUserId: String = "user-stale"

            override suspend fun reserve(
                campaignId: String,
                uploadId: String,
                estimatedSize: Long,
                hasThumbnail: Boolean,
                attachmentId: String?
            ): MediaReservationResult {
                reserveCallCount++
                return MediaReservationResult(
                    success = true,
                    provider = "gcs",
                    uploadId = uploadId,
                    bucket = "cross-testers.firebasestorage.app",
                    objectPath = "reports/$campaignId/user-stale/$uploadId.jpg",
                    reservationId = "new-res-uuid"
                )
            }

            override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String {
                uploadedProvider = "gcs"
                return "https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/stale.jpg"
            }

            override suspend fun uploadSupabase(file: File, bucket: String, objectPath: String): String {
                uploadedProvider = "supabase"
                val base = BuildConfig.SUPABASE_URL.trimEnd('/')
                return "$base/storage/v1/object/public/$bucket/$objectPath"
            }

            override suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult {
                return SubmitTesterReportResult(success = true, reportId = "rep-renewed-1")
            }
        }

        val worker = TestListenableWorkerBuilder<ReportUploadWorker>(context)
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to requestId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    ReportUploadWorker(appContext, workerParameters, ops)
            }).build()

        val result = worker.doWork()
        assertTrue("Worker should succeed", result is ListenableWorker.Result.Success)
        assertEquals("Server reserve RPC must be called because reservation was stale (>20m)", 1, reserveCallCount)
        assertEquals("Upload destination must follow server contract (GCS overflow)", "gcs", uploadedProvider)
    }

    @Test
    fun testReportUploadWorkerFailsFastOnNonRetryableReservationError() = runBlocking {
        val requestId = "req-locked-user"
        val imageFile = PendingReportStore.newScreenshotFile(context, requestId).apply {
            writeText("locked user image")
        }

        val report = PendingReport(
            id = requestId,
            campaignId = "camp-lock",
            appName = "App Lock",
            userId = "user-locked",
            rating = 5,
            comment = "Account locked test",
            screenshotPath = imageFile.absolutePath,
            imageHash = "ffffffffffffffff",
            screenshotSha256 = PendingReportStore.screenshotSha256(imageFile)
        )
        PendingReportStore.write(context, report)

        val ops = object : ReportUploadOperations {
            override val isReady: Boolean = true
            override val currentUserId: String = "user-locked"

            override suspend fun reserve(
                campaignId: String,
                uploadId: String,
                estimatedSize: Long,
                hasThumbnail: Boolean,
                attachmentId: String?
            ): MediaReservationResult {
                // Giả lập server ném lỗi ACCOUNT_LOCKED từ Postgrest RPC
                throw IllegalStateException("account_locked")
            }

            override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String {
                fail("Upload must not be called when reservation failed")
                return ""
            }

            override suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult {
                fail("Submit must not be called when reservation failed")
                return SubmitTesterReportResult(success = false)
            }
        }

        val worker = TestListenableWorkerBuilder<ReportUploadWorker>(context)
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to requestId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    ReportUploadWorker(appContext, workerParameters, ops)
            }).build()

        val result = worker.doWork()
        assertTrue("Worker must fail immediately when ACCOUNT_LOCKED", result is ListenableWorker.Result.Failure)
    }

    @Test
    fun testUploadExpiredTrueWithRemoteVerifiedSubmitsWithoutNewPost() = runBlocking {
        val requestId = "req-upload-expired-verified"
        val imageFile = PendingReportStore.newScreenshotFile(context, requestId).apply {
            writeText("dummy screenshot bytes for expired verify")
        }

        val fortyMinutesAgo = System.currentTimeMillis() - java.time.Duration.ofMinutes(40).toMillis()
        val report = PendingReport(
            id = requestId,
            campaignId = "camp-exp-1",
            appName = "App Exp 1",
            userId = "user-exp-1",
            rating = 5,
            comment = "Upload expired verified test",
            screenshotPath = imageFile.absolutePath,
            imageHash = "1234567812345678",
            screenshotSha256 = PendingReportStore.screenshotSha256(imageFile),
            createdAt = fortyMinutesAgo,
            targetProvider = "supabase",
            bucket = "report-screenshots",
            storagePath = "camp-exp-1/user-exp-1/$requestId.webp"
        )
        PendingReportStore.write(context, report)

        var postUploadCalled = 0
        var verifyCalled = 0
        var submitCalled = 0

        val ops = object : ReportUploadOperations {
            override val isReady: Boolean = true
            override val currentUserId: String = "user-exp-1"

            override suspend fun reserve(
                campaignId: String,
                uploadId: String,
                estimatedSize: Long,
                hasThumbnail: Boolean,
                attachmentId: String?
            ): MediaReservationResult {
                return MediaReservationResult(
                    success = true,
                    provider = "supabase",
                    uploadId = uploadId,
                    bucket = "report-screenshots",
                    objectPath = "camp-exp-1/user-exp-1/$uploadId.webp",
                    pinned = true,
                    uploadExpired = true // Pinned reservation quá 30 phút!
                )
            }

            override suspend fun verifySupabase(file: File, bucket: String, objectPath: String): Boolean {
                verifyCalled++
                return true // Remote object đã tồn tại và SHA256 khớp!
            }

            override suspend fun uploadSupabase(file: File, bucket: String, objectPath: String): String {
                postUploadCalled++
                fail("uploadSupabase (POST) must NOT be called when uploadExpired=true")
                return ""
            }

            override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String {
                fail("Google upload must NOT be called")
                return ""
            }

            override suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult =
                throw UnsupportedOperationException()

            override suspend fun submit(request: PendingReport, url: String?, hash: String?, thumbnailUrl: String?): SubmitTesterReportResult {
                submitCalled++
                assertNotNull("Url must be resolved from verified object", url)
                assertTrue(url!!.contains("camp-exp-1/user-exp-1/$requestId.webp"))
                return SubmitTesterReportResult(success = true, reportId = "rep-exp-1")
            }
        }

        val worker = TestListenableWorkerBuilder<ReportUploadWorker>(context)
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to requestId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    ReportUploadWorker(appContext, workerParameters, ops)
            }).build()

        val result = worker.doWork()
        assertTrue("Worker must succeed via idempotent verification", result is ListenableWorker.Result.Success)
        assertEquals("Verify must be called once", 1, verifyCalled)
        assertEquals("Post upload must NOT be called", 0, postUploadCalled)
        assertEquals("Submit must be called once", 1, submitCalled)
    }

    @Test
    fun testUploadExpiredTrueWithRemoteMissingFailsTerminalWithoutSwitchingProvider() = runBlocking {
        val requestId = "req-upload-expired-missing"
        val imageFile = PendingReportStore.newScreenshotFile(context, requestId).apply {
            writeText("dummy screenshot bytes for missing remote")
        }

        val fortyMinutesAgo = System.currentTimeMillis() - java.time.Duration.ofMinutes(40).toMillis()
        val report = PendingReport(
            id = requestId,
            campaignId = "camp-exp-2",
            appName = "App Exp 2",
            userId = "user-exp-2",
            rating = 5,
            comment = "Upload expired missing test",
            screenshotPath = imageFile.absolutePath,
            imageHash = "8765432187654321",
            screenshotSha256 = PendingReportStore.screenshotSha256(imageFile),
            createdAt = fortyMinutesAgo,
            targetProvider = "supabase",
            bucket = "report-screenshots",
            storagePath = "camp-exp-2/user-exp-2/$requestId.webp"
        )
        PendingReportStore.write(context, report)

        var postUploadCalled = 0
        var verifyCalled = 0

        val ops = object : ReportUploadOperations {
            override val isReady: Boolean = true
            override val currentUserId: String = "user-exp-2"

            override suspend fun reserve(
                campaignId: String,
                uploadId: String,
                estimatedSize: Long,
                hasThumbnail: Boolean,
                attachmentId: String?
            ): MediaReservationResult {
                return MediaReservationResult(
                    success = true,
                    provider = "supabase",
                    uploadId = uploadId,
                    bucket = "report-screenshots",
                    objectPath = "camp-exp-2/user-exp-2/$uploadId.webp",
                    pinned = true,
                    uploadExpired = true // Pinned reservation quá 30 phút!
                )
            }

            override suspend fun verifySupabase(file: File, bucket: String, objectPath: String): Boolean {
                verifyCalled++
                return false // Object đã bị xóa hoặc không tồn tại trên remote!
            }

            override suspend fun uploadSupabase(file: File, bucket: String, objectPath: String): String {
                postUploadCalled++
                fail("uploadSupabase must not be called")
                return ""
            }

            override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String {
                fail("Should not switch to Google")
                return ""
            }

            override suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult =
                throw UnsupportedOperationException()

            override suspend fun submit(request: PendingReport, url: String?, hash: String?, thumbnailUrl: String?): SubmitTesterReportResult {
                fail("Submit must not be called when remote object is missing")
                return SubmitTesterReportResult(success = false)
            }
        }

        val worker = TestListenableWorkerBuilder<ReportUploadWorker>(context)
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to requestId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    ReportUploadWorker(appContext, workerParameters, ops)
            }).build()

        val result = worker.doWork()
        assertTrue("Worker must fail terminal when object is missing on upload_expired", result is ListenableWorker.Result.Failure)
        assertEquals("Verify must be called", 1, verifyCalled)
        assertEquals("POST upload must NOT be called", 0, postUploadCalled)

        // Phải giữ nguyên id/path, không tự đổi provider
        val saved = PendingReportStore.read(context, requestId)
        assertNotNull(saved)
        assertEquals("supabase", saved!!.targetProvider)
        assertEquals("camp-exp-2/user-exp-2/$requestId.webp", saved.storagePath)
        assertEquals("media_upload_expired_missing_remote", saved.terminalFailure?.code)
    }

    @Test
    fun testUploadExpiredTrueMultiAttachmentV3SubmitsWithoutNewPost() = runBlocking {
        val requestId = "req-upload-expired-v3"
        val attFile = PendingReportStore.newScreenshotFile(context, "att-v3-exp").apply {
            writeText("dummy screenshot bytes for v3 expired verify")
        }

        val attachment = PendingAttachment(
            id = "att-v3-exp-0",
            position = 0,
            screenshotPath = attFile.absolutePath,
            imageHash = "abcdef1234567890",
            sha256 = PendingReportStore.screenshotSha256(attFile),
            targetProvider = "supabase",
            bucket = "report-screenshots",
            storagePath = "camp-v3/user-v3/att-v3-exp-0.webp"
        )

        val fortyMinutesAgo = System.currentTimeMillis() - java.time.Duration.ofMinutes(40).toMillis()
        val report = PendingReport(
            id = requestId,
            campaignId = "camp-v3",
            appName = "App V3 Exp",
            userId = "user-v3",
            rating = 5,
            comment = "V3 Upload expired verified test",
            createdAt = fortyMinutesAgo,
            attachments = listOf(attachment)
        )
        PendingReportStore.write(context, report)

        var postUploadCalled = 0
        var verifyCalled = 0
        var submitV3Called = 0

        val ops = object : ReportUploadOperations {
            override val isReady: Boolean = true
            override val currentUserId: String = "user-v3"

            override suspend fun reserve(
                campaignId: String,
                uploadId: String,
                estimatedSize: Long,
                hasThumbnail: Boolean,
                attachmentId: String?
            ): MediaReservationResult {
                return MediaReservationResult(
                    success = true,
                    provider = "supabase",
                    uploadId = uploadId,
                    bucket = "report-screenshots",
                    objectPath = "camp-v3/user-v3/$uploadId.webp",
                    pinned = true,
                    uploadExpired = true // Pinned reservation quá 30 phút!
                )
            }

            override suspend fun verifySupabase(file: File, bucket: String, objectPath: String): Boolean {
                verifyCalled++
                return true // Remote object đã tồn tại và SHA256 khớp!
            }

            override suspend fun uploadSupabase(file: File, bucket: String, objectPath: String): String {
                postUploadCalled++
                fail("uploadSupabase must not be called when uploadExpired=true")
                return ""
            }

            override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String {
                fail("upload must not be called")
                return ""
            }

            override suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult =
                throw UnsupportedOperationException()

            override suspend fun submitV3(request: PendingReport, attachments: List<ReportAttachmentInput>, missionId: String?): SubmitTesterReportResult {
                submitV3Called++
                assertEquals(1, attachments.size)
                assertTrue(attachments.first().screenshotUrl.contains("camp-v3/user-v3/att-v3-exp-0.webp"))
                return SubmitTesterReportResult(success = true, reportId = "rep-v3-exp")
            }
        }

        val worker = TestListenableWorkerBuilder<ReportUploadWorker>(context)
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to requestId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    ReportUploadWorker(appContext, workerParameters, ops)
            }).build()

        val result = worker.doWork()
        assertTrue("Worker must succeed via idempotent verification", result is ListenableWorker.Result.Success)
        assertEquals("Verify must be called once", 1, verifyCalled)
        assertEquals("Post upload must NOT be called", 0, postUploadCalled)
        assertEquals("SubmitV3 must be called once", 1, submitV3Called)
    }

    @Test
    fun testGoogleOverflowBeyond30MinutesUploadsNormally() = runBlocking {
        val requestId = "req-gcs-beyond-30m"
        val imageFile = PendingReportStore.newScreenshotFile(context, requestId).apply {
            writeText("dummy gcs screenshot bytes")
        }

        val fortyMinutesAgo = System.currentTimeMillis() - java.time.Duration.ofMinutes(40).toMillis()
        val report = PendingReport(
            id = requestId,
            campaignId = "camp-gcs-overflow",
            appName = "App GCS",
            userId = "user-gcs",
            rating = 5,
            comment = "GCS beyond 30m normal upload test",
            screenshotPath = imageFile.absolutePath,
            imageHash = "aaaabbbbccccdddd",
            screenshotSha256 = PendingReportStore.screenshotSha256(imageFile),
            createdAt = fortyMinutesAgo,
            targetProvider = "gcs",
            bucket = "cross-testers.firebasestorage.app",
            storagePath = "reports/camp-gcs-overflow/user-gcs/$requestId.jpg"
        )
        PendingReportStore.write(context, report)

        var gcsUploadCalled = 0
        var submitCalled = 0

        val ops = object : ReportUploadOperations {
            override val isReady: Boolean = true
            override val currentUserId: String = "user-gcs"

            override suspend fun reserve(
                campaignId: String,
                uploadId: String,
                estimatedSize: Long,
                hasThumbnail: Boolean,
                attachmentId: String?
            ): MediaReservationResult {
                // Server 092: upload_expired chỉ true cho Supabase; GCS quá 30 phút giữ nguyên path/provider và upload bình thường
                return MediaReservationResult(
                    success = true,
                    provider = "gcs",
                    uploadId = uploadId,
                    bucket = "cross-testers.firebasestorage.app",
                    objectPath = "reports/$campaignId/user-gcs/$uploadId.jpg",
                    pinned = true,
                    uploadExpired = false
                )
            }

            override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String {
                gcsUploadCalled++
                return "https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/$campaignId/$userId/${prepared.uploadId}.jpg"
            }

            override suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult =
                throw UnsupportedOperationException()

            override suspend fun submit(request: PendingReport, url: String?, hash: String?, thumbnailUrl: String?): SubmitTesterReportResult {
                submitCalled++
                assertNotNull(url)
                assertTrue(url!!.contains("reports/camp-gcs-overflow/user-gcs/$requestId.jpg"))
                return SubmitTesterReportResult(success = true, reportId = "rep-gcs-ok")
            }
        }

        val worker = TestListenableWorkerBuilder<ReportUploadWorker>(context)
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to requestId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    ReportUploadWorker(appContext, workerParameters, ops)
            }).build()

        val result = worker.doWork()
        assertTrue("Worker must succeed via normal GCS upload", result is ListenableWorker.Result.Success)
        assertEquals("GCS upload must be called once", 1, gcsUploadCalled)
        assertEquals("Submit must be called once", 1, submitCalled)
    }

    @Test
    fun testVerifyRemoteObjectSha256TransientErrorsThrowAndTerminalReturnFalse() = runBlocking {
        val testFile = File(context.cacheDir, "verify_test_${System.currentTimeMillis()}.jpg").apply {
            writeText("correct content for sha256 verification")
        }
        val customToken = "test-jwt-token"
        val testUrl = "https://test.supabase.co/storage/v1/object/authenticated/bucket/path.jpg"

        // 1. HTTP 503 (Transient) -> Must throw IOException
        var mockStatus = 503
        var mockBody = "Service Unavailable"
        SupabaseStorageHelper.clientOverride = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(mockStatus)
                    .message("Mock HTTP $mockStatus")
                    .body(mockBody.toByteArray().toResponseBody("text/plain".toMediaType()))
                    .build()
            }.build()

        try {
            SupabaseStorageHelper.verifyRemoteObjectSha256(testFile, testUrl, customToken)
            fail("HTTP 503 must throw IOException")
        } catch (e: IOException) {
            assertTrue("Exception must mention temporary or HTTP 503: ${e.message}", e.message?.contains("503") == true)
        }

        // 2. HTTP 429 (Transient Rate Limit) -> Must throw IOException
        mockStatus = 429
        mockBody = "Too Many Requests"
        try {
            SupabaseStorageHelper.verifyRemoteObjectSha256(testFile, testUrl, customToken)
            fail("HTTP 429 must throw IOException")
        } catch (e: IOException) {
            assertTrue("Exception must mention temporary or HTTP 429: ${e.message}", e.message?.contains("429") == true)
        }

        // 3. Network IO Error -> Must throw IOException
        SupabaseStorageHelper.clientOverride = OkHttpClient.Builder()
            .addInterceptor { _ ->
                throw IOException("simulated connection reset by peer")
            }.build()
        try {
            SupabaseStorageHelper.verifyRemoteObjectSha256(testFile, testUrl, customToken)
            fail("Network IO error must throw IOException")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("connection reset") == true)
        }

        // 4. HTTP 404 (Terminal Missing) -> Must return false without throwing
        mockStatus = 404
        mockBody = "Not Found"
        SupabaseStorageHelper.clientOverride = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(mockStatus)
                    .message("Mock 404")
                    .body(mockBody.toByteArray().toResponseBody("text/plain".toMediaType()))
                    .build()
            }.build()
        val notFoundResult = SupabaseStorageHelper.verifyRemoteObjectSha256(testFile, testUrl, customToken)
        assertFalse("HTTP 404 must return false (terminal)", notFoundResult)

        // 5. HTTP 410 (Terminal Gone) -> Must return false without throwing
        mockStatus = 410
        mockBody = "Gone"
        val goneResult = SupabaseStorageHelper.verifyRemoteObjectSha256(testFile, testUrl, customToken)
        assertFalse("HTTP 410 must return false (terminal)", goneResult)

        // 6. HTTP 200 with different content (Hash mismatch) -> Must return false without throwing
        mockStatus = 200
        mockBody = "different remote content with different sha256"
        val mismatchResult = SupabaseStorageHelper.verifyRemoteObjectSha256(testFile, testUrl, customToken)
        assertFalse("Content mismatch must return false (terminal)", mismatchResult)

        // 7. HTTP 200 with exact matching content -> Must return true
        mockStatus = 200
        mockBody = "correct content for sha256 verification"
        val matchResult = SupabaseStorageHelper.verifyRemoteObjectSha256(testFile, testUrl, customToken)
        assertTrue("Exact matching remote content must return true", matchResult)
    }

    @Test
    fun testReportUploadWorkerRetriesOnVerificationIOExceptionWithoutPostingOrSwitching() = runBlocking {
        val requestId = "req-verify-io-error"
        val imageFile = PendingReportStore.newScreenshotFile(context, requestId).apply {
            writeText("dummy screenshot for retry verification")
        }

        val fortyMinutesAgo = System.currentTimeMillis() - java.time.Duration.ofMinutes(40).toMillis()
        val report = PendingReport(
            id = requestId,
            campaignId = "camp-retry-io",
            appName = "App Retry IO",
            userId = "user-io",
            rating = 5,
            comment = "Retry on verify IOException test",
            screenshotPath = imageFile.absolutePath,
            imageHash = "1234567890abcdef",
            screenshotSha256 = PendingReportStore.screenshotSha256(imageFile),
            createdAt = fortyMinutesAgo,
            targetProvider = "supabase",
            bucket = "report-screenshots",
            storagePath = "camp-retry-io/user-io/$requestId.webp"
        )
        PendingReportStore.write(context, report)

        var reserveCalled = 0
        var verifyCalled = 0
        var postUploadCalled = 0
        var googleUploadCalled = 0
        var submitCalled = 0

        val ops = object : ReportUploadOperations {
            override val isReady: Boolean = true
            override val currentUserId: String = "user-io"

            override suspend fun reserve(
                campaignId: String,
                uploadId: String,
                estimatedSize: Long,
                hasThumbnail: Boolean,
                attachmentId: String?
            ): MediaReservationResult {
                reserveCalled++
                return MediaReservationResult(
                    success = true,
                    provider = "supabase",
                    uploadId = uploadId,
                    bucket = "report-screenshots",
                    objectPath = "camp-retry-io/user-io/$uploadId.webp",
                    pinned = true,
                    uploadExpired = true // Pinned reservation quá 30 phút
                )
            }

            override suspend fun verifySupabase(file: File, bucket: String, objectPath: String): Boolean {
                verifyCalled++
                // Giả lập lỗi mạng tạm thời / 503 trong quá trình verify
                throw IOException("simulated_temporary_503_or_timeout")
            }

            override suspend fun uploadSupabase(file: File, bucket: String, objectPath: String): String {
                postUploadCalled++
                fail("uploadSupabase must not be called when verify throws IOException")
                return ""
            }

            override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String {
                googleUploadCalled++
                fail("Google upload must not be called on temporary Supabase verify error")
                return ""
            }

            override suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult =
                throw UnsupportedOperationException()

            override suspend fun submit(request: PendingReport, url: String?, hash: String?, thumbnailUrl: String?): SubmitTesterReportResult {
                submitCalled++
                fail("submit must not be called when verify fails")
                return SubmitTesterReportResult(success = false, reportId = "")
            }
        }

        val worker = TestListenableWorkerBuilder<ReportUploadWorker>(context)
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to requestId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    ReportUploadWorker(appContext, workerParameters, ops)
            }).build()

        val result = worker.doWork()
        assertTrue("Worker must retry on verification IOException: $result", result is ListenableWorker.Result.Retry)
        assertEquals("Reserve must be called once", 1, reserveCalled)
        assertEquals("Verify must be called once", 1, verifyCalled)
        assertEquals("Post upload must NOT be called", 0, postUploadCalled)
        assertEquals("Google upload must NOT be called", 0, googleUploadCalled)
        assertEquals("Submit must NOT be called", 0, submitCalled)

        // Đảm bảo không bị đánh dấu terminal failure trong PendingReportStore
        val updatedReport = PendingReportStore.read(context, requestId)
        assertNotNull(updatedReport)
        assertNull("Report must not be marked with terminal failure on retryable error", updatedReport?.terminalFailure)
    }

    @Test
    fun testHasThumbnailContractPreservedWhenLocalThumbnailMissing() = runBlocking {
        val requestId = "req-thumb-contract"
        val imageFile = PendingReportStore.newScreenshotFile(context, requestId).apply {
            writeText("dummy screenshot bytes")
        }

        val fortyMinutesAgo = System.currentTimeMillis() - java.time.Duration.ofMinutes(40).toMillis()
        // Giả lập report đã có thumbnailStoragePath được pin trước đó, nhưng file local thumbnail bị dọn dẹp (null)
        val report = PendingReport(
            id = requestId,
            campaignId = "camp-contract",
            appName = "App Contract",
            userId = "user-contract",
            rating = 5,
            comment = "Preserve thumbnail contract test",
            screenshotPath = imageFile.absolutePath,
            thumbnailPath = null, // local thumbnail file không còn
            imageHash = "contract12345678",
            screenshotSha256 = PendingReportStore.screenshotSha256(imageFile),
            createdAt = fortyMinutesAgo,
            targetProvider = "supabase",
            bucket = "report-screenshots",
            storagePath = "camp-contract/user-contract/$requestId.webp",
            thumbnailStoragePath = "camp-contract/user-contract/thumb-$requestId.webp" // contract đã pin
        )
        PendingReportStore.write(context, report)

        var capturedHasThumbnail: Boolean? = null

        val ops = object : ReportUploadOperations {
            override val isReady: Boolean = true
            override val currentUserId: String = "user-contract"

            override suspend fun reserve(
                campaignId: String,
                uploadId: String,
                estimatedSize: Long,
                hasThumbnail: Boolean,
                attachmentId: String?
            ): MediaReservationResult {
                capturedHasThumbnail = hasThumbnail
                return MediaReservationResult(
                    success = true,
                    provider = "supabase",
                    uploadId = uploadId,
                    bucket = "report-screenshots",
                    objectPath = "camp-contract/user-contract/$uploadId.webp",
                    thumbnailObjectPath = "camp-contract/user-contract/thumb-$uploadId.webp",
                    pinned = true,
                    uploadExpired = true
                )
            }

            override suspend fun verifySupabase(file: File, bucket: String, objectPath: String): Boolean {
                return true
            }

            override suspend fun uploadSupabase(file: File, bucket: String, objectPath: String): String {
                fail("uploadSupabase must not be called")
                return ""
            }

            override suspend fun upload(prepared: PreparedScreenshot, campaignId: String, userId: String): String {
                fail("upload must not be called")
                return ""
            }

            override suspend fun submit(request: PendingReport, url: String?, hash: String?): SubmitTesterReportResult =
                throw UnsupportedOperationException()

            override suspend fun submit(request: PendingReport, url: String?, hash: String?, thumbnailUrl: String?): SubmitTesterReportResult {
                return SubmitTesterReportResult(success = true, reportId = "rep-contract-ok")
            }
        }

        val worker = TestListenableWorkerBuilder<ReportUploadWorker>(context)
            .setInputData(workDataOf(ReportUploadWorker.EXTRA_REQUEST_ID to requestId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    ReportUploadWorker(appContext, workerParameters, ops)
            }).build()

        val result = worker.doWork()
        assertTrue("Worker must succeed: $result", result is ListenableWorker.Result.Success)
        assertEquals("hasThumbnail contract must be preserved as TRUE despite missing local thumb file", true, capturedHasThumbnail)
    }

    @Test
    fun testParentExpiredLiveChildAttachmentNotBlocked() {
        val now = Instant.now()
        val created10DaysAgo = now.minus(Duration.ofHours(240)).toString()
        val created2DaysAgo = now.minus(Duration.ofHours(48)).toString()
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')

        val liveChild = ReportAttachment(
            id = "att-child-live",
            position = 1,
            screenshotUrl = "$base/storage/v1/object/public/report-screenshots/camp/rep/child_live.webp",
            storageProvider = "supabase",
            createdAt = created2DaysAgo,
            mediaExpired = null
        )

        val parentReport = TesterReport(
            id = "rep-parent-expired",
            campaignId = "camp-1",
            userId = "user-1",
            screenshotUrl = "$base/storage/v1/object/public/report-screenshots/camp-1/rep-parent-expired/full.webp",
            storageProvider = "supabase",
            createdAt = created10DaysAgo,
            mediaExpired = null,
            attachments = listOf(liveChild)
        )

        // Cha hết hạn nhưng con còn sống
        assertTrue("Parent report must be expired (>216h)", parentReport.isScreenshotExpired)
        assertFalse("Child attachment must NOT be expired (<216h)", liveChild.isExpired)

        val childDescriptor = ScreenshotRequestFactory.createDescriptor(
            url = liveChild.screenshotUrl,
            report = parentReport,
            attachmentId = liveChild.id
        )

        assertNotNull(childDescriptor)
        assertEquals("att-child-live", childDescriptor?.attachmentId)
        assertEquals("Child must use own createdAt", created2DaysAgo, childDescriptor?.createdAt)
        assertEquals("Parent mediaExpired must not block live child", false, childDescriptor?.mediaExpired)
        assertFalse("ScreenshotManager must NOT report child as expired", ScreenshotManager.isExpired(childDescriptor))
    }

    @Test
    fun testParentLiveExpiredChildAttachmentBlocked() {
        val now = Instant.now()
        val created2DaysAgo = now.minus(Duration.ofHours(48)).toString()
        val created10DaysAgo = now.minus(Duration.ofHours(240)).toString()
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')

        val expiredChild = ReportAttachment(
            id = "att-child-expired",
            position = 1,
            screenshotUrl = "$base/storage/v1/object/public/report-screenshots/camp/rep/child_expired.webp",
            storageProvider = "supabase",
            createdAt = created10DaysAgo,
            mediaExpired = null
        )

        val parentReport = TesterReport(
            id = "rep-parent-live",
            campaignId = "camp-1",
            userId = "user-1",
            screenshotUrl = "$base/storage/v1/object/public/report-screenshots/camp-1/rep-parent-live/full.webp",
            storageProvider = "supabase",
            createdAt = created2DaysAgo,
            mediaExpired = null,
            attachments = listOf(expiredChild)
        )

        // Cha còn sống nhưng con đã hết hạn
        assertFalse("Parent report must be live (<216h)", parentReport.isScreenshotExpired)
        assertTrue("Child attachment must be expired (>216h)", expiredChild.isExpired)

        val childDescriptor = ScreenshotRequestFactory.createDescriptor(
            url = expiredChild.screenshotUrl,
            report = parentReport,
            attachmentId = expiredChild.id
        )

        assertNotNull(childDescriptor)
        assertEquals("Child must be flagged as mediaExpired", true, childDescriptor?.mediaExpired)
        assertTrue("ScreenshotManager must report child as expired", ScreenshotManager.isExpired(childDescriptor))
    }

    @Test
    fun testExplicitFutureViewExpiresAtWithOldReportNotExpired() {
        val now = Instant.now()
        val created20DaysAgo = now.minus(Duration.ofDays(20)).toString()
        val futureExpiry = now.plus(Duration.ofDays(5)).toString()
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')

        val report = TesterReport(
            id = "rep-future-renewed",
            campaignId = "camp-future",
            userId = "user-1",
            screenshotUrl = "$base/storage/v1/object/public/report-screenshots/camp-future/full.webp",
            storageProvider = "supabase",
            createdAt = created20DaysAgo,
            viewExpiresAt = futureExpiry
        )

        // viewExpiresAt trong tương lai là authoritative: KHÔNG bị createdAt cũ 20 ngày làm hết hạn!
        assertFalse("Report with future viewExpiresAt must NOT be expired", report.isScreenshotExpired)
        assertFalse("ScreenshotManager must NOT report it as expired", ScreenshotManager.isExpired(report))

        val desc = ScreenshotRequestFactory.createDescriptor(report.screenshotUrl, report)
        assertNotNull(desc)
        assertEquals(futureExpiry, desc?.viewExpiresAt)
        assertFalse("Descriptor must NOT be expired", ScreenshotManager.isExpired(desc))
    }

    @Test
    fun testGoogleFilenameContainingSupabaseDoesNotExpireAt216Hours() {
        val now = Instant.now()
        val created10DaysAgo = now.minus(Duration.ofHours(240)).toString()
        val gcsUrlWithSupabaseFilename = "https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/camp/user/my_supabase_report_screenshot.jpg"

        val report = TesterReport(
            id = "rep-gcs-filename",
            campaignId = "camp-gcs",
            userId = "user-gcs",
            screenshotUrl = gcsUrlWithSupabaseFilename,
            storageProvider = "gcs",
            createdAt = created10DaysAgo
        )

        // GCS provider có thời hạn 30 ngày: không bị tên file "supabase" đổi sang 216h!
        assertFalse("GCS report at 10 days must NOT be expired despite filename containing supabase", report.isScreenshotExpired)
        assertFalse("ScreenshotManager must NOT report it as expired", ScreenshotManager.isExpired(report))

        val desc = ScreenshotRequestFactory.createDescriptor(report.screenshotUrl, report)
        assertEquals("gcs", desc?.provider)
        assertFalse("Descriptor must NOT be expired", ScreenshotManager.isExpired(desc))
    }

    @Test
    fun testExactCutoffComparisonIsGreaterThanOrEqual() {
        val now = Instant.now()
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')

        // 1. Supabase exact cutoff: now - 216h
        val exactSupabase = now.minus(Duration.ofHours(216)).toString()
        val reportExact = TesterReport(
            id = "rep-exact",
            campaignId = "camp-exact",
            userId = "user-exact",
            screenshotUrl = "$base/storage/v1/object/public/report-screenshots/camp/full.webp",
            storageProvider = "supabase",
            createdAt = exactSupabase
        )
        assertTrue("Report created at exact 216h cutoff must be expired (>=)", reportExact.isScreenshotExpired)
        assertTrue("ScreenshotManager must report exact cutoff as expired", ScreenshotManager.isExpired(reportExact))

        // 2. Just before cutoff: now - 216h + 5 seconds
        val justBeforeSupabase = now.minus(Duration.ofHours(216)).plusSeconds(5).toString()
        val reportJustBefore = TesterReport(
            id = "rep-before",
            campaignId = "camp-before",
            userId = "user-before",
            screenshotUrl = "$base/storage/v1/object/public/report-screenshots/camp/full.webp",
            storageProvider = "supabase",
            createdAt = justBeforeSupabase
        )
        assertFalse("Report created just before 216h cutoff must NOT be expired", reportJustBefore.isScreenshotExpired)
        assertFalse("ScreenshotManager must report just before cutoff as not expired", ScreenshotManager.isExpired(reportJustBefore))
    }

    @Test
    fun testLiveGcsChildAttachmentWithParentSupabaseExpired() {
        val now = Instant.now()
        val created10DaysAgo = now.minus(Duration.ofHours(240)).toString()
        val base = BuildConfig.SUPABASE_URL.trimEnd('/')

        // Child là GCS không có viewExpiresAt, tạo 10 ngày trước (10 ngày < 30 ngày)
        val gcsChild = ReportAttachment(
            id = "att-gcs-child",
            position = 1,
            screenshotUrl = "https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/camp/user/att_gcs.jpg",
            provider = "gcs",
            storageProvider = "gcs",
            createdAt = created10DaysAgo,
            viewExpiresAt = null,
            mediaExpired = null
        )

        // Parent là Supabase, tạo 10 ngày trước (10 ngày > 216h = 9 ngày)
        val parentReport = TesterReport(
            id = "rep-sup-parent",
            campaignId = "camp-mixed",
            userId = "user-mixed",
            screenshotUrl = "$base/storage/v1/object/public/report-screenshots/camp-mixed/full.webp",
            storageProvider = "supabase",
            createdAt = created10DaysAgo,
            viewExpiresAt = null,
            attachments = listOf(gcsChild)
        )

        assertTrue("Parent Supabase at 10 days must be expired (>216h)", parentReport.isScreenshotExpired)
        assertFalse("Child GCS at 10 days must NOT be expired (<30d)", gcsChild.isExpired)

        val childDescriptor = ScreenshotRequestFactory.createDescriptor(
            url = gcsChild.screenshotUrl,
            report = parentReport,
            attachmentId = gcsChild.id
        )

        assertNotNull(childDescriptor)
        assertEquals("gcs", childDescriptor?.provider)
        assertNull("Child with null viewExpiresAt must not inherit parent viewExpiresAt", childDescriptor?.viewExpiresAt)
        assertEquals("Child must not inherit parent expired flag", false, childDescriptor?.mediaExpired)
        assertFalse("ScreenshotManager must NOT report GCS child as expired", ScreenshotManager.isExpired(childDescriptor))
    }
}
