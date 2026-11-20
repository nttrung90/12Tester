package com.ntt.crosstest.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.ntt.crosstest.data.ReportAttachment
import com.ntt.crosstest.data.TesterReport
import kotlinx.coroutines.runBlocking
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
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectedMediaRegressionTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val childUrl = "https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/campaign/user/child.jpg"
    private fun report(childExpired: Boolean = false): TesterReport {
        return TesterReport(
            id = "selected-report", campaignId = "campaign", userId = "user",
            screenshotUrl = "https://storage.googleapis.com/cross-testers.firebasestorage.app/reports/campaign/user/parent.jpg",
            createdAt = Instant.now().minus(40, ChronoUnit.DAYS).toString(),
            mediaExpired = true, imageHash = "1111111111111111",
            viewExpiresAt = Instant.now().minusSeconds(1).toString(),
            attachments = listOf(ReportAttachment(
                id = "child", position = 1, screenshotUrl = childUrl,
                createdAt = Instant.now().minus(1, ChronoUnit.DAYS).toString(),
                provider = "gcs", mediaExpired = childExpired, imageHash = "2222222222222222"
            ))
        )
    }
    @Before fun before() {
        ScreenshotManager.clear(context)
        ScreenshotCachePolicy.isPrivateDiskCacheEnabled = false
        ScreenshotCachePolicy.isMediaGatewayEnabled = false
    }
    @After fun after() {
        ScreenshotManager.customHttpClient = null
        ScreenshotManager.clear(context)
    }
    @Test fun fetchUsesLiveChildInsteadOfExpiredParentMetadata() = runBlocking {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        var requests = 0
        ScreenshotManager.customHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").header("Cache-Control", "private, no-store")
                .body(bytes.toResponseBody()).build()
        }.build()
        val result = ScreenshotManager.getOrFetchBitmap(context, childUrl, report = report())
        assertNotNull("Actual fetch must honor selected child's metadata", result)
        assertEquals(1, requests)
    }
    @Test fun expiredChildStopsFetchBeforeNetwork() = runBlocking {
        var requests = 0
        ScreenshotManager.customHttpClient = OkHttpClient.Builder().addInterceptor { _ ->
            requests++
            throw AssertionError("Expired child must never reach network")
        }.build()
        assertNull(ScreenshotManager.getOrFetchBitmap(context, childUrl, report = report(true).copy(mediaExpired = false)))
        assertEquals(0, requests)
    }
    @Test fun selectedChildHashDoesNotReusePrimaryHash() = runBlocking {
        assertEquals("2222222222222222", ImageHashHelper.computeDHashFromUrl(context, childUrl, report()))
    }
    @Test fun detailedComparisonUsesSelectedChildLifetime() = runBlocking {
        val parent = report()
        val other = parent.copy(
            id = "other", mediaExpired = false,
            screenshotUrl = childUrl + "?other=1",
            createdAt = Instant.now().toString(),
            viewExpiresAt = Instant.now().plusSeconds(3600).toString(),
            attachments = emptyList()
        )
        val first = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val second = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            val execution = ComparisonStateCoordinator.startOperation(parent, other, childUrl, other.screenshotUrl)
            val result = ComparisonStateCoordinator.executeDetailedComparison(context, execution, parent, other, first, second)
            assertNotNull("Comparison must use selected live child, independently of parent", result)
        } finally {
            first.recycle()
            second.recycle()
        }
    }
}
