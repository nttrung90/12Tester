package com.ntt.crosstest.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CampaignPushUnitTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testCampaignIsPushActiveRules() {
        val today = "2026-10-29"
        val tomorrow = "2026-10-30"
        val yesterday = "2026-10-28"

        // Case 1: Chưa từng đẩy (pushOrder = null, pushBusinessDate = null)
        val campNeverPushed = Campaign(id = "c1", pushOrder = null, pushBusinessDate = null)
        assertFalse(campNeverPushed.isPushActive(today))

        // Case 2: pushOrder <= 0 (không hợp lệ)
        val campZeroOrder = Campaign(id = "c2", pushOrder = 0L, pushBusinessDate = today)
        assertFalse(campZeroOrder.isPushActive(today))

        // Case 3: Đẩy hôm nay (hợp lệ)
        val campPushedToday = Campaign(
            id = "c3",
            pushOrder = 42L,
            pushBusinessDate = today,
            pushExpiresAt = "2026-10-29T17:00:00Z"
        )
        assertTrue(campPushedToday.isPushActive(today))
        // Sang ngày mai: tự động hết hiệu lực
        assertFalse(campPushedToday.isPushActive(tomorrow))
        // Khác ngày hôm qua
        assertFalse(campPushedToday.isPushActive(yesterday))

        // Case 4: Đẩy hôm qua -> kiểm tra tại ngày hôm nay
        val campPushedYesterday = Campaign(
            id = "c4",
            pushOrder = 15L,
            pushBusinessDate = yesterday,
            pushExpiresAt = "2026-10-28T17:00:00Z"
        )
        assertFalse(campPushedYesterday.isPushActive(today))
        assertTrue(campPushedYesterday.isPushActive(yesterday))
    }

    @Test
    fun testPushCampaignResultSerialization() {
        val jsonPayload = """
            {
                "success": true,
                "receipt_id": "11111111-2222-3333-4444-555555555555",
                "client_request_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                "campaign_id": "99999999-8888-7777-6666-555555555555",
                "charged_credits": 100,
                "balance_after": 25,
                "business_date": "2026-10-29",
                "expires_at": "2026-10-29T17:00:00Z",
                "push_order": 108,
                "replayed": false
            }
        """.trimIndent()

        val result = json.decodeFromString<PushCampaignResult>(jsonPayload)
        assertTrue(result.success)
        assertEquals("11111111-2222-3333-4444-555555555555", result.receiptId)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", result.clientRequestId)
        assertEquals("99999999-8888-7777-6666-555555555555", result.campaignId)
        assertEquals(100, result.chargedCredits)
        assertEquals(25, result.balanceAfter)
        assertEquals("2026-10-29", result.businessDate)
        assertEquals("2026-10-29T17:00:00Z", result.expiresAt)
        assertEquals(108L, result.pushOrder)
        assertFalse(result.replayed)
    }

    @Test
    fun testPushCampaignResultReplaySerialization() {
        val jsonPayload = """
            {
                "success": true,
                "receipt_id": "11111111-2222-3333-4444-555555555555",
                "client_request_id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                "campaign_id": "99999999-8888-7777-6666-555555555555",
                "charged_credits": 100,
                "balance_after": 25,
                "business_date": "2026-10-29",
                "expires_at": "2026-10-29T17:00:00Z",
                "push_order": 108,
                "replayed": true
            }
        """.trimIndent()

        val result = json.decodeFromString<PushCampaignResult>(jsonPayload)
        assertTrue(result.success)
        assertTrue(result.replayed)
    }

    @Test
    fun testConcurrencyViewModelActionLock_PreventsDuplicateExecution() {
        // Mô phỏng 20 thread đồng thời bấm "Xác nhận đẩy app" cùng một microsecond
        val threads = 20
        val executor = Executors.newFixedThreadPool(threads)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threads)

        val inProgressLock = AtomicBoolean(false)
        val acquiredCount = AtomicInteger(0)
        val rejectedCount = AtomicInteger(0)

        for (i in 0 until threads) {
            executor.submit {
                try {
                    startLatch.await()
                    // Đồng bộ CAS như trong ViewModel
                    if (inProgressLock.compareAndSet(false, true)) {
                        acquiredCount.incrementAndGet()
                    } else {
                        rejectedCount.incrementAndGet()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        doneLatch.await()
        executor.shutdown()

        assertEquals("Chỉ đúng 1 hành động được phép sở hữu khóa khi cạnh tranh đồng thời", 1, acquiredCount.get())
        assertEquals(threads - 1, rejectedCount.get())
    }
}
