package com.ntt.crosstest.audit

import android.app.Application
import coil.request.ImageRequest
import com.ntt.crosstest.data.ReportAttachment
import com.ntt.crosstest.data.TesterReport
import com.ntt.crosstest.util.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(application=Application::class,sdk=[34])
class MissionWindowMediaIndependentTest {
    @Before fun before(){
        // Coil default options can retain Main.immediate across Robolectric sandboxes.
        // Bind the forwarding test Main dispatcher before creating any ImageRequest.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }
    @After fun after(){
        ScreenshotCachePolicy.isMediaGatewayEnabled=false
        ScreenshotCachePolicy.mediaGatewayBaseUrl=""
        ScreenshotCachePolicy.mediaAccessGrantProvider=null
        ScreenshotManager.clear()
        Dispatchers.resetMain()
    }
    @Test fun secondThumbnailFallbackKeepsSecondImageAndTrustedExpiry(){
        val second=ReportAttachment("attachment-two",1,"https://fixture.invalid/reports/second.jpg","https://fixture.invalid/reports/thumb_second.jpg")
        val report=TesterReport(id="report",campaignId="campaign",userId="tester",
            screenshotUrl="https://fixture.invalid/reports/first.jpg",thumbnailUrl="https://fixture.invalid/reports/thumb_first.jpg",
            createdAt=Instant.now().toString(),mediaExpired=true,attachments=listOf(second))
        val request=ScreenshotRequestFactory.createRequest(RuntimeEnvironment.getApplication(),second.thumbnailUrl,
            report=report,variant=ScreenshotVariant.THUMBNAIL,attachmentId=second.id) as ImageRequest
        val descriptor=request.tags.tag(ScreenshotDescriptor::class.java)!!
        assertEquals(second.thumbnailUrl,request.data)
        assertEquals(second.screenshotUrl,descriptor.fallbackUrl)
        assertEquals(second.id,descriptor.attachmentId)
        assertEquals(report.createdAt,descriptor.createdAt)
        assertTrue("expired secondary media must fail before network/cache display",ScreenshotManager.isExpired(descriptor))
    }
    @Test fun gatewayRequestsTheSelectedAttachmentInsteadOfReportPrimary()=runBlocking{
        var requested:String?="not-called"
        ScreenshotCachePolicy.isMediaGatewayEnabled=true
        ScreenshotCachePolicy.mediaGatewayBaseUrl="https://gateway.invalid/media"
        ScreenshotCachePolicy.mediaAccessGrantProvider=object:MediaAccessGrantProvider{
            override suspend fun getMediaAccessGrant(reportId:String)=MediaAccessGrantResult(true,screenshotUrl="https://signed.invalid/primary")
            override suspend fun getMediaAccessGrant(reportId:String,attachmentId:String?):MediaAccessGrantResult{
                requested=attachmentId
                return MediaAccessGrantResult(true,screenshotUrl="https://signed.invalid/$attachmentId")
            }
            override fun getCurrentAuthToken():String?=null
        }
        val descriptor=ScreenshotDescriptor("https://fixture.invalid/reports/second.jpg",reportId="report",attachmentId="attachment-two")
        val actual=ScreenshotManager.resolveEffectiveUrlAsync(descriptor.url,descriptor)
        assertEquals("attachment-two",requested)
        assertEquals("https://signed.invalid/attachment-two",actual)
        ScreenshotCachePolicy.mediaAccessGrantProvider=null
        val fallback=ScreenshotManager.resolveEffectiveUrl(descriptor.url,descriptor)
        assertTrue("gateway fallback must retain exact attachment identity",fallback.contains("attachment_id=attachment-two"))
    }
    @Test fun syntheticLegacyAttachmentDoesNotBecomeARealAttachmentGrant(){
        val report=TesterReport(id="legacy-report",campaignId="campaign",userId="tester",screenshotUrl="https://fixture.invalid/reports/legacy.jpg")
        val legacy=report.allAttachments.single()
        val descriptor=ScreenshotRequestFactory.createDescriptor(legacy.screenshotUrl,report,attachmentId=legacy.id)!!
        assertEquals(report.id,descriptor.reportId)
        assertNull("legacy report ID is not an attachment-row ID",descriptor.attachmentId)
    }
    @Test fun existingFullscreenViewerContractResolvesSelectedSecondaryUrl(){
        val second=ReportAttachment("attachment-two",1,"https://fixture.invalid/reports/second.jpg","https://fixture.invalid/reports/thumb_second.jpg")
        val report=TesterReport(id="report",campaignId="campaign",userId="tester",screenshotUrl="https://fixture.invalid/reports/first.jpg",attachments=listOf(second))
        // FullscreenImageViewer's existing public inputs are selected URL + complete report.
        val descriptor=ScreenshotRequestFactory.createDescriptor(second.screenshotUrl,report)!!
        assertEquals("fullscreen selected URL must resolve its own grant identity",second.id,descriptor.attachmentId)
        assertEquals(second.screenshotUrl,descriptor.url)
    }
}
