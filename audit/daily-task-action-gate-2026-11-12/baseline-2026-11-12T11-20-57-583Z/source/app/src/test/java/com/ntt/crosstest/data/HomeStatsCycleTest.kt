package com.ntt.crosstest.data

import com.ntt.crosstest.ui.viewmodels.CampaignDisplayItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class HomeStatsCycleTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testCampaignStatsDeserialization_withNewFields() {
        val payload = """
            {
                "installed_count": 10,
                "today_tested_count": 4,
                "has_tested_today": true,
                "home_tested_count": 9,
                "stats_date": "2026-10-28"
            }
        """.trimIndent()

        val stats = json.decodeFromString<CampaignStats>(payload)
        assertEquals(10, stats.installedCount)
        assertEquals(4, stats.todayTestedCount)
        assertTrue(stats.hasTestedToday)
        assertEquals(9, stats.homeTestedCount)
        assertEquals("2026-10-28", stats.statsDate)
        assertEquals(9, stats.effectiveHomeTestedCount)
    }

    @Test
    fun testCampaignStatsDeserialization_legacyBackendPayload() {
        // Backend cũ chỉ trả 3 trường cơ bản
        val legacyPayload = """
            {
                "installed_count": 8,
                "today_tested_count": 3,
                "has_tested_today": false
            }
        """.trimIndent()

        val stats = json.decodeFromString<CampaignStats>(legacyPayload)
        assertEquals(8, stats.installedCount)
        assertEquals(3, stats.todayTestedCount)
        assertFalse(stats.hasTestedToday)
        assertNull(stats.homeTestedCount)
        assertNull(stats.statsDate)
        // Khi không có homeTestedCount, effectiveHomeTestedCount rơi về todayTestedCount
        assertEquals(3, stats.effectiveHomeTestedCount)
    }

    @Test
    fun testHomeStatsPolicyConfigDeserialization() {
        val payload = """
            {
                "active_cycle_days": 2,
                "active_effective_from": "2026-10-28",
                "pending_cycle_days": 3,
                "pending_effective_from": "2026-10-29",
                "revision": "abc123rev",
                "today": "2026-10-28"
            }
        """.trimIndent()

        val config = json.decodeFromString<HomeStatsPolicyConfig>(payload)
        assertEquals(2, config.activeCycleDays)
        assertEquals("2026-10-28", config.activeEffectiveFrom)
        assertEquals(3, config.pendingCycleDays)
        assertEquals("2026-10-29", config.pendingEffectiveFrom)
        assertEquals("abc123rev", config.revision)
        assertEquals("2026-10-28", config.today)
    }

    @Test
    fun testAdminSetHomeStatsCycleParamsSerialization() {
        val params = AdminSetHomeStatsCycleParams(cycleDays = 3, expectedRevision = "rev99")
        val encoded = json.encodeToString(params)
        assertTrue(encoded.contains("\"p_cycle_days\":3"))
        assertTrue(encoded.contains("\"p_expected_revision\":\"rev99\""))
    }

    @Test
    fun testCampaignDisplayItem_targetReachedUsesTodayTestedCount_notHomeTestedCount() {
        // Kịch bản: Mục tiêu 12 test/ngày.
        // Chu kỳ 3 ngày cộng dồn được 14 lượt (vượt mục tiêu).
        // Nhưng ngày hôm nay mới có 2 lượt.
        val campaign = Campaign(
            id = "c1",
            appName = "Demo App",
            packageName = "com.demo.app",
            targetTesters = 12,
            status = "open"
        )

        val item = CampaignDisplayItem(
            campaign = campaign,
            ownerName = "Owner",
            installedCount = 10,
            todayTestedCount = 2,
            hasTestedToday = false,
            homeTestedCount = 14,
            statsDate = "2026-10-28"
        )

        // 1. Số hiển thị trên thẻ app Trang chủ là 14 (chu kỳ cộng dồn)
        assertEquals(14, item.displayTestedCount)
        // 2. Chỉ tiêu trong ngày thật sự là 2/12 -> Chưa đạt chỉ tiêu!
        assertFalse(item.isTargetReached)

        // Nếu hôm nay test đủ 12 lượt:
        val itemReached = item.copy(todayTestedCount = 12, homeTestedCount = 24)
        assertEquals(24, itemReached.displayTestedCount)
        assertTrue(itemReached.isTargetReached)
    }

    @Test
    fun testSanitizeForDate_clearsOldTodayStats_andInvalidatesHomeStatsWithoutFakeZero() {
        val campaign = Campaign(id = "c1", appName = "App", packageName = "com.app", targetTesters = 12)
        val item = CampaignDisplayItem(
            campaign = campaign,
            todayTestedCount = 5,
            hasTestedToday = true,
            homeTestedCount = 9,
            statsDate = "2026-10-20"
        )

        val cachedSession = CachedSessionData(
            userId = "u1",
            dateVn = "2026-10-20", // Ngày cũ trong quá khứ
            timestamp = 1000L,
            displayItems = listOf(item),
            todayCheckinCount = 3
        )

        val sanitized = cachedSession.sanitizeForDate()

        // todayCheckinCount phải về 0
        assertEquals(0, sanitized.todayCheckinCount)

        val sanitizedItem = sanitized.displayItems.first()
        // hasTestedToday và todayTestedCount phải về false và 0
        assertFalse(sanitizedItem.hasTestedToday)
        assertEquals(0, sanitizedItem.todayTestedCount)

        // homeTestedCount và statsDate phải về null (không giữ số ngày cũ và không gán số 0 giả)
        assertNull(sanitizedItem.homeTestedCount)
        assertNull(sanitizedItem.statsDate)
        // displayTestedCount không được rơi về số 0 giả khi dữ liệu hết hạn
        assertNull(sanitizedItem.displayTestedCount)
    }
}
