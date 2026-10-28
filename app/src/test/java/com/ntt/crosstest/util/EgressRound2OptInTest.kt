package com.ntt.crosstest.util

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.AuthRepository
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class EgressRound2OptInTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val url = "https://firebasestorage.googleapis.com/v0/b/fixture/o/reports%2Fcampaign%2Ftester%2Foptin.jpg?alt=media&token=fixture"

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = false
        ScreenshotManager.clear(context)
        ImageInstrumentation.isEnabled = false
        ImageInstrumentation.reset()
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        bitmap.recycle()
        ScreenshotManager.customHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Cache-Control", "private, no-store")
                .body(bytes.toResponseBody()).build()
        }.build()
    }

    @After fun teardown() {
        ScreenshotManager.customHttpClient = null
        ScreenshotManager.clear(context)
        ImageInstrumentation.isEnabled = false
        ImageInstrumentation.reset()
        Dispatchers.resetMain()
    }

    @Test fun optedOutDirectManagerMemoryHitDoesNotCollect() = runTest {
        assertNotNull(ScreenshotManager.getOrFetchBitmap(context, url))
        ImageInstrumentation.reset()
        assertNotNull(ScreenshotManager.getOrFetchBitmap(context, url))
        assertEquals("Direct manager must honor diagnostic opt-out on RAM hits", 0, ImageInstrumentation.snapshot().memoryHits)
    }

    @Test fun optedOutRuntimeLoaderMemoryHitDoesNotCollect() = runTest {
        val loader = ImageLoader.Builder(context)
            .components { add(ScreenshotCoilInterceptor(context)) }
            .eventListener(ImageInstrumentation.createEventListener()).build()
        try {
            val request = ImageRequest.Builder(context).data(url).build()
            assertTrue(loader.execute(request) is SuccessResult)
            ImageInstrumentation.reset()
            assertTrue(loader.execute(request) is SuccessResult)
            assertEquals("Runtime screenshot interceptor must honor diagnostic opt-out", 0, ImageInstrumentation.snapshot().memoryHits)
        } finally {
            loader.shutdown()
        }
    }

    @Test fun contextlessSignOutThenContextfulSignOutStillPurgesDisk() = runTest {
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = true
        ScreenshotManager.onUserSessionChanged("round2-account", context)
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        bitmap.recycle()
        ScreenshotManager.customHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Cache-Control", "private, max-age=60")
                .body(bytes.toResponseBody()).build()
        }.build()
        assertNotNull(ScreenshotManager.getOrFetchBitmap(context, url,
            descriptor = ScreenshotDescriptor(url, Instant.now().toString(), false)))
        assertTrue("Fixture must exercise a real disk write",
            PrivateScreenshotDiskCache.cacheDir(context).walkTopDown().any { it.isFile && it.extension == "bin" })
        // Mirrors AuthRepository's contextless notification followed by an app/auth observer.
        ScreenshotManager.onUserSignedOut()
        ScreenshotManager.onUserSignedOut(context)
        assertFalse("Contextless notification must not lose the account whose files need purging",
            PrivateScreenshotDiskCache.cacheDir(context).walkTopDown().any { it.isFile && it.extension == "bin" })
    }

    @Test fun imageFetchSuccessDoesNotProveAnAppCheckTokenWasObserved() = runTest {
        AppCheckObservationConfig.reset()
        AppCheckObservationConfig.currentMode = AppCheckEnforcementMode.MONITOR_ONLY
        try {
            assertNotNull(ScreenshotManager.getOrFetchBitmap(context, url))
            assertEquals("HTTP success from a fixture contains no App Check token observation",
                0L, AppCheckObservationConfig.validTokensObserved.get())
        } finally {
            AppCheckObservationConfig.reset()
        }
    }

    @Test fun enabledButUnconfiguredGatewayDoesNotFallBackToPublicDownload() = runTest {
        ScreenshotCachePolicy.isMediaGatewayEnabled = true
        ScreenshotCachePolicy.mediaGatewayBaseUrl = ""
        try {
            assertNull("An enabled gateway without configuration must fail closed instead of using the public URL",
                ScreenshotManager.getOrFetchBitmap(context, url))
        } finally {
            ScreenshotCachePolicy.isMediaGatewayEnabled = false
            ScreenshotCachePolicy.mediaGatewayBaseUrl = ""
        }
    }

    @Test fun contextlessAccountSwitchDoesNotForgetPreviousAccountFiles() = runTest {
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = true
        ScreenshotManager.onUserSessionChanged("round2-account-a", context)
        val bitmap = Bitmap.createBitmap(20, 30, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        bitmap.recycle()
        ScreenshotManager.customHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Cache-Control", "private, max-age=60")
                .body(bytes.toResponseBody()).build()
        }.build()
        assertNotNull(ScreenshotManager.getOrFetchBitmap(context, url,
            descriptor = ScreenshotDescriptor(url, Instant.now().toString(), false)))
        val oldAccount = PrivateScreenshotDiskCache.accountDir(context, "round2-account-a")
        assertTrue(oldAccount.walkTopDown().any { it.isFile && it.extension == "bin" })
        ScreenshotManager.onUserSessionChanged("round2-account-b")
        ScreenshotManager.onUserSessionChanged("round2-account-b", context)
        assertFalse("A later context-aware event must still purge account A after a contextless switch to B",
            oldAccount.walkTopDown().any { it.isFile && it.extension == "bin" })
    }

    @Test fun appCheckObservationTracksTokensAndMaintainsMonitorOnlySafety() {
        AppCheckObservationConfig.reset()
        try {
            // 1. Kiểm tra init an toàn khi FirebaseApp chưa cấu hình cloud credentials
            AppCheckObservationConfig.currentMode = AppCheckEnforcementMode.MONITOR_ONLY
            AppCheckObservationConfig.initObservation(context)
            // Phải xử lý trạng thái an toàn (UNCONFIGURED khi thiếu credentials)
            assertTrue(
                AppCheckObservationConfig.observationState == AppCheckObservationState.UNCONFIGURED ||
                AppCheckObservationConfig.observationState == AppCheckObservationState.MONITORING
            )

            // 2. Telemetry tracking thông qua callback thực
            AppCheckObservationConfig.recordTokenObservation(true, "UnitTest")
            assertEquals(1L, AppCheckObservationConfig.validTokensObserved.get())
            AppCheckObservationConfig.recordTokenObservation(false, "UnitTest")
            assertEquals(1L, AppCheckObservationConfig.missingOrInvalidTokensObserved.get())

            // 3. MONITOR_ONLY: Tuyệt đối không chặn request người dùng thật
            AppCheckObservationConfig.currentMode = AppCheckEnforcementMode.MONITOR_ONLY
            assertTrue(AppCheckObservationConfig.isRequestAllowed(false))
            assertTrue(AppCheckObservationConfig.isRequestAllowed(true))

            // 4. ENFORCED: Chỉ cho phép khi có token hợp lệ
            AppCheckObservationConfig.currentMode = AppCheckEnforcementMode.ENFORCED
            assertFalse(AppCheckObservationConfig.isRequestAllowed(false))
            assertTrue(AppCheckObservationConfig.isRequestAllowed(true))
        } finally {
            AppCheckObservationConfig.reset()
        }
    }

    @Test fun runtimeAuthObserverBindsRestoredSessionAndPurgesOnSignOutAndSwitch() = runTest {
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = true
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val statusFlow = kotlinx.coroutines.flow.MutableStateFlow<io.github.jan.supabase.auth.status.SessionStatus>(
            io.github.jan.supabase.auth.status.SessionStatus.Initializing
        )
        var currentUserId: String? = null
        val job = TestCheoApp.observeAuthSession(this, context, statusFlow) { currentUserId }

        try {
            // 1. Phục hồi session user A từ async storage / importSession
            val sessionAJson = """{"access_token":"tok-a","refresh_token":"ref-a","token_type":"bearer","expires_in":3600,"user":{"id":"user-a","aud":"authenticated"}}"""
            val sessionA = json.decodeFromString<io.github.jan.supabase.auth.user.UserSession>(sessionAJson)
            currentUserId = "user-a"
            statusFlow.value = io.github.jan.supabase.auth.status.SessionStatus.Authenticated(sessionA)
            advanceUntilIdle()

            // Tạo file cache đĩa cho user-a
            val dirA = PrivateScreenshotDiskCache.accountDir(context, "user-a")
            dirA.mkdirs()
            val fileA = java.io.File(dirA, "entry.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            assertTrue(fileA.exists())

            // 2. Chuyển sang user B -> Observer tự động bắt event Authenticated user B,
            // dọn sạch toàn bộ file của user A
            val sessionBJson = """{"access_token":"tok-b","refresh_token":"ref-b","token_type":"bearer","expires_in":3600,"user":{"id":"user-b","aud":"authenticated"}}"""
            val sessionB = json.decodeFromString<io.github.jan.supabase.auth.user.UserSession>(sessionBJson)
            currentUserId = "user-b"
            statusFlow.value = io.github.jan.supabase.auth.status.SessionStatus.Authenticated(sessionB)
            advanceUntilIdle()

            assertFalse("Khi đổi sang user B, thư mục và file của user A phải được purge hoàn toàn",
                dirA.walkTopDown().any { it.isFile && it.extension == "bin" })

            // Tạo file cache đĩa cho user-b
            val dirB = PrivateScreenshotDiskCache.accountDir(context, "user-b")
            dirB.mkdirs()
            val fileB = java.io.File(dirB, "entry.bin").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            assertTrue(fileB.exists())

            // 3. User B đăng xuất -> Observer tự động bắt NotAuthenticated, purge sạch file của user B
            currentUserId = null
            statusFlow.value = io.github.jan.supabase.auth.status.SessionStatus.NotAuthenticated(false)
            advanceUntilIdle()

            assertFalse("Khi đăng xuất, thư mục và file của user B phải được purge sạch với Context",
                dirB.walkTopDown().any { it.isFile && it.extension == "bin" })
        } finally {
            job.cancel()
        }
    }
}
