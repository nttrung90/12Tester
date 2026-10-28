package com.ntt.crosstest.util

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.request.SuccessResult
import com.ntt.crosstest.data.TesterReport
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
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
class EgressGatewayClientAuditTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val uploadId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val reportId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val rawUrl = "https://firebasestorage.googleapis.com/v0/b/cross-testers.firebasestorage.app/o/reports%2Fcampaign%2Ftester%2F$uploadId.jpg?alt=media&token=fixture"
    private val base = "https://gateway.fixture.invalid/v1/media-gateway"
    private val grantedIds = mutableListOf<String>()
    private val requests = mutableListOf<Request>()
    private fun report() = TesterReport(id=reportId,campaignId="campaign",userId="tester",screenshotUrl=rawUrl,createdAt=Instant.now().toString(),mediaExpired=false)

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = false
        ScreenshotManager.clear(context)
        ScreenshotManager.onUserSessionChanged("tester",context)
        ImageHashHelper.clearCache()
        ScreenshotCachePolicy.isMediaGatewayEnabled = true
        ScreenshotCachePolicy.mediaGatewayBaseUrl = base
        ScreenshotCachePolicy.mediaAccessGrantProvider = object : MediaAccessGrantProvider {
            override suspend fun getMediaAccessGrant(reportId: String): MediaAccessGrantResult {
                grantedIds += reportId
                return MediaAccessGrantResult(success=true,reportId=this@EgressGatewayClientAuditTest.reportId,role="author",
                    screenshotUrl="$base?report_id=${this@EgressGatewayClientAuditTest.reportId}&variant=full&expires_at=${Instant.now().epochSecond+300}&grant_token=fixture",
                    expiresAt=Instant.now().plusSeconds(300).toString(),accessGrantToken="fixture")
            }
            override fun getCurrentAuthToken() = "fixture-user-jwt"
        }
        val bitmap=Bitmap.createBitmap(20,30,Bitmap.Config.ARGB_8888)
        val bytes=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.JPEG,80,it)}.toByteArray()
        bitmap.recycle()
        ScreenshotManager.customHttpClient=OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Cache-Control","private, no-store").body(bytes.toResponseBody()).build()
        }.build()
    }
    @After fun cleanup() {
        ScreenshotCachePolicy.isMediaGatewayEnabled=false
        ScreenshotCachePolicy.mediaGatewayBaseUrl=""
        ScreenshotCachePolicy.mediaAccessGrantProvider=null
        ScreenshotManager.customHttpClient=null
        ScreenshotManager.clear(context)
        ImageHashHelper.clearCache()
        Dispatchers.resetMain()
    }
    @Test fun runtimeReportRequestUsesDatabaseReportIdentityAndBearer() = runTest {
        val loader=ImageLoader.Builder(context).components{add(ScreenshotCoilInterceptor(context))}.build()
        try {
            val request=ScreenshotRequestFactory.createRequest(context,report(),ScreenshotVariant.FULL)!!
            assertTrue(loader.execute(request) is SuccessResult)
            assertEquals("Upload/clientSubmitId is not the database report ID",listOf(reportId),grantedIds)
            assertEquals("gateway.fixture.invalid",requests.single().url.host)
            assertEquals("Bearer fixture-user-jwt",requests.single().header("Authorization"))
            assertEquals("fixture",requests.single().url.queryParameter("grant_token"))
        } finally {loader.shutdown()}
    }
    @Test fun legacyHashFallbackKeepsDatabaseReportIdentityForGatewayGrant() = runTest {
        assertNotNull(ImageHashHelper.computeDHashFromUrl(context,rawUrl,report()))
        assertEquals("Legacy hashing must use report identity, not infer it from an upload filename",listOf(reportId),grantedIds)
        assertEquals("gateway.fixture.invalid",requests.single().url.host)
        assertEquals("Bearer fixture-user-jwt",requests.single().header("Authorization"))
    }

    @Test fun mediaAccessGrantResultDecodesSnakeCasePayload() {
        val payload = """
            {
                "success": true,
                "report_id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                "role": "author",
                "screenshot_url": "https://gateway.example.com/v1?report_id=b&variant=full&expires_at=1800000000&grant_token=abc",
                "thumbnail_url": "https://gateway.example.com/v1?report_id=b&variant=thumb&expires_at=1800000000&grant_token=def",
                "expires_at": "2026-10-28T12:00:00Z",
                "expires_at_epoch": 1800000000,
                "access_grant_token": "abc"
            }
        """.trimIndent()
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<MediaAccessGrantResult>(payload)
        assertTrue(decoded.success)
        assertEquals("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", decoded.reportId)
        assertEquals("author", decoded.role)
        assertTrue(decoded.screenshotUrl?.contains("variant=full") == true)
        assertTrue(decoded.thumbnailUrl?.contains("variant=thumb") == true)
        assertEquals("2026-10-28T12:00:00Z", decoded.expiresAt)
        assertEquals(1800000000L, decoded.expiresAtEpoch)
        assertEquals("abc", decoded.accessGrantToken)
    }

    @Test fun supabaseMediaAccessGrantProviderRejectsBlankReportId() = runTest {
        val dummyClient = createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Postgrest)
            httpEngine = MockEngine {
                respond(
                    content = """{"success":true,"report_id":"rep-1","role":"author","screenshot_url":"https://gw/s","thumbnail_url":"https://gw/t","expires_at":"2026-10-28T12:00:00Z","expires_at_epoch":1800000000,"access_grant_token":"tok"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        val provider = com.ntt.crosstest.data.SupabaseMediaAccessGrantProvider(dummyClient)
        assertNull(provider.getMediaAccessGrant(""))
        assertNull(provider.getMediaAccessGrant("   "))
        val grant = provider.getMediaAccessGrant("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        assertNotNull(grant)
        assertEquals("rep-1", grant?.reportId)
        assertEquals("author", grant?.role)
        assertEquals("tok", grant?.accessGrantToken)
        assertNull(provider.getCurrentAuthToken())
    }
}
