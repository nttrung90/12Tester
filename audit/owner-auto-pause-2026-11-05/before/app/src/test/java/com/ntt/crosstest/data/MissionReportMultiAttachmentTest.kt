package com.ntt.crosstest.data

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import com.ntt.crosstest.ui.viewmodels.MissionReportViewModel
import com.ntt.crosstest.util.PreparedScreenshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MissionReportMultiAttachmentTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        PendingReportStore.clear(context)
    }

    @After
    fun tearDown() {
        PendingReportStore.clear(context)
    }

    @Test
    fun testMultiAttachmentStoreSerializationAndCheckpoints() {
        val reqId = "test-multi-req-1"
        val att1File = PendingReportStore.newAttachmentScreenshotFile(context, reqId, "att1").apply { writeText("img1") }
        val att2File = PendingReportStore.newAttachmentScreenshotFile(context, reqId, "att2").apply { writeText("img2") }
        val att3File = PendingReportStore.newAttachmentScreenshotFile(context, reqId, "att3").apply { writeText("img3") }

        val attachments = listOf(
            PendingAttachment(
                id = "att1",
                position = 0,
                screenshotPath = att1File.absolutePath,
                imageHash = "hash1",
                sha256 = "sha1"
            ),
            PendingAttachment(
                id = "att2",
                position = 1,
                screenshotPath = att2File.absolutePath,
                imageHash = "hash2",
                sha256 = "sha2"
            ),
            PendingAttachment(
                id = "att3",
                position = 2,
                screenshotPath = att3File.absolutePath,
                imageHash = "hash3",
                sha256 = "sha3"
            )
        )

        val report = PendingReport(
            id = reqId,
            campaignId = "camp-123",
            appName = "Test App",
            userId = "user-456",
            rating = 5,
            comment = "Multi-attachment test report",
            submissionContext = "mission",
            missionId = "mission-789",
            missionTitle = "3-day quest",
            attachments = attachments
        )

        PendingReportStore.write(context, report)

        val readBack = PendingReportStore.read(context, reqId)
        assertNotNull(readBack)
        assertEquals(3, readBack!!.attachments.size)
        assertEquals("att1", readBack.attachments[0].id)
        assertEquals("att2", readBack.attachments[1].id)
        assertEquals("att3", readBack.attachments[2].id)
        assertNull(readBack.attachments[0].uploadedUrl)

        // Test checkpoint on first attachment
        PendingReportStore.markAttachmentUploaded(
            context = context,
            id = reqId,
            attachmentId = "att1",
            url = "https://storage.googleapis.com/test/att1.jpg",
            hash = "hash1",
            thumbnailUrl = "https://storage.googleapis.com/test/att1_thumb.jpg"
        )

        val checkpoint1 = PendingReportStore.read(context, reqId)!!
        assertEquals("https://storage.googleapis.com/test/att1.jpg", checkpoint1.attachments[0].uploadedUrl)
        assertEquals("https://storage.googleapis.com/test/att1_thumb.jpg", checkpoint1.attachments[0].uploadedThumbnailUrl)
        // Primary compatibility url also updated
        assertEquals("https://storage.googleapis.com/test/att1.jpg", checkpoint1.uploadedUrl)
        assertNull(checkpoint1.attachments[1].uploadedUrl)

        // Test checkpoint on second attachment
        PendingReportStore.markAttachmentUploaded(
            context = context,
            id = reqId,
            attachmentId = "att2",
            url = "https://storage.googleapis.com/test/att2.jpg",
            hash = "hash2"
        )
        val checkpoint2 = PendingReportStore.read(context, reqId)!!
        assertEquals("https://storage.googleapis.com/test/att2.jpg", checkpoint2.attachments[1].uploadedUrl)
        assertNull(checkpoint2.attachments[2].uploadedUrl)

        // Mark completed and verify all attachment local files are deleted
        val result = SubmitTesterReportResult(
            success = true,
            reportId = "server-report-uuid",
            status = "pending",
            submissionContext = "mission",
            missionId = "mission-789",
        )
        PendingReportStore.markCompleted(context, reqId, result)
        assertFalse(att1File.exists())
        assertFalse(att2File.exists())
        assertFalse(att3File.exists())

        // Receipt survives in store
        val completed = PendingReportStore.read(context, reqId)
        assertNotNull(completed)
        assertEquals("server-report-uuid", completed!!.completedResult?.reportId)
    }

    @Test
    fun testPendingCampaignIdsFiltersByContext() {
        val appTestReport = PendingReport(
            id = "req-app-test",
            campaignId = "camp-1",
            appName = "App 1",
            userId = "user-1",
            rating = 5,
            comment = "test",
            submissionContext = "app_test"
        )
        val missionReport = PendingReport(
            id = "req-mission",
            campaignId = "camp-2",
            appName = "App 2",
            userId = "user-1",
            rating = 5,
            comment = "mission",
            submissionContext = "mission",
            missionId = "m-1"
        )

        PendingReportStore.write(context, appTestReport)
        PendingReportStore.write(context, missionReport)

        // Total pending campaigns
        val allPending = PendingReportStore.pendingCampaignIds(context)
        assertEquals(setOf("camp-1", "camp-2"), allPending)

        // Filtered for app_test: mission report does not block app_test suggestions
        val appTestPending = PendingReportStore.pendingCampaignIds(context, "app_test")
        assertEquals(setOf("camp-1"), appTestPending)

        val missionPending = PendingReportStore.pendingCampaignIds(context, "mission")
        assertEquals(setOf("camp-2"), missionPending)
    }

    @Test
    fun testSubmitStateKeyDistinguishesContextAndMission() {
        val keyOrdinary = ReportSubmitManager.submitStateKey("user1", "camp1", "app_test", null, "2026-11-02")
        val keyMission = ReportSubmitManager.submitStateKey("user1", "camp1", "mission", "mission1", "2026-11-02")
        val keyMissionDay2 = ReportSubmitManager.submitStateKey("user1", "camp1", "mission", "mission1", "2026-11-03")

        assertTrue(keyOrdinary != keyMission)
        assertTrue(keyMission != keyMissionDay2)
        assertEquals("user1:camp1:app_test::2026-11-02", keyOrdinary)
        assertEquals("user1:camp1:mission:mission1:2026-11-02", keyMission)
        assertEquals("user1:camp1:mission:mission1:2026-11-03", keyMissionDay2)
    }

    @Test
    fun testTesterReportAllAttachmentsFallbackForSingleImage() {
        val legacyReport = TesterReport(
            id = "rep-legacy",
            campaignId = "camp-1",
            userId = "user-1",
            screenshotUrl = "https://storage.googleapis.com/test/legacy.jpg",
            thumbnailUrl = "https://storage.googleapis.com/test/thumb_legacy.jpg",
            imageHash = "hash123",
            submissionContext = "app_test",
        )
        val atts = legacyReport.allAttachments
        assertEquals(1, atts.size)
        assertEquals("https://storage.googleapis.com/test/legacy.jpg", atts[0].screenshotUrl)
        assertEquals("https://storage.googleapis.com/test/thumb_legacy.jpg", atts[0].thumbnailUrl)

        val multiReport = TesterReport(
            id = "rep-multi",
            campaignId = "camp-1",
            userId = "user-1",
            submissionContext = "mission",
            attachments = listOf(
                ReportAttachment(id = "1", position = 0, screenshotUrl = "url1"),
                ReportAttachment(id = "2", position = 1, screenshotUrl = "url2"),
                ReportAttachment(id = "3", position = 2, screenshotUrl = "url3"),
            )
        )
        assertEquals(3, multiReport.allAttachments.size)
        assertEquals("url1", multiReport.allAttachments[0].screenshotUrl)
        assertEquals("url2", multiReport.allAttachments[1].screenshotUrl)
        assertEquals("url3", multiReport.allAttachments[2].screenshotUrl)
    }
}
