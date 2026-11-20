package com.ntt.crosstest.data

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PendingSlotIntent(
    @SerialName("user_id") val userId: String,
    @SerialName("slot_number") val slotNumber: Int,
    @SerialName("expected_cost") val expectedCost: Int,
    @SerialName("expected_revision") val expectedRevision: String,
    @SerialName("renewal_days") val renewalDays: Int,
    @SerialName("client_request_id") val clientRequestId: String,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class PendingPublishUpdateIntent(
    @SerialName("user_id") val userId: String,
    @SerialName("campaign_id") val campaignId: String,
    @SerialName("version_name") val versionName: String,
    @SerialName("target_version_code") val targetVersionCode: Long,
    @SerialName("release_notes") val releaseNotes: String,
    @SerialName("expected_current_release_id") val expectedCurrentReleaseId: String? = null,
    @SerialName("expected_budget") val expectedBudget: Int,
    @SerialName("expected_revision") val expectedRevision: String,
    @SerialName("client_operation_id") val clientOperationId: String,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class PendingCreateCampaignIntent(
    @SerialName("user_id") val userId: String,
    @SerialName("package_name") val packageName: String,
    @SerialName("slot_number") val slotNumber: Int,
    @SerialName("target_testers") val targetTesters: Int,
    @SerialName("expected_total_cost") val expectedTotalCost: Int,
    @SerialName("expected_min_credits") val expectedMinCredits: Int,
    @SerialName("expected_revision") val expectedRevision: String,
    @SerialName("client_request_id") val clientRequestId: String,
    @SerialName("metadata_hash") val metadataHash: String = "",
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        fun computeMetadataHash(
            appName: String,
            packageName: String,
            playUrl: String,
            testingUrl: String,
            description: String,
            iconUrl: String,
            testUsername: String,
            testPassword: String,
        ): String {
            val cleanPkg = packageName.trim()
            val cleanName = appName.trim().ifBlank { cleanPkg }.take(60)
            val cleanPlay = playUrl.trim().ifBlank { "https://play.google.com/store/apps/details?id=$cleanPkg" }
            val cleanTest = testingUrl.trim().ifBlank { "https://play.google.com/apps/testing/$cleanPkg" }
            val raw = listOf(
                cleanPkg.lowercase(),
                cleanName,
                cleanPlay,
                cleanTest,
                description.trim(),
                iconUrl.trim(),
                testUsername.trim(),
                testPassword.trim(),
            ).joinToString("\u001f")
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * Reusable owner-scoped durable store for financial transaction intents.
 *
 * Guarantees:
 * - Scoped strictly to authenticated userId and target (slotNumber or campaignId).
 * - Synchronous commit() to disk before dispatching network requests.
 * - Retains exact immutable intent terms (cost, token, duration, version, code, release CAS)
 *   and UUID across process recreation and ambiguous network timeouts.
 * - Pending intent of Account A never appears in Account B.
 * - Only cleared upon confirmed server receipt or definitive non-transient rejection.
 */
object FinancialIntentStore {
    private const val PREFS_SLOTS = "crosstest_financial_slot_intents"
    private const val PREFS_APP_UPDATES = "crosstest_financial_app_update_intents"
    private const val PREFS_CREATE_CAMPAIGNS = "crosstest_financial_create_campaign_intents"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    // -------------------------------------------------------------------------
    // Slot Purchase / Renewal Intents
    // -------------------------------------------------------------------------

    @Synchronized
    fun saveSlotIntent(context: Context, intent: PendingSlotIntent) {
        val prefs = context.getSharedPreferences(PREFS_SLOTS, Context.MODE_PRIVATE)
        val key = "${intent.userId}_slot_${intent.slotNumber}"
        val serialized = json.encodeToString(intent)
        val committed = prefs.edit().putString(key, serialized).commit()
        if (!committed) {
            throw IllegalStateException("Failed to commit slot financial intent to disk")
        }
    }

    @Synchronized
    fun peekSlotIntent(context: Context, userId: String, slotNumber: Int): PendingSlotIntent? {
        val prefs = context.getSharedPreferences(PREFS_SLOTS, Context.MODE_PRIVATE)
        val key = "${userId}_slot_$slotNumber"
        val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            json.decodeFromString<PendingSlotIntent>(raw)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clearSlotIntent(context: Context, userId: String, slotNumber: Int) {
        val prefs = context.getSharedPreferences(PREFS_SLOTS, Context.MODE_PRIVATE)
        val key = "${userId}_slot_$slotNumber"
        prefs.edit().remove(key).commit()
    }

    @Synchronized
    fun clearAllSlotIntentsForUser(context: Context, userId: String) {
        val prefs = context.getSharedPreferences(PREFS_SLOTS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val prefix = "${userId}_"
        for (k in prefs.all.keys) {
            if (k.startsWith(prefix)) {
                editor.remove(k)
            }
        }
        editor.commit()
    }

    // -------------------------------------------------------------------------
    // App Update Publish Intents
    // -------------------------------------------------------------------------

    @Synchronized
    fun savePublishIntent(context: Context, intent: PendingPublishUpdateIntent) {
        val prefs = context.getSharedPreferences(PREFS_APP_UPDATES, Context.MODE_PRIVATE)
        val key = "${intent.userId}_campaign_${intent.campaignId}"
        val serialized = json.encodeToString(intent)
        val committed = prefs.edit().putString(key, serialized).commit()
        if (!committed) {
            throw IllegalStateException("Failed to commit publish update financial intent to disk")
        }
    }

    @Synchronized
    fun peekPublishIntent(context: Context, userId: String, campaignId: String): PendingPublishUpdateIntent? {
        val prefs = context.getSharedPreferences(PREFS_APP_UPDATES, Context.MODE_PRIVATE)
        val key = "${userId}_campaign_$campaignId"
        val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            json.decodeFromString<PendingPublishUpdateIntent>(raw)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clearPublishIntent(context: Context, userId: String, campaignId: String) {
        val prefs = context.getSharedPreferences(PREFS_APP_UPDATES, Context.MODE_PRIVATE)
        val key = "${userId}_campaign_$campaignId"
        prefs.edit().remove(key).commit()
    }

    @Synchronized
    fun clearAllPublishIntentsForUser(context: Context, userId: String) {
        val prefs = context.getSharedPreferences(PREFS_APP_UPDATES, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val prefix = "${userId}_"
        for (k in prefs.all.keys) {
            if (k.startsWith(prefix)) {
                editor.remove(k)
            }
        }
        editor.commit()
    }

    // -------------------------------------------------------------------------
    // Campaign Creation Financial Intents
    // -------------------------------------------------------------------------

    private fun createCampaignKey(userId: String, packageName: String, slotNumber: Int): String =
        "${userId}_create_${slotNumber}_${packageName.trim().lowercase()}"

    @Synchronized
    fun saveCreateCampaignIntent(context: Context, intent: PendingCreateCampaignIntent) {
        val prefs = context.getSharedPreferences(PREFS_CREATE_CAMPAIGNS, Context.MODE_PRIVATE)
        val key = createCampaignKey(intent.userId, intent.packageName, intent.slotNumber)
        val serialized = json.encodeToString(intent)
        val committed = prefs.edit().putString(key, serialized).commit()
        if (!committed) {
            throw IllegalStateException("Failed to commit campaign creation financial intent to disk")
        }
    }

    @Synchronized
    fun peekCreateCampaignIntent(
        context: Context,
        userId: String,
        packageName: String,
        slotNumber: Int,
    ): PendingCreateCampaignIntent? {
        val prefs = context.getSharedPreferences(PREFS_CREATE_CAMPAIGNS, Context.MODE_PRIVATE)
        val key = createCampaignKey(userId, packageName, slotNumber)
        val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            json.decodeFromString<PendingCreateCampaignIntent>(raw)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clearCreateCampaignIntent(
        context: Context,
        userId: String,
        packageName: String,
        slotNumber: Int,
    ) {
        val prefs = context.getSharedPreferences(PREFS_CREATE_CAMPAIGNS, Context.MODE_PRIVATE)
        val key = createCampaignKey(userId, packageName, slotNumber)
        prefs.edit().remove(key).commit()
    }

    @Synchronized
    fun clearAllCreateCampaignIntentsForUser(context: Context, userId: String) {
        val prefs = context.getSharedPreferences(PREFS_CREATE_CAMPAIGNS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val prefix = "${userId}_"
        for (k in prefs.all.keys) {
            if (k.startsWith(prefix)) {
                editor.remove(k)
            }
        }
        editor.commit()
    }
}
