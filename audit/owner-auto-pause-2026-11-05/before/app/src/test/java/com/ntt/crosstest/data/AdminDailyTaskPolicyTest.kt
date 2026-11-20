package com.ntt.crosstest.data

import com.ntt.crosstest.ui.viewmodels.friendlyError
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AdminDailyTaskPolicyTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testAdminDailyTaskConfigDeserialization_withBaselineAndFuture() {
        val payload = """
            {
                "today": "2026-10-29",
                "timezone": "Asia/Ho_Chi_Minh",
                "revision": "hash123rev",
                "active_policy": {
                    "id": 1,
                    "tests_per_active_app": 3,
                    "effective_from": null,
                    "is_baseline": true,
                    "operator_note": "Baseline policy",
                    "updated_at": "2026-10-29T00:00:00.000000Z"
                },
                "future_policies": [
                    {
                        "id": 2,
                        "tests_per_active_app": 6,
                        "effective_from": "2026-10-30",
                        "is_baseline": false,
                        "operator_note": "Tăng lên 6:1 từ ngày mai",
                        "updated_at": "2026-10-29T10:00:00.123456Z"
                    }
                ]
            }
        """.trimIndent()

        val config = json.decodeFromString<AdminDailyTaskConfig>(payload)
        assertEquals("2026-10-29", config.today)
        assertEquals("Asia/Ho_Chi_Minh", config.timezone)
        assertEquals("hash123rev", config.revision)

        val active = config.activePolicy
        assertNotNull(active)
        assertEquals(1L, active!!.id)
        assertEquals(3, active.testsPerActiveApp)
        assertNull(active.effectiveFrom)
        assertTrue(active.isBaseline)
        assertEquals("Baseline policy", active.operatorNote)

        assertEquals(1, config.futurePolicies.size)
        val future = config.futurePolicies[0]
        assertEquals(2L, future.id)
        assertEquals(6, future.testsPerActiveApp)
        assertEquals("2026-10-30", future.effectiveFrom)
        assertFalse(future.isBaseline)
    }

    @Test
    fun testAdminScheduleParamsSerialization() {
        val params = AdminScheduleDailyTaskPolicyParams(
            testsPerActiveApp = 6,
            effectiveFrom = "2026-10-30",
            operatorNote = "Đổi tỷ lệ",
            expectedRevision = "rev-xyz"
        )
        val encoded = json.encodeToString(params)
        assertTrue(encoded.contains("\"p_tests_per_active_app\":6"))
        assertTrue(encoded.contains("\"p_effective_from\":\"2026-10-30\""))
        assertTrue(encoded.contains("\"p_operator_note\":\"Đổi tỷ lệ\""))
        assertTrue(encoded.contains("\"p_expected_revision\":\"rev-xyz\""))
    }

    @Test
    fun testAdminCancelParamsSerialization() {
        val params = AdminCancelDailyTaskPolicyParams(
            effectiveFrom = "2026-10-30",
            operatorNote = "Hủy lịch",
            expectedRevision = "rev-xyz"
        )
        val encoded = json.encodeToString(params)
        assertTrue(encoded.contains("\"p_effective_from\":\"2026-10-30\""))
        assertTrue(encoded.contains("\"p_operator_note\":\"Hủy lịch\""))
        assertTrue(encoded.contains("\"p_expected_revision\":\"rev-xyz\""))
    }

    @Test
    fun testAdminDailyTaskHistoryDeserialization() {
        val payload = """
            {
                "items": [
                    {
                        "id": 10,
                        "policy_id": 2,
                        "operation": "DELETE",
                        "old_row": {
                            "id": 2,
                            "effective_from": "2026-10-30",
                            "tests_per_active_app": 6
                        },
                        "new_row": null,
                        "operator_note": "Hủy lịch ngày mai",
                        "session_role": "authenticated",
                        "invoked_role": "authenticated",
                        "actor_id": "11111111-1111-1111-1111-111111111111",
                        "actor_name": "Admin Test",
                        "actor_email": "admin@test.com",
                        "changed_at": "2026-10-29T11:00:00.000000Z"
                    }
                ],
                "has_more": false,
                "next_cursor_id": null
            }
        """.trimIndent()

        val history = json.decodeFromString<AdminDailyTaskPolicyHistoryResult>(payload)
        assertEquals(1, history.items.size)
        val item = history.items[0]
        assertEquals(10L, item.id)
        assertEquals("DELETE", item.operation)
        assertEquals("11111111-1111-1111-1111-111111111111", item.actorId)
        assertEquals("Admin Test", item.actorName)
        assertEquals("admin@test.com", item.actorEmail)
        assertEquals("Hủy lịch ngày mai", item.operatorNote)
        assertNotNull(item.oldRow)
        assertNull(item.newRow)
    }

    @Test
    fun testTomorrowCalculationFromTodayServer() {
        val todayStr = "2026-10-29"
        val parsed = LocalDate.parse(todayStr)
        val tomorrow = parsed.plusDays(1).toString()
        assertEquals("2026-10-30", tomorrow)
        assertTrue(tomorrow > todayStr)
    }

    @Test
    fun testRatioRangeValidation() {
        fun isValidRatio(r: Int) = r in 1..30
        assertTrue(isValidRatio(1))
        assertTrue(isValidRatio(6))
        assertTrue(isValidRatio(30))
        assertFalse(isValidRatio(0))
        assertFalse(isValidRatio(31))
        assertFalse(isValidRatio(-5))
    }

    @Test
    fun testNoteLengthValidation() {
        fun isValidNote(n: String) = n.trim().length in 1..1000
        assertTrue(isValidNote("Cập nhật theo số lượng app"))
        assertFalse(isValidNote(""))
        assertFalse(isValidNote("   \n\t  "))
        val longNote = "a".repeat(1001)
        assertFalse(isValidNote(longNote))
        val maxNote = "a".repeat(1000)
        assertTrue(isValidNote(maxNote))
    }
}
