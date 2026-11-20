package com.ntt.crosstest.util

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import com.ntt.crosstest.data.TesterReport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComparisonStateCoordinatorTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun largeLocalImageIsDecodedWithBoundedDimensions() {
        val file = java.io.File.createTempFile("comparison-large", ".png", app.cacheDir)
        val source = Bitmap.createBitmap(3072, 1536, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val decoded = ComparisonStateCoordinator.decodeLocalBitmap(app, android.net.Uri.fromFile(file))
            assertNotNull(decoded)
            try {
                assertTrue(maxOf(decoded!!.width, decoded.height) <= 1024)
                assertEquals(2f, decoded.width.toFloat() / decoded.height, 0.01f)
            } finally { decoded?.recycle() }
        } finally {
            source.recycle()
            file.delete()
        }
    }

    @Test
    fun supersededOperationIsNoLongerCurrent() {
        val op1 = ComparisonStateCoordinator.startOperation(null, null, "url1", "url2")
        assertTrue(ComparisonStateCoordinator.isCurrent(op1))

        val op2 = ComparisonStateCoordinator.startOperation(null, null, "url3", "url4")
        assertFalse("Old operation must not be current after new operation starts", ComparisonStateCoordinator.isCurrent(op1))
        assertTrue(ComparisonStateCoordinator.isCurrent(op2))
    }

    @Test
    fun sessionGenerationChangeCancelsCurrentOperation() {
        val initialGen = ScreenshotManager.sessionGeneration.get()
        val op = ComparisonStateCoordinator.startOperation(null, null, "url1", "url2")
        assertTrue(ComparisonStateCoordinator.isCurrent(op))

        // Simulate user logout/session change
        ScreenshotManager.sessionGeneration.incrementAndGet()
        assertFalse("Operation must be rejected when session generation changes (logout/login)", ComparisonStateCoordinator.isCurrent(op))

        // Reset generation back
        ScreenshotManager.sessionGeneration.set(initialGen)
    }

    @Test
    fun expiredReportFailsImmediately() = runBlocking {
        val expiredReport = TesterReport(
            id = "rep-exp",
            campaignId = "camp",
            userId = "user",
            mediaExpired = true,
            createdAt = "2026-01-01T00:00:00Z",
            screenshotUrl = "https://example.com/exp.jpg",
        )
        val validReport = TesterReport(
            id = "rep-val",
            campaignId = "camp",
            userId = "user",
            createdAt = "2026-10-30T00:00:00Z",
            screenshotUrl = "https://example.com/val.jpg",
        )

        val op = ComparisonStateCoordinator.startOperation(expiredReport, validReport, expiredReport.screenshotUrl, validReport.screenshotUrl)
        val b1 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val b2 = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        try {
            val result = ComparisonStateCoordinator.executeDetailedComparison(
                context = app,
                execution = op,
                report1 = expiredReport,
                report2 = validReport,
                image1 = b1,
                image2 = b2,
            )
            assertNull("Expired report must return null without publishing result", result)
        } finally {
            b1.recycle()
            b2.recycle()
        }
    }

    @Test
    fun tombstoned404UrlReturnsNull() = runBlocking {
        val url404 = "https://example.com/missing404.jpg"
        ScreenshotManager.mark404Unavailable(url404)

        val report1 = TesterReport(id = "r1", campaignId = "c", userId = "u", createdAt = "2026-10-30T00:00:00Z", screenshotUrl = url404)
        val report2 = TesterReport(id = "r2", campaignId = "c", userId = "u", createdAt = "2026-10-29T00:00:00Z", screenshotUrl = "https://example.com/ok.jpg")

        val op = ComparisonStateCoordinator.startOperation(report1, report2, url404, "https://example.com/ok.jpg")
        val b = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        try {
            val result = ComparisonStateCoordinator.executeDetailedComparison(
                context = app,
                execution = op,
                report1 = report1,
                report2 = report2,
                image1 = b,
                image2 = b,
            )
            assertNull("Tombstoned 404 URL must return null", result)
        } finally {
            b.recycle()
        }
    }

    @Test
    fun validExecutionReturnsDetailedResult() = runBlocking {
        val report1 = TesterReport(id = "r1", campaignId = "c", userId = "u", createdAt = "2026-10-30T00:00:00Z", screenshotUrl = "https://example.com/1.jpg")
        val report2 = TesterReport(id = "r2", campaignId = "c", userId = "u", createdAt = "2026-10-29T00:00:00Z", screenshotUrl = "https://example.com/2.jpg")

        val op = ComparisonStateCoordinator.startOperation(report1, report2, "https://example.com/1.jpg", "https://example.com/2.jpg")
        val b1 = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val b2 = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            val result = ComparisonStateCoordinator.executeDetailedComparison(
                context = app,
                execution = op,
                report1 = report1,
                report2 = report2,
                image1 = b1,
                image2 = b2,
            )
            assertNotNull(result)
            assertEquals(DetailedImageComparator.DetailedVerdict.DIFFERENT, result?.verdict)
        } finally {
            b1.recycle()
            b2.recycle()
        }
    }
}
