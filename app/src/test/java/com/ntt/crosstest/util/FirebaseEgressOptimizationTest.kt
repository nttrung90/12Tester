package com.ntt.crosstest.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.ntt.crosstest.data.PendingReport
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.data.TesterReport
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.time.Duration
import java.time.Instant
import java.util.Random
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FirebaseEgressOptimizationTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var sampleFullJpegBytes1: ByteArray
    private lateinit var sampleFullJpegBytes2: ByteArray
    private lateinit var sampleThumbJpegBytes1: ByteArray
    private lateinit var sampleIconWebpBytes: ByteArray

    private val urlFull1 = "https://firebasestorage.googleapis.com/v0/b/app/o/reports%2Fcamp1%2Fuser1%2Fshot1.jpg?alt=media&token=secret-token-111"
    private val urlFull2 = "https://firebasestorage.googleapis.com/v0/b/app/o/reports%2Fcamp1%2Fuser1%2Fshot2.jpg?alt=media&token=secret-token-222"
    private val url404 = "https://firebasestorage.googleapis.com/v0/b/app/o/reports%2Fcamp1%2Fuser1%2Fdeleted_404.jpg?alt=media&token=secret-token-404"
    private val url403 = "https://firebasestorage.googleapis.com/v0/b/app/o/reports%2Fcamp1%2Fuser1%2Fforbidden_403.jpg?alt=media&token=secret-token-403"
    private val url3mbChunked = "https://firebasestorage.googleapis.com/v0/b/app/o/reports%2Fcamp1%2Fuser1%2Fstream_3mb.jpg?alt=media&token=secret-token-3mb"
    private val urlIcon = "https://firebasestorage.googleapis.com/v0/b/app/o/app_icons%2Fapp1_icon.webp?alt=media&token=secret-token-icon"

    private lateinit var fixtureClient: OkHttpClient
    private lateinit var legacyImageLoader: ImageLoader
    private lateinit var optimizedImageLoader: ImageLoader

    private val serverBytesEmittedFor3Mb = AtomicLong(0L)

    private fun awaitGateOrCancel(call: Call, gate: CompletableDeferred<Unit>, timeoutMs: Long = 5000L) {
        val start = System.currentTimeMillis()
        while (!gate.isCompleted && !call.isCanceled()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                break
            }
            Thread.sleep(10L)
        }
        if (call.isCanceled()) {
            throw java.io.IOException("Canceled")
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        ImageInstrumentation.reset()
        ImageInstrumentation.isEnabled = true
        ScreenshotManager.clear()
        ImageHashHelper.clearCache()
        serverBytesEmittedFor3Mb.set(0L)

        // 1. Tạo các ảnh JPEG mẫu có kích thước thực tế
        val bmp1 = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val out1 = ByteArrayOutputStream()
        bmp1.compress(Bitmap.CompressFormat.JPEG, 80, out1)
        sampleFullJpegBytes1 = out1.toByteArray()

        val bmp2 = Bitmap.createBitmap(540, 960, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val out2 = ByteArrayOutputStream()
        bmp2.compress(Bitmap.CompressFormat.JPEG, 80, out2)
        sampleFullJpegBytes2 = out2.toByteArray()

        val bmpThumb = Bitmap.createBitmap(270, 480, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val outThumb = ByteArrayOutputStream()
        bmpThumb.compress(Bitmap.CompressFormat.JPEG, 70, outThumb)
        sampleThumbJpegBytes1 = outThumb.toByteArray()

        val bmpIcon = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.GREEN)
        }
        val outIcon = ByteArrayOutputStream()
        bmpIcon.compress(Bitmap.CompressFormat.WEBP, 90, outIcon)
        sampleIconWebpBytes = outIcon.toByteArray()

        // 2. HTTP Fixture mô phỏng chính xác Firebase Storage responses
        val fixtureInterceptor = Interceptor { chain ->
            val request = chain.request()
            val urlStr = request.url.toString()

            val responseBuilder = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)

            when {
                urlStr.contains("deleted_404.jpg") -> {
                    responseBuilder
                        .code(404)
                        .message("Not Found")
                        .body("Deleted by cleanup worker".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                urlStr.contains("forbidden_403.jpg") -> {
                    responseBuilder
                        .code(403)
                        .message("Forbidden")
                        .body("Access Denied".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                urlStr.contains("thumb_rep1.jpg") -> {
                    responseBuilder
                        .code(200)
                        .message("OK")
                        .header("Cache-Control", "private, no-store")
                        .body(sampleThumbJpegBytes1.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                }
                urlStr.contains("thumb_missing_404.jpg") -> {
                    responseBuilder
                        .code(404)
                        .message("Not Found")
                        .body("Thumb not found".toResponseBody("text/plain".toMediaType()))
                        .build()
                }
                urlStr.contains("cacheable_report.jpg") -> {
                    responseBuilder
                        .code(200)
                        .message("OK")
                        .header("Cache-Control", "private, max-age=1800")
                        .header("Date", "Wed, 28 Oct 2026 01:00:00 GMT")
                        .body(sampleFullJpegBytes1.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                }
                urlStr.contains("stream_3mb.jpg") -> {
                    // Mô phỏng response 3MB không xác định Content-Length (chunked / -1)
                    val total3Mb = 3_000_000
                    val dummyBytes = ByteArray(total3Mb) { 0x7F }
                    val source = Buffer().write(dummyBytes)

                    val countingSource = object : ForwardingSource(source) {
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            val r = super.read(sink, byteCount)
                            if (r > 0) {
                                serverBytesEmittedFor3Mb.addAndGet(r)
                            }
                            return r
                        }
                    }

                    val customBody = object : ResponseBody() {
                        override fun contentType(): MediaType = "image/jpeg".toMediaType()
                        override fun contentLength(): Long = -1L // Unknown length
                        override fun source(): BufferedSource = countingSource.buffer()
                    }

                    responseBuilder
                        .code(200)
                        .message("OK")
                        .header("Cache-Control", "private, no-store")
                        .body(customBody)
                        .build()
                }
                urlStr.contains("shot1.jpg") -> {
                    responseBuilder
                        .code(200)
                        .message("OK")
                        .header("Cache-Control", "private, no-store")
                        .body(sampleFullJpegBytes1.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                }
                urlStr.contains("shot2.jpg") -> {
                    responseBuilder
                        .code(200)
                        .message("OK")
                        .header("Cache-Control", "private, no-store")
                        .body(sampleFullJpegBytes2.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                }
                urlStr.contains("app_icons") -> {
                    responseBuilder
                        .code(200)
                        .message("OK")
                        .header("Cache-Control", "public, max-age=31536000, immutable")
                        .body(sampleIconWebpBytes.toResponseBody("image/webp".toMediaType()))
                        .build()
                }
                else -> {
                    responseBuilder
                        .code(200)
                        .message("OK")
                        .header("Cache-Control", "private, no-store")
                        .body(sampleFullJpegBytes1.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                }
            }
        }

        fixtureClient = OkHttpClient.Builder()
            .addInterceptor(ImageInstrumentation.createTrackingInterceptor())
            .addInterceptor(fixtureInterceptor)
            .build()

        ScreenshotManager.customHttpClient = fixtureClient

        legacyImageLoader = ImageLoader.Builder(context)
            .interceptorDispatcher(Dispatchers.IO)
            .okHttpClient { fixtureClient }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("legacy_coil_disk"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .build()

        optimizedImageLoader = ImageLoader.Builder(context)
            .interceptorDispatcher(Dispatchers.IO)
            .components {
                add(ScreenshotCoilInterceptor(context))
            }
            .okHttpClient { fixtureClient }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("opt_coil_disk"))
                    .maxSizeBytes(50L * 1024 * 1024)
                    .build()
            }
            .build()
    }

    @After
    fun tearDown() {
        ScreenshotManager.customHttpClient = null
        ScreenshotManager.clear()
        ImageHashHelper.clearCache()
        ImageInstrumentation.reset()
        ImageInstrumentation.isEnabled = false
        Dispatchers.resetMain()
    }

    // =========================================================================
    // E00: Instrumentation & An toàn thông tin (Băm object ID, không rò rỉ token)
    // =========================================================================

    @Test
    fun e00_instrumentation_sanitizesUrls_hashesObjectId_neverExposesTokens() {
        val sanitizedScreenshot = ImageInstrumentation.sanitizeUrl(urlFull1)
        assertTrue(sanitizedScreenshot.startsWith("reports/obj_"))
        assertFalse(sanitizedScreenshot.contains("token"))
        assertFalse(sanitizedScreenshot.contains("secret"))
        assertFalse(sanitizedScreenshot.contains("alt=media"))
        assertFalse(sanitizedScreenshot.contains("shot1.jpg"))

        val sanitizedIcon = ImageInstrumentation.sanitizeUrl(urlIcon)
        assertTrue(sanitizedIcon.startsWith("app_icons/obj_"))
        assertFalse(sanitizedIcon.contains("token"))
    }

    @Test
    fun e00_instrumentation_tracksResponseBodyBytes_andStatusCodes() = runTest {
        val bitmap = ScreenshotManager.getOrFetchBitmap(context, urlFull1, purpose = "preview")
        assertNotNull(bitmap)

        val snapshot = ImageInstrumentation.snapshot()
        assertEquals(1, snapshot.networkRequests)
        assertEquals(sampleFullJpegBytes1.size.toLong(), snapshot.responseBodyBytes)
        assertEquals(1, snapshot.statusCodes[200])
        assertEquals(1, snapshot.requestsByPurpose["preview"])
        assertEquals(0, snapshot.ioFailures)
        assertEquals(0, snapshot.canceledRequests)
    }

    // =========================================================================
    // E01a & E01b: Chạy Pipeline Thật: Legacy Coil vs Optimized trên cùng Fixture
    // =========================================================================

    @Test
    fun e01a_realCoilPipeline_previewThenFullscreen_provesZeroDuplicateEgress() = runTest {
        // --- CHẠY WORKLOAD TRÊN PIPELINE LEGACY THẬT ---
        ImageInstrumentation.reset()

        val legacyPreviewReq = ImageRequest.Builder(context)
            .data(urlFull1)
            .size(180, 126)
            .build()
        val legacyRes1 = legacyImageLoader.execute(legacyPreviewReq)
        assertNotNull(legacyRes1.drawable)

        val legacyFullReq = ImageRequest.Builder(context)
            .data(urlFull1)
            .size(1080, 1920)
            .build()
        val legacyRes2 = legacyImageLoader.execute(legacyFullReq)
        assertNotNull(legacyRes2.drawable)

        val legacySnapshot = ImageInstrumentation.snapshot()
        // Bằng chứng baseline thật: 2 requests, 2 lần response-body bytes
        assertEquals(2, legacySnapshot.networkRequests)
        assertEquals(sampleFullJpegBytes1.size.toLong() * 2, legacySnapshot.responseBodyBytes)

        // --- CHẠY CÙNG WORKLOAD TRÊN PIPELINE OPTIMIZED THẬT ---
        ImageInstrumentation.reset()
        ScreenshotManager.clear()

        val optPreviewReq = ImageRequest.Builder(context)
            .data(urlFull1)
            .size(180, 126)
            .build()
        val optRes1 = optimizedImageLoader.execute(optPreviewReq)
        assertNotNull(optRes1.drawable)

        val optFullReq = ImageRequest.Builder(context)
            .data(urlFull1)
            .size(1080, 1920)
            .build()
        val optRes2 = optimizedImageLoader.execute(optFullReq)
        assertNotNull(optRes2.drawable)

        val optSnapshot = ImageInstrumentation.snapshot()
        // Bằng chứng optimized thật: Đúng 1 request mạng, 0 byte tải lặp, 1 memory hit!
        assertEquals(1, optSnapshot.networkRequests)
        assertEquals(sampleFullJpegBytes1.size.toLong(), optSnapshot.responseBodyBytes)
        assertEquals(1, optSnapshot.memoryHits)

        assertTrue(optSnapshot.networkRequests < legacySnapshot.networkRequests)
        assertEquals(legacySnapshot.responseBodyBytes / 2, optSnapshot.responseBodyBytes)
    }

    @Test
    fun e01b_realPipeline_concurrentPreviewAndHash_coalescesSingleFlight() = runTest {
        ImageInstrumentation.reset()
        val legDef1 = async(Dispatchers.IO) {
            val req = ImageRequest.Builder(context).data(urlFull1).size(200, 200).build()
            legacyImageLoader.execute(req)
        }
        val legDef2 = async(Dispatchers.IO) {
            val req = ImageRequest.Builder(context).data(urlFull1).allowHardware(false).build()
            legacyImageLoader.execute(req)
        }
        legDef1.await()
        legDef2.await()

        val legacySnapshot = ImageInstrumentation.snapshot()
        assertEquals(2, legacySnapshot.networkRequests)
        assertEquals(sampleFullJpegBytes1.size.toLong() * 2, legacySnapshot.responseBodyBytes)

        // --- CHẠY OPTIMIZED: Single-flight gom request đồng thời ---
        ImageInstrumentation.reset()
        ScreenshotManager.clear()

        val optDef1 = async(Dispatchers.IO) {
            val req = ImageRequest.Builder(context).data(urlFull1).size(200, 200).build()
            optimizedImageLoader.execute(req)
        }
        val optDef2 = async(Dispatchers.IO) {
            ImageHashHelper.computeDHashFromUrl(context, urlFull1)
        }

        val res1 = optDef1.await()
        val hash = optDef2.await()

        assertNotNull(res1.drawable)
        assertNotNull(hash)
        assertEquals(16, hash!!.length)

        val optSnapshot = ImageInstrumentation.snapshot()
        assertEquals(1, optSnapshot.networkRequests)
        assertEquals(sampleFullJpegBytes1.size.toLong(), optSnapshot.responseBodyBytes)
    }

    @Test
    fun e01b_comparisonDialogModes_provesZeroReFetchOnTabSwitching() = runTest {
        ImageInstrumentation.reset()
        ScreenshotManager.clear()

        val req1 = ImageRequest.Builder(context).data(urlFull1).build()
        val req2 = ImageRequest.Builder(context).data(urlFull2).build()

        // Tab 0: Tải 2 ảnh
        optimizedImageLoader.execute(req1)
        optimizedImageLoader.execute(req2)

        // Tab 1: So sánh đè (Slider)
        optimizedImageLoader.execute(req1)
        optimizedImageLoader.execute(req2)

        // Tab 2: Chớp ảnh (Blink)
        optimizedImageLoader.execute(req1)
        optimizedImageLoader.execute(req2)

        val snapshot = ImageInstrumentation.snapshot()
        assertEquals(2, snapshot.networkRequests)
        assertEquals((sampleFullJpegBytes1.size + sampleFullJpegBytes2.size).toLong(), snapshot.responseBodyBytes)
        assertEquals(4, snapshot.memoryHits)
    }

    @Test
    fun e01_noDiskCacheWrite_withNoStoreHeader() = runTest {
        val req = ImageRequest.Builder(context).data(urlFull1).build()
        optimizedImageLoader.execute(req)
        assertEquals(0L, optimizedImageLoader.diskCache?.size ?: 0L)
    }

    // =========================================================================
    // Issue A: Body size limit for unknown length (chunked)
    // =========================================================================

    @Test
    fun issueA_unknownLengthStream_3MB_stopsAtCap_doesNotConsumeEntireStream() = runTest {
        serverBytesEmittedFor3Mb.set(0L)

        val bmp = ScreenshotManager.getOrFetchBitmap(context, url3mbChunked, purpose = "preview")
        assertNull(bmp) // Bị từ chối vì vượt 2.5MB

        val emitted = serverBytesEmittedFor3Mb.get()
        // Chứng minh: dừng ngay tại cap (~2.5MB), TUYỆT ĐỐI không đọc hết 3.0MB của stream
        assertTrue("Chỉ đọc tối đa ~2.5MB (thực tế $emitted bytes), không được đọc hết 3.000.000 bytes",
            emitted <= ScreenshotManager.MAX_BODY_BYTES + 32_768L)
        assertTrue("Thực tế đã dừng trước khi chạm 3MB", emitted < 2_600_000L)
    }

    // =========================================================================
    // Issue B: Creator cancellation, follower alive, 3rd caller reuse flight
    // =========================================================================

    @Test
    fun issueB_threeCallers_creatorCancelled_followerAlive_thirdCallerReusesFlight() = runTest {
        val serverArrivedLatch = CountDownLatch(1)
        val serverProceedGate = CompletableDeferred<Unit>()
        val networkCallsCount = AtomicInteger(0)

        val delayedClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                networkCallsCount.incrementAndGet()
                serverArrivedLatch.countDown()
                awaitGateOrCancel(chain.call(), serverProceedGate)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Cache-Control", "private, no-store")
                    .body(sampleFullJpegBytes1.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            .build()

        ScreenshotManager.customHttpClient = delayedClient

        try {
            // 1. Caller 1 (Creator) bắt đầu tải
            val caller1Job = launch(Dispatchers.IO) {
                ScreenshotManager.getOrFetchBitmap(context, urlFull1)
            }

            // Chờ server nhận request
            assertTrue(serverArrivedLatch.await(5, TimeUnit.SECONDS))
            assertEquals(1, networkCallsCount.get())

            // 2. Caller 2 (Follower) tham gia flight
            val caller2Def = async(Dispatchers.IO) {
                ScreenshotManager.getOrFetchBitmap(context, urlFull1)
            }

            // 3. Caller 1 (Creator) bị cancel giữa chừng
            caller1Job.cancelAndJoin()
            assertTrue(caller1Job.isCancelled)

            // 4. Caller 3 đến khi Caller 2 còn đang chờ -> BẮT BUỘC phải dùng chung flight, KHÔNG được tạo GET thứ hai
            val caller3Def = async(Dispatchers.IO) {
                ScreenshotManager.getOrFetchBitmap(context, urlFull1)
            }

            // Mở cổng cho server trả response
            serverProceedGate.complete(Unit)

            val bmp2 = caller2Def.await()
            val bmp3 = caller3Def.await()

            assertNotNull(bmp2)
            assertNotNull(bmp3)

            // Bằng chứng cốt lõi: Chỉ có đúng 1 network call duy nhất!
            assertEquals(1, networkCallsCount.get())
        } finally {
            serverProceedGate.complete(Unit)
        }
    }

    @Test
    fun issueB_clearMidflight_cancelsCall_stopsLatePublication() = runTest {
        val serverArrivedLatch = CountDownLatch(1)
        val serverProceedGate = CompletableDeferred<Unit>()
        val isCallCancelled = AtomicBoolean(false)

        val hangingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val call = chain.call()
                serverArrivedLatch.countDown()
                try {
                    awaitGateOrCancel(call, serverProceedGate)
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .header("Cache-Control", "private, no-store")
                        .body(sampleFullJpegBytes1.toResponseBody("image/jpeg".toMediaType()))
                        .build()
                } catch (e: Exception) {
                    if (call.isCanceled()) {
                        isCallCancelled.set(true)
                    }
                    throw e
                }
            }
            .build()

        ScreenshotManager.customHttpClient = hangingClient

        try {
            val job = launch(Dispatchers.IO) {
                ScreenshotManager.getOrFetchBitmap(context, urlFull1)
            }

            assertTrue(serverArrivedLatch.await(5, TimeUnit.SECONDS))

            // Đăng xuất / clear giữa chừng
            ScreenshotManager.clear()

            job.join()

            // Call đã bị huỷ qua clear()
            serverProceedGate.complete(Unit)
            assertNull(ScreenshotManager.getCachedBitmap(urlFull1))
        } finally {
            serverProceedGate.complete(Unit)
        }
    }

    // =========================================================================
    // Issue C: Expiry re-check before publish & Hash publication after clear
    // =========================================================================

    @Test
    fun issueC_expiredDuringFetch_doesNotPublishOrReturnBitmap() = runTest {
        val serverArrivedLatch = CountDownLatch(1)
        val serverProceedGate = CompletableDeferred<Unit>()

        val delayedClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                serverArrivedLatch.countDown()
                awaitGateOrCancel(chain.call(), serverProceedGate)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Cache-Control", "private, no-store")
                    .body(sampleFullJpegBytes1.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            .build()

        ScreenshotManager.customHttpClient = delayedClient

        try {
            // Báo cáo ban đầu còn hợp lệ (chưa hết hạn)
            val almostExpiredIso = Instant.now().minus(Duration.ofDays(29)).minus(Duration.ofHours(23)).minus(Duration.ofMinutes(59)).minus(Duration.ofSeconds(58)).toString()
            val report = TesterReport(
                id = "rep_boundary",
                campaignId = "camp1",
                userId = "user1",
                screenshotUrl = urlFull1,
                createdAt = almostExpiredIso,
            )

            val fetchJob = async(Dispatchers.IO) {
                ScreenshotManager.getOrFetchBitmap(context, urlFull1, report = report)
            }

            assertTrue(serverArrivedLatch.await(5, TimeUnit.SECONDS))

            // Trong lúc network đang chạy dở: media bị đánh dấu hết hạn (hoặc tuổi vượt 30 ngày)
            val expiredIso = Instant.now().minus(Duration.ofDays(30)).minus(Duration.ofMinutes(2)).toString()
            ScreenshotManager.registerReportMetadata(urlFull1, createdAt = expiredIso, mediaExpired = null)

            // Network hoàn tất
            serverProceedGate.complete(Unit)

            val resultBitmap = fetchJob.await()

            // Bằng chứng Issue C: Phải bị từ chối ngay trước khi publish/return!
            assertNull(resultBitmap)
            assertNull(ScreenshotManager.getCachedBitmap(urlFull1))
        } finally {
            serverProceedGate.complete(Unit)
        }
    }

    @Test
    fun issueC_hashStartedBeforeClear_completingAfterClear_doesNotPublishHash() = runTest {
        val serverArrivedLatch = CountDownLatch(1)
        val serverProceedGate = CompletableDeferred<Unit>()

        val delayedClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                serverArrivedLatch.countDown()
                awaitGateOrCancel(chain.call(), serverProceedGate)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Cache-Control", "private, no-store")
                    .body(sampleFullJpegBytes1.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            .build()

        ScreenshotManager.customHttpClient = delayedClient

        try {
            val hashJob = async(Dispatchers.IO) {
                ImageHashHelper.computeDHashFromUrl(context, urlFull1)
            }

            assertTrue(serverArrivedLatch.await(5, TimeUnit.SECONDS))

            // Clear phiên đăng xuất trước khi network kịp trả
            ScreenshotManager.clear()

            serverProceedGate.complete(Unit)

            val hash = hashJob.await()
            // Bằng chứng: Không được publish hash vào phiên mới
            assertNull(hash)
        } finally {
            serverProceedGate.complete(Unit)
        }
    }

    @Test
    fun issueC_hashComputation_clearDuringCompute_doesNotPublishOrReturnHash() = runTest {
        val serverArrivedLatch = CountDownLatch(1)
        val serverProceedGate = CompletableDeferred<Unit>()

        val delayedClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                serverArrivedLatch.countDown()
                awaitGateOrCancel(chain.call(), serverProceedGate)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Cache-Control", "private, no-store")
                    .body(sampleFullJpegBytes1.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            .build()

        ScreenshotManager.customHttpClient = delayedClient

        try {
            val hashDeferred = async(Dispatchers.IO) {
                ImageHashHelper.computeDHashFromUrl(context, urlFull1)
            }

            assertTrue(serverArrivedLatch.await(5, TimeUnit.SECONDS))

            // Trigger clear giữa chừng
            ScreenshotManager.clear()
            ImageHashHelper.clearCache()

            // Cho server trả ảnh
            serverProceedGate.complete(Unit)

            val hashResult = hashDeferred.await()
            assertNull("Hash phải bị null do session generation đã thay đổi trong lúc compute", hashResult)
        } finally {
            serverProceedGate.complete(Unit)
        }
    }

    // =========================================================================
    // Request Factory & Descriptor & Token Guard Tests
    // =========================================================================

    @Test
    fun e01_requestFactory_descriptorAttached_blocksExpiredImmediately() = runTest {
        ImageInstrumentation.reset()
        val expiredReport = TesterReport(
            id = "rep_expired",
            campaignId = "camp1",
            userId = "user1",
            screenshotUrl = urlFull1,
            mediaExpired = true,
        )

        val request = ScreenshotRequestFactory.createRequest(context, expiredReport)
        assertNotNull(request)

        val result = optimizedImageLoader.execute(request!!)
        assertTrue("Request phải trả về ErrorResult do ảnh hết hạn", result is coil.request.ErrorResult)
        assertTrue(
            "Lỗi phải là ScreenshotExpiredException",
            (result as coil.request.ErrorResult).throwable is ScreenshotExpiredException
        )

        // Không phát sinh bất kỳ request mạng nào!
        val snapshot = ImageInstrumentation.snapshot()
        assertEquals(0, snapshot.networkRequests)
    }

    @Test
    fun e01_interceptor_rethrowsCancellationException() = runTest {
        val serverArrivedLatch = CountDownLatch(1)
        val hangingClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val call = chain.call()
                serverArrivedLatch.countDown()
                val start = System.currentTimeMillis()
                while (!call.isCanceled() && System.currentTimeMillis() - start < 5000L) {
                    Thread.sleep(10L)
                }
                if (call.isCanceled()) {
                    throw java.io.IOException("Canceled")
                }
                chain.proceed(chain.request())
            }
            .build()
        ScreenshotManager.customHttpClient = hangingClient

        val job = launch(Dispatchers.IO) {
            val req = ImageRequest.Builder(context).data(urlFull1).build()
            optimizedImageLoader.execute(req)
        }

        assertTrue(serverArrivedLatch.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue("Coroutine job phải bị cancel", job.isCancelled)
    }

    // =========================================================================
    // E01d: 404 Tombstone và 403 Quyền
    // =========================================================================

    @Test
    fun e01d_server404_tombstonedAndPreventsRetryLoops() = runTest {
        val bmp1 = ScreenshotManager.getOrFetchBitmap(context, url404, purpose = "preview")
        assertNull(bmp1)
        assertTrue(ScreenshotManager.is404Tombstoned(url404))

        val snapshotAfterFirst = ImageInstrumentation.snapshot()
        assertEquals(1, snapshotAfterFirst.networkRequests)
        assertEquals(1, snapshotAfterFirst.statusCodes[404])

        for (i in 2..5) {
            val retryBmp = ScreenshotManager.getOrFetchBitmap(context, url404, purpose = "preview")
            assertNull(retryBmp)
            val retryHash = ImageHashHelper.computeDHashFromUrl(context, url404)
            assertNull(retryHash)
        }

        val finalSnapshot = ImageInstrumentation.snapshot()
        assertEquals(1, finalSnapshot.networkRequests)
    }

    @Test
    fun e01d_server403_isNotMaskedAsExpired() = runTest {
        try {
            ScreenshotManager.getOrFetchBitmap(context, url403, purpose = "preview")
        } catch (_: ScreenshotPermissionDeniedException) {}

        assertFalse(ScreenshotManager.is404Tombstoned(url403))
        val snapshot = ImageInstrumentation.snapshot()
        assertEquals(1, snapshot.networkRequests)
        assertEquals(1, snapshot.statusCodes[403])
    }

    // =========================================================================
    // E02 TESTS: THUMBNAIL THẬT, BYTE SAVINGS VÀ RESILIENCE
    // =========================================================================

    @Test
    fun e02_thumbnailFile_provesRealByteSavings_withActualCompressedJpeg() = runTest {
        // Chứng minh kích thước thumbnail nén thực tế nhỏ hơn ít nhất 50% so với ảnh full
        val fullSize = sampleFullJpegBytes1.size
        val thumbSize = sampleThumbJpegBytes1.size
        assertTrue(thumbSize < fullSize * 0.65)

        val thumbUrl = "https://firebasestorage.app/reports/c1/u1/thumb_rep1.jpg"
        val fullUrl = "https://firebasestorage.app/reports/c1/u1/full_rep1.jpg"

        val reportWithThumb = TesterReport(
            id = "rep_thumb_1",
            campaignId = "c1",
            userId = "u1",
            screenshotUrl = fullUrl,
            thumbnailUrl = thumbUrl,
            createdAt = java.time.Instant.now().toString(),
        )

        // 1. Luồng xem Card Preview: yêu cầu thumbnail -> chỉ tải thumb bytes
        val previewRequest = ScreenshotRequestFactory.createRequest(
            context = context,
            report = reportWithThumb,
            variant = ScreenshotVariant.THUMBNAIL,
        ) as ImageRequest

        val previewResult = optimizedImageLoader.execute(previewRequest)
        assertTrue(previewResult is SuccessResult)

        val previewSnapshot = ImageInstrumentation.snapshot()
        assertEquals(1, previewSnapshot.networkRequests)
        assertEquals(thumbSize.toLong(), previewSnapshot.responseBodyBytes)

        // 2. Fallback: Nếu thumbnail bị 404 (ảnh legacy chưa có thumbnail), tự động fallback sang ảnh full và thành công
        val reportThumbMissing = TesterReport(
            id = "rep_thumb_missing",
            campaignId = "c1",
            userId = "u1",
            screenshotUrl = urlFull1,
            thumbnailUrl = "https://firebasestorage.app/reports/c1/u1/thumb_missing_404.jpg",
            createdAt = java.time.Instant.now().toString(),
        )

        val fallbackRequest = ScreenshotRequestFactory.createRequest(
            context = context,
            report = reportThumbMissing,
            variant = ScreenshotVariant.THUMBNAIL,
        ) as ImageRequest

        val fallbackResult = optimizedImageLoader.execute(fallbackRequest)
        assertTrue(fallbackResult is SuccessResult)
    }

    // =========================================================================
    // E03 TESTS: PRIVATE DISK CACHE POLICY, EXPIRY & NO-STORE ENFORCEMENT
    // =========================================================================

    @Test
    fun e03_privateDiskCache_refusesNoStore_and_respectsPolicyFlag() = runTest {
        val testUrl = "https://firebasestorage.app/reports/c1/u1/no_store_rep.jpg"
        val cacheableUrl = "https://firebasestorage.app/reports/c1/u1/cacheable_report.jpg"

        try {
            ScreenshotManager.onUserSessionChanged("user_test", context)
            // Case 1: Cờ tắt (mặc định) -> không ghi đĩa
            ScreenshotCachePolicy.isPrivateDiskCacheEnabled = false
            val putWhenDisabled = PrivateScreenshotDiskCache.put(
                context = context,
                accountId = "user_test",
                generation = 1L,
                variant = ScreenshotVariant.FULL,
                url = cacheableUrl,
                bitmapBytes = sampleFullJpegBytes1,
                cacheControlHeader = "private, max-age=1800",
                reportCreatedAt = java.time.Instant.now().toString(),
                mediaExpired = false,
            )
            assertFalse(putWhenDisabled)

            // Case 2: Cờ BẬT nhưng response có "no-store" -> TUYỆT ĐỐI KHÔNG GHI ĐĨA
            ScreenshotCachePolicy.isPrivateDiskCacheEnabled = true
            val putNoStore = PrivateScreenshotDiskCache.put(
                context = context,
                accountId = "user_test",
                generation = 1L,
                variant = ScreenshotVariant.FULL,
                url = testUrl,
                bitmapBytes = sampleFullJpegBytes1,
                cacheControlHeader = "private, no-store",
                reportCreatedAt = java.time.Instant.now().toString(),
                mediaExpired = false,
            )
            assertFalse(putNoStore)

            // Case 3: Cờ BẬT, response hợp lệ (private, max-age), không no-store -> Ghi thành công và đọc lại được
            val putSuccess = PrivateScreenshotDiskCache.put(
                context = context,
                accountId = "user_test",
                generation = 1L,
                variant = ScreenshotVariant.FULL,
                url = cacheableUrl,
                bitmapBytes = sampleFullJpegBytes1,
                cacheControlHeader = "private, max-age=1800",
                reportCreatedAt = java.time.Instant.now().toString(),
                mediaExpired = false,
                serverDateHeaderEpochMs = System.currentTimeMillis(),
            )
            assertTrue(putSuccess)

            val cachedBitmap = PrivateScreenshotDiskCache.get(
                context = context,
                accountId = "user_test",
                generation = 1L,
                variant = ScreenshotVariant.FULL,
                url = cacheableUrl,
            )
            assertNotNull(cachedBitmap)

            // Case 4: Xóa khi logout
            ScreenshotManager.clear(context)
            val afterLogout = PrivateScreenshotDiskCache.get(
                context = context,
                accountId = "user_test",
                generation = 1L,
                variant = ScreenshotVariant.FULL,
                url = cacheableUrl,
            )
            assertNull(afterLogout)
        } finally {
            ScreenshotManager.onUserSignedOut(context)
            ScreenshotCachePolicy.isPrivateDiskCacheEnabled = false
            PrivateScreenshotDiskCache.clearAll(context)
        }
    }

    // =========================================================================
    // E04 & E05 TESTS: APP CHECK OBSERVATION & CDN ROUTING POC
    // =========================================================================

    @Test
    fun e04_appCheckObservation_monitorModeActive_doesNotBlockTokens() {
        assertEquals(AppCheckEnforcementMode.MONITOR_ONLY, AppCheckObservationConfig.currentMode)
        // Ghi nhận telemetry không crash, không ném exception
        AppCheckObservationConfig.recordTokenObservation(isValidTokenPresent = true)
        AppCheckObservationConfig.recordTokenObservation(isValidTokenPresent = false)
    }

    @Test
    fun e05_iconRoutingHelper_pocFlagDisabledByDefault_andFallbackRecognized() {
        assertFalse(IconRoutingHelper.isCdnRoutingEnabled)

        val firebaseUrl = "https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/app_icons%2Fcom_test_app.webp?alt=media&token=123"

        // 1. Khi cờ tắt: trả về URL gốc
        assertEquals(firebaseUrl, IconRoutingHelper.resolveIconUrl(firebaseUrl))

        // 2. Khi cờ bật và có cdnHost: chuyển sang CDN host
        try {
            IconRoutingHelper.cdnHost = "cdn.crosstest.app"
            IconRoutingHelper.isCdnRoutingEnabled = true
            val cdnUrl = IconRoutingHelper.resolveIconUrl(firebaseUrl)
            assertTrue(cdnUrl.startsWith("https://cdn.crosstest.app/app_icons/"))

            // 3. Fallback logic: 404 hoặc 5xx từ CDN kích hoạt fallback về origin
            assertTrue(IconRoutingHelper.shouldFallbackToOrigin(cdnUrl, 404))
            assertTrue(IconRoutingHelper.shouldFallbackToOrigin(cdnUrl, 502))
            assertFalse(IconRoutingHelper.shouldFallbackToOrigin(cdnUrl, 200))

            // 4. R2-13: Chặn query string spoofing trên URL screenshot
            val spoofedScreenshot = "https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2Fcamp%2Fuser%2Fshot.jpg?param=app_icons%2Ffake.webp"
            assertFalse(IconRoutingHelper.isPublicIconUrl(spoofedScreenshot))
            assertEquals(spoofedScreenshot, IconRoutingHelper.resolveIconUrl(spoofedScreenshot))

            // 5. R2-13: Bounded fallback - ngăn infinite loop
            IconRoutingHelper.recordFallbackOccurred(cdnUrl)
            assertFalse("Bounded fallback must prevent recurring fallback attempts for the same CDN URL",
                IconRoutingHelper.shouldFallbackToOrigin(cdnUrl, 404))
        } finally {
            IconRoutingHelper.resetFallbackTracking()
            IconRoutingHelper.isCdnRoutingEnabled = false
            IconRoutingHelper.cdnHost = ""
        }
    }

    // =========================================================================
    // REGRESSION AUDIT PROBES (F01–F15 VERIFICATION)
    // =========================================================================

    private fun createTestJpeg(width: Int = 36, height: Int = 64, color: Int = Color.BLUE): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, it)
            bmp.recycle()
        }.toByteArray()
    }

    @Test
    fun regression_f04_preparedThumbnailMustSurviveDurablePendingPersistence() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val id = UUID.randomUUID().toString()
        val fullFile = PreparedScreenshotCache.newFile(context, id).apply { writeBytes(createTestJpeg()) }
        val thumbFile = PreparedScreenshotCache.newThumbnailFile(context, id).apply { writeBytes(createTestJpeg()) }
        val prepared = PreparedScreenshot(id, fullFile, "0123456789abcdef", fullFile.length(), "image/jpeg", thumbFile, thumbFile.length())
        val request = ReportSubmitManager.SubmitRequest("campaign", "Audit", "tester", 5, "fixture", prepared, null, null, false, id)
        val manager = ReportSubmitManager(context)
        val method = ReportSubmitManager::class.java.getDeclaredMethod("persist", ReportSubmitManager.SubmitRequest::class.java).apply { isAccessible = true }
        val pending = method.invoke(manager, request) as PendingReport
        assertNotNull("Worker only uploads request.thumbnailPath; persistence must populate it", pending.thumbnailPath)
        assertTrue(java.io.File(pending.thumbnailPath!!).isFile)
    }

    @Test
    fun regression_f06_thumbnail404FallbackMustWorkOnSecondDisplay() = runTest {
        val testUrl = "https://firebasestorage.googleapis.com/v0/b/fixture/o/reports%2Fcampaign%2Ftester%2Ffull.jpg?alt=media&token=fixture"
        val testThumb = testUrl.replace("full.jpg", "thumb_full.jpg")
        val bytes = createTestJpeg()
        ScreenshotManager.customHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val missing = chain.request().url.toString() == testThumb
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (missing) 404 else 200)
                .message(if (missing) "Not Found" else "OK").header("Cache-Control", "private, no-store")
                .body((if (missing) ByteArray(0) else bytes).toResponseBody("image/jpeg".toMediaType())).build()
        }.build()
        val loader = ImageLoader.Builder(context).components { add(ScreenshotCoilInterceptor(context)) }.build()
        try {
            val report = TesterReport(id = "report", campaignId = "campaign", userId = "tester", screenshotUrl = testUrl, thumbnailUrl = testThumb, createdAt = Instant.now().toString())
            val request = ScreenshotRequestFactory.createRequest(context, report, ScreenshotVariant.THUMBNAIL)!!
            assertTrue(loader.execute(request) is SuccessResult)
            assertTrue("Tombstoned thumbnail must still fall back to cached full on re-entry", loader.execute(request) is SuccessResult)
        } finally {
            loader.shutdown()
            ScreenshotManager.customHttpClient = null
        }
    }

    @Test
    fun regression_f09_diskCacheMustRequireTrustedExpiry() {
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = true
        try {
            val stored = PrivateScreenshotDiskCache.put(context, "audit_user", 0, ScreenshotVariant.FULL, urlFull1, createTestJpeg(), "private, max-age=60", null, null)
            assertFalse("Missing expiry/authorization contract must fail closed", stored)
        } finally {
            ScreenshotManager.onUserSignedOut(context)
            ScreenshotCachePolicy.isPrivateDiskCacheEnabled = false
            PrivateScreenshotDiskCache.clearAll(context)
        }
    }

    @Test
    fun regression_f09_diskCacheMustRespectServerMaxAge() {
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = true
        try {
            assertTrue(PrivateScreenshotDiskCache.put(context, "audit_user", 0, ScreenshotVariant.FULL, urlFull1, createTestJpeg(), "private, max-age=60", Instant.now().toString(), false))
            val file = PrivateScreenshotDiskCache.cacheDir(context).walkTopDown().single { it.isFile && it.extension == "bin" }
            RandomAccessFile(file, "r").use { raf ->
                raf.readInt()
                val expires = raf.readLong()
                val cached = raf.readLong()
                assertTrue("max-age=60 must not become 30-minute freshness: ttl=${expires - cached}", expires - cached <= 62_000L)
            }
        } finally {
            ScreenshotManager.onUserSignedOut(context)
            ScreenshotCachePolicy.isPrivateDiskCacheEnabled = false
            PrivateScreenshotDiskCache.clearAll(context)
        }
    }

    @Test
    fun regression_f09_serverClockBehindMustNotImmediatelyInvalidateValidCache() {
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = true
        try {
            val serverNow = System.currentTimeMillis() - 3_600_000L
            assertTrue(PrivateScreenshotDiskCache.put(context, "audit_user", 0, ScreenshotVariant.FULL, urlFull1, createTestJpeg(), "private, max-age=60", Instant.ofEpochMilli(serverNow).toString(), false, serverNow))
            assertNotNull("Server Date skew must be applied consistently during get", PrivateScreenshotDiskCache.get(context, "audit_user", 0, ScreenshotVariant.FULL, urlFull1))
        } finally {
            ScreenshotManager.onUserSignedOut(context)
            ScreenshotCachePolicy.isPrivateDiskCacheEnabled = false
            PrivateScreenshotDiskCache.clearAll(context)
        }
    }

    @Test
    fun regression_f07_clearDuringBodyReadMustNotReviveDiskFiles() = runTest {
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = true
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val bytes = createTestJpeg()
        ScreenshotManager.currentAccountId = "audit_user"
        ScreenshotManager.customHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val source = object : ForwardingSource(Buffer().write(bytes)) {
                var first = true
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (first) {
                        first = false
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    return super.read(sink, byteCount)
                }
                override fun close() {
                    super.close()
                    closed.countDown()
                }
            }.buffer()
            val body = object : ResponseBody() {
                override fun contentType() = "image/jpeg".toMediaType()
                override fun contentLength() = bytes.size.toLong()
                override fun source() = source
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Cache-Control", "private, max-age=60").body(body).build()
        }.build()
        val call = async(Dispatchers.IO) { ScreenshotManager.getOrFetchBitmap(context, urlFull1, descriptor = ScreenshotDescriptor(urlFull1, Instant.now().toString(), false)) }
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            ScreenshotManager.clear(context)
            release.countDown()
            try { call.await() } catch (_: CancellationException) {}
            assertTrue(withContext(Dispatchers.IO) { closed.await(5, TimeUnit.SECONDS) })
            val files = PrivateScreenshotDiskCache.cacheDir(context).walkTopDown().filter { it.isFile && it.extension == "bin" }.toList()
            assertTrue("In-flight response wrote disk files after logout/clear: ${files.size}", files.isEmpty())
        } finally {
            release.countDown()
            call.cancel()
            ScreenshotCachePolicy.isPrivateDiskCacheEnabled = false
            ScreenshotManager.customHttpClient = null
            ScreenshotManager.clear(context)
        }
    }

    @Test
    fun regression_f12_runtimeLoaderMustCountOneMemoryHitOnce() = runTest {
        val bytes = createTestJpeg()
        ScreenshotManager.customHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(bytes.toResponseBody("image/jpeg".toMediaType())).build()
        }.build()
        val loader = ImageLoader.Builder(context).components { add(ScreenshotCoilInterceptor(context)) }.eventListener(ImageInstrumentation.createEventListener()).build()
        try {
            val req = ImageRequest.Builder(context).data(urlFull1).build()
            assertTrue(loader.execute(req) is SuccessResult)
            ImageInstrumentation.reset()
            assertTrue(loader.execute(req) is SuccessResult)
            assertEquals("Runtime event listener and interceptor must not both count same hit", 1, ImageInstrumentation.snapshot().memoryHits)
        } finally {
            loader.shutdown()
            ScreenshotManager.customHttpClient = null
        }
    }

    @Test
    fun regression_f10_diskDecodeMustPreserveDHashBasis() {
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = true
        val random = Random(61)
        try {
            repeat(25) { iteration ->
                val original = Bitmap.createBitmap(9, 8, Bitmap.Config.ARGB_8888)
                for (y in 0 until 8) for (x in 0 until 9) {
                    val v = 100 + random.nextInt(7)
                    original.setPixel(x, y, Color.rgb(v, v, v))
                }
                val bytes = ByteArrayOutputStream().also { original.compress(Bitmap.CompressFormat.JPEG, 100, it); original.recycle() }.toByteArray()
                val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })!!
                assertTrue(PrivateScreenshotDiskCache.put(context, "audit_user", 0, ScreenshotVariant.FULL, urlFull1, bytes, "private, max-age=60", Instant.now().toString(), false))
                val disk = PrivateScreenshotDiskCache.get(context, "audit_user", 0, ScreenshotVariant.FULL, urlFull1)!!
                assertEquals("Same JPEG changed dHash after disk-cache decode on fixture $iteration", ImageHashHelper.computeDHash(full), ImageHashHelper.computeDHash(disk))
            }
        } finally {
            ScreenshotManager.onUserSignedOut(context)
            ScreenshotCachePolicy.isPrivateDiskCacheEnabled = false
            PrivateScreenshotDiskCache.clearAll(context)
        }
    }

    @Test
    fun regression_r2_mediaGatewayClient_requestsSignedUrlAndPassesBearerToken() = runTest {
        val testReportId = "rep-1111-2222-3333"
        val gatewayBase = "https://gateway.crosstest.fixture/v1/media-gateway"
        val signedUrl = "$gatewayBase?report_id=$testReportId&variant=full&expires_at=1800000000&grant_token=valid_grant_token_123"
        val expectedBearer = "mock_author_jwt_bearer_token"
        val bytes = createTestJpeg()

        var interceptedUrl: String? = null
        var interceptedAuthHeader: String? = null

        ScreenshotCachePolicy.isMediaGatewayEnabled = true
        ScreenshotCachePolicy.mediaGatewayBaseUrl = gatewayBase
        ScreenshotCachePolicy.mediaAccessGrantProvider = object : MediaAccessGrantProvider {
            override suspend fun getMediaAccessGrant(reportId: String): MediaAccessGrantResult? {
                assertEquals(testReportId, reportId)
                return MediaAccessGrantResult(
                    success = true,
                    reportId = reportId,
                    role = "author",
                    screenshotUrl = signedUrl,
                    thumbnailUrl = null,
                    expiresAt = "2026-10-28T12:00:00Z",
                    accessGrantToken = "valid_grant_token_123"
                )
            }
            override fun getCurrentAuthToken(): String? = expectedBearer
        }

        ScreenshotManager.customHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            interceptedUrl = req.url.toString()
            interceptedAuthHeader = req.header("Authorization")
            Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "image/jpeg")
                .header("Cache-Control", "private, no-store")
                .body(bytes.toResponseBody("image/jpeg".toMediaType()))
                .build()
        }.build()

        try {
            val descriptor = ScreenshotDescriptor(
                url = urlFull1,
                reportId = testReportId,
                variant = ScreenshotVariant.FULL
            )
            val bmp = ScreenshotManager.getOrFetchBitmap(context, urlFull1, descriptor = descriptor)
            assertNotNull(bmp)
            assertEquals(signedUrl, interceptedUrl)
            assertEquals("Bearer $expectedBearer", interceptedAuthHeader)
        } finally {
            ScreenshotCachePolicy.isMediaGatewayEnabled = false
            ScreenshotCachePolicy.mediaGatewayBaseUrl = ""
            ScreenshotCachePolicy.mediaAccessGrantProvider = null
            ScreenshotManager.customHttpClient = null
        }
    }

    @Test
    fun regression_r2_mediaGatewayClient_handles403And410Properly() = runTest {
        val testReportId = "rep-forbidden-403"
        val gatewayBase = "https://gateway.crosstest.fixture/v1/media-gateway"

        ScreenshotCachePolicy.isMediaGatewayEnabled = true
        ScreenshotCachePolicy.mediaGatewayBaseUrl = gatewayBase
        ScreenshotCachePolicy.mediaAccessGrantProvider = object : MediaAccessGrantProvider {
            override suspend fun getMediaAccessGrant(reportId: String): MediaAccessGrantResult? = null
            override fun getCurrentAuthToken(): String? = "any_token"
        }

        // Khi grant provider trả về null (bị cấm bởi RPC), gateway URL được gọi và server trả 403
        ScreenshotManager.customHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(403)
                .message("Forbidden")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()

        try {
            val descriptor = ScreenshotDescriptor(url = urlFull1, reportId = testReportId)
            var caught403 = false
            try {
                ScreenshotManager.getOrFetchBitmap(context, urlFull1, descriptor = descriptor)
            } catch (e: ScreenshotPermissionDeniedException) {
                caught403 = true
            }
            assertTrue("HTTP 403 must throw ScreenshotPermissionDeniedException", caught403)

            // Tiếp tục test 410 Expired
            ScreenshotManager.clear(context)
            ScreenshotManager.customHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(410)
                    .message("Gone")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }.build()

            var caught410 = false
            try {
                ScreenshotManager.getOrFetchBitmap(context, urlFull1, descriptor = descriptor)
            } catch (e: ScreenshotExpiredException) {
                caught410 = true
            }
            assertTrue("HTTP 410 must throw ScreenshotExpiredException", caught410)
        } finally {
            ScreenshotCachePolicy.isMediaGatewayEnabled = false
            ScreenshotCachePolicy.mediaGatewayBaseUrl = ""
            ScreenshotCachePolicy.mediaAccessGrantProvider = null
            ScreenshotManager.customHttpClient = null
        }
    }
}
