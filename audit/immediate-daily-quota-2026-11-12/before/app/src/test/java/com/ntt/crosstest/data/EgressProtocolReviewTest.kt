package com.ntt.crosstest.data

import java.time.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Independent protocol contract checks, including malformed responses. */
class EgressProtocolReviewTest {
    private val json = Json { encodeDefaults = true }
    private val day = LocalDate.of(2026, 11, 10)
    private val tokenA = "a".repeat(64)
    private val tokenB = "b".repeat(64)

    private fun envelope(values: JsonObject): JsonObject = JsonObject(mapOf(
        "schema_version" to JsonPrimitive(2),
        "business_date" to JsonPrimitive(day.toString()),
        "blocks" to JsonObject(values.mapValues { (_, value) -> JsonObject(mapOf(
            "token" to JsonPrimitive(tokenA),
            "changed" to JsonPrimitive(true),
            "value" to value,
        )) }),
    ))

    private fun changeBlock(root: JsonObject, key: String, transform: (JsonObject) -> JsonObject): String {
        val blocks = root["blocks"]!!.jsonObject
        return JsonObject(root + ("blocks" to JsonObject(blocks + (key to transform(blocks[key]!!.jsonObject))))).toString()
    }

    @Test
    fun decodedJsonLengthMustNotBeReportedAsWireBytes() {
        val root = envelope(json.encodeToJsonElement(HomeFeedResult()).jsonObject)
        val result = EgressSyncProtocol.validateAndMergeHomeFeed(root.toString(), null, day)
        require(result is MergeResult.Success)
        assertNull("Wire bytes are unknown at the JSON parser; compression/HTTP framing are unavailable", result.wireBytesEstimate)
    }

    @Test
    fun unchangedBlockMustMatchTheTokenOfTheExactBaseValue() {
        val payload = HomeFeedResult()
        val raw = json.encodeToJsonElement(payload).jsonObject
        val base = ScopedSyncSnapshot(payload, raw.keys.associateWith { tokenA }, null, 1L, 1L, day, 0L)
        val body = changeBlock(envelope(raw), "open") { block ->
            JsonObject((block - "value") + mapOf("token" to JsonPrimitive(tokenB), "changed" to JsonPrimitive(false)))
        }
        val result = EgressSyncProtocol.validateAndMergeHomeFeed(body, base, day)
        assertFalse("An unchanged response cannot assign a different token to cached content", result is MergeResult.Success)
    }

    @Test
    fun stringTrueIsNotAJsonBoolean() {
        val root = envelope(json.encodeToJsonElement(HomeFeedResult()).jsonObject)
        val body = changeBlock(root, "open") { JsonObject(it + ("changed" to JsonPrimitive("true"))) }
        val result = EgressSyncProtocol.validateAndMergeHomeFeed(body, null, day)
        assertFalse("Protocol booleans must not accept quoted strings", result is MergeResult.Success)
    }

    @Test
    fun nonNullableDashboardCountCannotSilentlyBecomeZeroFromJsonNull() {
        val root = envelope(json.encodeToJsonElement(MyDashboardResult()).jsonObject)
        val body = changeBlock(root, "pending_reports_count") { JsonObject(it + ("value" to JsonNull)) }
        val result = EgressSyncProtocol.validateAndMergeDashboard(body, null, day)
        assertFalse("Null count must require repair, not fabricate zero", result is MergeResult.Success)
    }

    @Test
    fun nullableDetailValueStillRequiresTheBlockToBePresent() {
        val root = envelope(json.encodeToJsonElement(CampaignDetailResult(campaign = Campaign(id = "fixture"))).jsonObject)
        val body = JsonObject(root + ("blocks" to JsonObject(root["blocks"]!!.jsonObject - "app_update"))).toString()
        val result = EgressSyncProtocol.validateAndMergeCampaignDetail(body, null, day)
        assertFalse("A nullable value is different from an absent required v2 block", result is MergeResult.Success)
    }

    @Test
    fun protocolHeaderDiagnosticsMustNotEchoMalformedHeaderValues() {
        val root = envelope(json.encodeToJsonElement(HomeFeedResult()).jsonObject)
        for (key in listOf("schema_version", "business_date")) {
            val body = JsonObject(root + (key to JsonPrimitive("sensitive-fixture-marker"))).toString()
            val results = listOf(
                EgressSyncProtocol.validateAndMergeHomeFeed(body, null, day),
                EgressSyncProtocol.validateAndMergeDashboard(body, null, day),
                EgressSyncProtocol.validateAndMergeCampaignDetail(body, null, day),
            )
            results.forEach {
                assertFalse(it is MergeResult.Success)
                assertFalse("Header diagnostics must not expose response data", it.toString().contains("sensitive-fixture-marker"))
            }
        }
    }

    @Test
    fun protocolDiagnosticsMustNotEchoSensitivePayloadValues() {
        val root = envelope(json.encodeToJsonElement(MyDashboardResult()).jsonObject)
        val invalid = JsonObject(mapOf("private_value" to JsonPrimitive("sensitive-fixture-marker")))
        val body = changeBlock(root, "pending_reports_count") { JsonObject(it + ("value" to invalid)) }
        val result = EgressSyncProtocol.validateAndMergeDashboard(body, null, day)
        assertFalse(result is MergeResult.Success)
        assertFalse("Decoder diagnostics must not expose raw response data", result.toString().contains("sensitive-fixture-marker"))
    }
}
