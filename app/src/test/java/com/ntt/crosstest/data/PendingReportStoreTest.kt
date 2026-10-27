package com.ntt.crosstest.data

import android.app.Application
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PendingReportStoreTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { PendingReportStore.clear(context) }
    @After fun cleanup() { PendingReportStore.clear(context) }
    private fun report() = PendingReport("id", "campaign", "app", "user", 5, "feedback")
    @Test fun legacyJsonRemainsReadable() {
        File(PendingReportStore.directory(context), "id.json").writeText(
            """{"id":"id","campaignId":"campaign","appName":"app","userId":"user","rating":5,"comment":"feedback"}"""
        )
        val restored = PendingReportStore.read(context, "id")!!
        assertNull(restored.completedResult)
        assertNull(restored.screenshotSha256)
    }
    @Test fun completedReceiptSurvivesReReadAndCleanupWithoutBlockingSuggestions() {
        val image = PendingReportStore.newScreenshotFile(context, "id").apply { writeText("bytes") }
        PendingReportStore.write(context, report().copy(screenshotPath = image.path, createdAt = 0))
        val result = SubmitTesterReportResult(success = true, reportId = "server-id", provisionalGranted = true, provisionalAmount = 10)
        PendingReportStore.markCompleted(context, "id", result)
        assertNotNull("receipt JSON: " + File(PendingReportStore.directory(context), "id.json").readText(),
            PendingReportStore.read(context, "id"))
        PendingReportStore.cleanup(context)
        assertEquals(result, PendingReportStore.read(context, "id")!!.completedResult)
        assertFalse(image.exists())
        assertTrue(PendingReportStore.pendingCampaignIds(context).isEmpty())
    }
    @Test fun interruptedAtomicUpdatePreservesPreviousRequest() {
        PendingReportStore.write(context, report())
        File(PendingReportStore.directory(context), "id.json.tmp").writeText("incomplete JSON")
        assertEquals(report().comment, PendingReportStore.read(context, "id")!!.comment)
    }
    @Test fun legacyBackupIsRecoveredBeforeReading() {
        PendingReportStore.write(context, report())
        val base = File(PendingReportStore.directory(context), "id.json")
        base.copyTo(File(base.path + ".bak"))
        base.writeText("interrupted old write")
        assertEquals("feedback", PendingReportStore.read(context, "id")!!.comment)
    }
    @Test(expected = IOException::class) fun missingRecordCannotPretendToPersistUploadedUrl() {
        PendingReportStore.markUploaded(context, "missing", "url", "hash")
    }
    @Test fun fingerprintDetectsChangedBytes() {
        val image = PendingReportStore.newScreenshotFile(context, "id").apply { writeText("original") }
        val original = PendingReportStore.screenshotSha256(image)
        image.writeText("changed")
        assertNotEquals(original, PendingReportStore.screenshotSha256(image))
    }
}
