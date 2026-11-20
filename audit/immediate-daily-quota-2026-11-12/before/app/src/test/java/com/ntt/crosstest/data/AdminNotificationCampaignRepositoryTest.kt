package com.ntt.crosstest.data

import android.app.Application
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AdminNotificationCampaignRepositoryTest {

    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun createClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): SupabaseClient =
        createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Postgrest)
            httpEngine = MockEngine(handler)
        }

    private val sampleCampaignJson = """
        {
            "id": "550e8400-e29b-41d4-a716-446655440000",
            "title_vi": "Tiêu đề VI",
            "title_en": "Title EN",
            "body_vi": "Nội dung VI",
            "body_en": "Body EN",
            "default_locale": "vi",
            "audience_type": "all",
            "target_role": null,
            "selected_user_ids": [],
            "status": "draft",
            "revision": 1,
            "total_recipients": 100,
            "recipient_snapshot_taken": true,
            "snapshot_taken_at": "2026-11-01T12:00:00.000000Z",
            "sent_count": 0,
            "read_count": 0,
            "failed_count": 0,
            "scheduled_for": null,
            "started_at": null,
            "completed_at": null,
            "cancelled_at": null,
            "cancelled_by": null,
            "cancel_reason": null,
            "created_by": "11111111-1111-1111-1111-111111111111",
            "created_at": "2026-11-01T10:00:00.000000Z",
            "updated_at": "2026-11-01T10:00:00.000000Z"
        }
    """.trimIndent()

    @Test
    fun testCreateCampaignDecodesExactResponse() = runTest {
        var requestedPath = ""
        val client = createClient { req ->
            requestedPath = req.url.encodedPath
            reply("""{"success": true, "replayed": false, "campaign": $sampleCampaignJson}""")
        }
        val repo = AdminNotificationCampaignRepository(client)

        val resp = repo.createCampaign(
            requestId = "req-1",
            titleVi = "Tiêu đề VI",
            titleEn = "Title EN",
            bodyVi = "Nội dung VI",
            bodyEn = "Body EN",
        )

        assertTrue(requestedPath.contains("admin_create_notification_campaign"))
        assertTrue(resp.success)
        assertFalse(resp.replayed)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", resp.campaign.id)
        assertEquals("Tiêu đề VI", resp.campaign.titleVi)
        assertEquals(1, resp.campaign.revision)
        assertTrue(resp.campaign.recipientSnapshotTaken)
    }

    @Test
    fun testUpdateCampaignDecodesExactResponse() = runTest {
        var requestedPath = ""
        val client = createClient { req ->
            requestedPath = req.url.encodedPath
            reply("""{"success": true, "replayed": false, "campaign": $sampleCampaignJson}""")
        }
        val repo = AdminNotificationCampaignRepository(client)

        val resp = repo.updateCampaign(
            requestId = "req-2",
            campaignId = "550e8400-e29b-41d4-a716-446655440000",
            expectedRevision = 1,
            titleVi = "Tiêu đề VI sửa",
            titleEn = "Title EN modified",
            bodyVi = "Nội dung",
            bodyEn = "Body",
        )

        assertTrue(requestedPath.contains("admin_update_notification_campaign"))
        assertTrue(resp.success)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", resp.campaign.id)
    }

    @Test
    fun testPreviewCampaignDecodesExactResponse() = runTest {
        var requestedPath = ""
        val client = createClient { req ->
            requestedPath = req.url.encodedPath
            reply("""
                {
                    "success": true,
                    "recipient_count": 250,
                    "audience_type": "role",
                    "target_role": "member",
                    "default_locale": "vi",
                    "preview_vi": { "title": "Tin tức", "body": "Nội dung" },
                    "preview_en": { "title": "News", "body": "Content" },
                    "preview_effective": { "locale": "vi", "title": "Tin tức", "body": "Nội dung" }
                }
            """.trimIndent())
        }
        val repo = AdminNotificationCampaignRepository(client)

        val preview = repo.previewCampaign(
            audienceType = "role",
            targetRole = "member",
            titleVi = "Tin tức",
            titleEn = "News",
        )

        assertTrue(requestedPath.contains("admin_preview_notification_campaign"))
        assertTrue(preview.success)
        assertEquals(250, preview.recipientCount)
        assertEquals("Tin tức", preview.previewEffective?.title)
    }

    @Test
    fun testScheduleAndCancelDecodesExactResponse() = runTest {
        val client = createClient { req ->
            val path = req.url.encodedPath
            if (path.contains("admin_schedule_notification_campaign")) {
                reply("""{"success": true, "replayed": false, "campaign": $sampleCampaignJson}""")
            } else if (path.contains("admin_cancel_notification_campaign")) {
                reply("""{"success": true, "replayed": false, "campaign": $sampleCampaignJson}""")
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }
        val repo = AdminNotificationCampaignRepository(client)

        val schedResp = repo.scheduleCampaign(
            requestId = "req-sched",
            campaignId = "550e8400-e29b-41d4-a716-446655440000",
            expectedRevision = 1,
            scheduledForIso = "2026-11-02T15:00:00.000000Z",
        )
        assertTrue(schedResp.success)

        val cancelResp = repo.cancelCampaign(
            requestId = "req-cancel",
            campaignId = "550e8400-e29b-41d4-a716-446655440000",
            expectedRevision = 2,
            reason = "Admin cancelled",
        )
        assertTrue(cancelResp.success)
    }

    @Test
    fun testListCampaignsAndRecipientsPagination() = runTest {
        val client = createClient { req ->
            val path = req.url.encodedPath
            if (path.contains("admin_list_notification_campaigns")) {
                reply("""
                    {
                        "items": [$sampleCampaignJson],
                        "has_more": true,
                        "next_cursor": "2026-11-01T10:00:00.000000Z|550e8400-e29b-41d4-a716-446655440000"
                    }
                """.trimIndent())
            } else if (path.contains("admin_list_notification_campaign_recipients")) {
                reply("""
                    {
                        "items": [
                            {
                                "recipient_id": "r1",
                                "user_id": "u1",
                                "user_email": "tester@test.local",
                                "user_display_name": "Tester 1",
                                "user_role": "member",
                                "status": "sent",
                                "sent_at": "2026-11-01T10:05:00.000000Z",
                                "is_read": true
                            }
                        ],
                        "has_more": false,
                        "next_cursor": null
                    }
                """.trimIndent())
            } else {
                reply("{}", HttpStatusCode.NotFound)
            }
        }
        val repo = AdminNotificationCampaignRepository(client)

        val campEnv = repo.listCampaigns(status = "draft")
        assertEquals(1, campEnv.items.size)
        assertTrue(campEnv.hasMore)
        assertNotNull(campEnv.nextCursor)

        val recipEnv = repo.listCampaignRecipients(campaignId = "550e8400-e29b-41d4-a716-446655440000")
        assertEquals(1, recipEnv.items.size)
        assertEquals("Tester 1", recipEnv.items[0].userDisplayName)
        assertTrue(recipEnv.items[0].isRead)
    }

    @Test
    fun testGetSchedulerHealthDecodesExactDto() = runTest {
        val client = createClient {
            reply("""
                {
                    "pg_cron_available": true,
                    "pg_cron_job_active": true,
                    "scheduler_ready": true,
                    "pending_due_campaigns_count": 0,
                    "in_flight_campaigns_count": 0,
                    "last_worker_run_at": "2026-11-01T12:00:00.000000Z",
                    "lifetime_campaigns_processed": 42,
                    "lifetime_notifications_sent": 5800,
                    "health_status": "healthy"
                }
            """.trimIndent())
        }
        val repo = AdminNotificationCampaignRepository(client)

        val health = repo.getSchedulerHealth()
        assertTrue(health.pgCronAvailable)
        assertTrue(health.pgCronJobActive)
        assertTrue(health.schedulerReady)
        assertEquals("healthy", health.healthStatus)
        assertEquals(42, health.lifetimeCampaignsProcessed)
    }
}
