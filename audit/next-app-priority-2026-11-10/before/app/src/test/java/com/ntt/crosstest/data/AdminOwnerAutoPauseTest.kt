package com.ntt.crosstest.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AdminOwnerAutoPauseTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testOwnerAutoPauseConfigDeserialization() {
        val payload = """
            {
                "missed_days": 3,
                "revision": "rev-test-123",
                "updated_at": "2026-11-03T12:00:00Z"
            }
        """.trimIndent()

        val config = json.decodeFromString<OwnerAutoPauseConfig>(payload)
        assertEquals(3, config.missedDays)
        assertEquals("rev-test-123", config.revision)
        assertEquals("2026-11-03T12:00:00Z", config.updatedAt)
    }

    @Test
    fun incompleteServerConfigDoesNotAppearAsSevenDays() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<OwnerAutoPauseConfig>("{}")
        }
    }

    @Test
    fun testSetOwnerAutoPauseConfigParamsSerialization() {
        val params = SetOwnerAutoPauseConfigParams(
            missedDays = 5,
            expectedRevision = "rev-exp-456"
        )
        val encoded = json.encodeToString(params)
        assertTrue(encoded.contains("\"p_missed_days\":5"))
        assertTrue(encoded.contains("\"p_expected_revision\":\"rev-exp-456\""))
    }

    @Test
    fun testSetOwnerAutoPauseConfigResultDeserialization() {
        val payload = """
            {
                "success": true,
                "missed_days": 3,
                "revision": "rev-new-789",
                "previous_missed_days": 7
            }
        """.trimIndent()

        val result = json.decodeFromString<SetOwnerAutoPauseConfigResult>(payload)
        assertTrue(result.success)
        assertEquals(3, result.missedDays)
        assertEquals("rev-new-789", result.revision)
        assertEquals(7, result.previousMissedDays)
    }

}
