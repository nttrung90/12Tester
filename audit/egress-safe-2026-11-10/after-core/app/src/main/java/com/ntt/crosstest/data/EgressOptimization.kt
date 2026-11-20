package com.ntt.crosstest.data

import android.os.SystemClock
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

enum class EgressSyncType {
    LOCAL_CACHE_HIT,           // 0 network requests
    SINGLE_FLIGHT_DEDUP,       // In-flight request coalesced
    NETWORK_UNCHANGED_304,     // Network request returned, 100% blocks unchanged
    NETWORK_PARTIAL_SYNC,      // Some blocks changed, some unchanged
    NETWORK_FULL_SYNC,         // All blocks changed
    FALLBACK_LEGACY_RPC,       // Fallback to legacy RPC
}

enum class EgressTriggerSource {
    COLD_START,
    RESUME,
    PULL_REFRESH,
    MUTATION,
    PREFETCH,
    TAB_SWITCH,
    MANUAL,
    UNKNOWN,
}

data class EgressTelemetryRecord(
    val rpcName: String,
    val syncType: EgressSyncType,
    val triggerSource: EgressTriggerSource,
    val wireBytesEstimate: Long?,
    val decodedBytesEstimate: Long,
    val changedBlockCount: Int = 0,
    val totalBlockCount: Int = 0,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * Feature flags, capability caching, and lightweight telemetry for Egress Optimization v2.
 * Defaults are strictly false (safe legacy baseline).
 */
object EgressOptimizationConfig {
    @Volatile var isHomeFeedSyncV2Enabled: Boolean = false
    @Volatile var isDashboardSyncV2Enabled: Boolean = false
    @Volatile var isCampaignDetailSyncV2Enabled: Boolean = false
    @Volatile var isCandidatesV2Enabled: Boolean = false

    var clock: () -> Long = { SystemClock.elapsedRealtime() }

    const val NEGATIVE_CAPABILITY_TTL_MS = 5 * 60 * 1000L // 5 minutes
    private val disabledRpcUntilMs = ConcurrentHashMap<String, Long>()

    fun applyFromAppConfig(config: AppConfig) {
        isHomeFeedSyncV2Enabled = config.egressSyncHomeFeedV2Enabled
        isDashboardSyncV2Enabled = config.egressSyncDashboardV2Enabled
        isCampaignDetailSyncV2Enabled = config.egressSyncCampaignDetailV2Enabled
        isCandidatesV2Enabled = config.egressCandidatesV2Enabled
    }

    fun setAllEnabled(enabled: Boolean) {
        isHomeFeedSyncV2Enabled = enabled
        isDashboardSyncV2Enabled = enabled
        isCampaignDetailSyncV2Enabled = enabled
        isCandidatesV2Enabled = enabled
    }

    fun markRpcUnavailable(rpcName: String, nowMs: Long = clock()) {
        disabledRpcUntilMs[rpcName] = nowMs + NEGATIVE_CAPABILITY_TTL_MS
    }

    fun isRpcAvailable(rpcName: String, nowMs: Long = clock()): Boolean {
        val until = disabledRpcUntilMs[rpcName] ?: return true
        if (nowMs >= until) {
            disabledRpcUntilMs.remove(rpcName, until)
            return true
        }
        return false
    }

    fun resetCapabilityCache() {
        disabledRpcUntilMs.clear()
    }

    /**
     * Strict classifier: Only returns true for genuine 404 + PGRST202 targeting the expected function name.
     * Never classifies 401/403/5xx/timeout/deserialization errors as missing RPC.
     */
    fun isStrictMissingRpc(error: Throwable, expectedFunctionName: String): Boolean {
        if (error is CancellationException) return false
        val missing = error as? PostgrestRestException ?: return false
        if (missing.statusCode != 404 || !missing.code.equals("PGRST202", ignoreCase = true)) return false
        // Server error only: Exception.message also contains the requested URL.
        val functionSignature = Regex("""\b${Regex.escape(expectedFunctionName)}\s*\(""")
        return functionSignature.containsMatchIn(missing.error)
    }
}

object EgressTelemetry {
    private val totalRequests = AtomicLong(0L)
    private val totalLocalCacheHits = AtomicLong(0L)
    private val totalSingleFlightDeduplicated = AtomicLong(0L)
    private val totalNetworkUnchanged = AtomicLong(0L)
    private val totalNetworkPartial = AtomicLong(0L)
    private val totalNetworkFull = AtomicLong(0L)
    private val totalFallbackLegacy = AtomicLong(0L)
    private val totalEstimatedWireBytes = AtomicLong(0L)
    private val totalEstimatedDecodedBytes = AtomicLong(0L)

    private val recentRecords = ConcurrentLinkedQueue<EgressTelemetryRecord>()
    private const val MAX_RECENT_RECORDS = 100

    /**
     * Strictly records aggregated metrics without logging any user IDs, JWTs,
     * URLs, report content, or credentials.
     */
    fun recordEvent(
        rpcName: String,
        syncType: EgressSyncType,
        triggerSource: EgressTriggerSource = EgressTriggerSource.UNKNOWN,
        wireBytesEstimate: Long? = null,
        decodedBytesEstimate: Long = 0L,
        changedBlockCount: Int = 0,
        totalBlockCount: Int = 0,
    ) {
        totalRequests.incrementAndGet()
        when (syncType) {
            EgressSyncType.LOCAL_CACHE_HIT -> totalLocalCacheHits.incrementAndGet()
            EgressSyncType.SINGLE_FLIGHT_DEDUP -> totalSingleFlightDeduplicated.incrementAndGet()
            EgressSyncType.NETWORK_UNCHANGED_304 -> totalNetworkUnchanged.incrementAndGet()
            EgressSyncType.NETWORK_PARTIAL_SYNC -> totalNetworkPartial.incrementAndGet()
            EgressSyncType.NETWORK_FULL_SYNC -> totalNetworkFull.incrementAndGet()
            EgressSyncType.FALLBACK_LEGACY_RPC -> totalFallbackLegacy.incrementAndGet()
        }
        if (wireBytesEstimate != null && wireBytesEstimate > 0) {
            totalEstimatedWireBytes.addAndGet(wireBytesEstimate)
        }
        if (decodedBytesEstimate > 0) {
            totalEstimatedDecodedBytes.addAndGet(decodedBytesEstimate)
        }

        val record = EgressTelemetryRecord(
            rpcName = rpcName,
            syncType = syncType,
            triggerSource = triggerSource,
            wireBytesEstimate = wireBytesEstimate,
            decodedBytesEstimate = decodedBytesEstimate,
            changedBlockCount = changedBlockCount,
            totalBlockCount = totalBlockCount,
        )
        recentRecords.offer(record)
        while (recentRecords.size > MAX_RECENT_RECORDS) {
            recentRecords.poll()
        }
    }

    fun getStats(): Map<String, Long> = mapOf(
        "totalRequests" to totalRequests.get(),
        "totalLocalCacheHits" to totalLocalCacheHits.get(),
        "totalSingleFlightDeduplicated" to totalSingleFlightDeduplicated.get(),
        "totalNetworkUnchanged" to totalNetworkUnchanged.get(),
        "totalNetworkPartial" to totalNetworkPartial.get(),
        "totalNetworkFull" to totalNetworkFull.get(),
        "totalFallbackLegacy" to totalFallbackLegacy.get(),
        "totalEstimatedWireBytes" to totalEstimatedWireBytes.get(),
        "totalEstimatedDecodedBytes" to totalEstimatedDecodedBytes.get(),
    )

    fun getRecentRecords(): List<EgressTelemetryRecord> = recentRecords.toList()

    fun resetStats() {
        totalRequests.set(0L)
        totalLocalCacheHits.set(0L)
        totalSingleFlightDeduplicated.set(0L)
        totalNetworkUnchanged.set(0L)
        totalNetworkPartial.set(0L)
        totalNetworkFull.set(0L)
        totalFallbackLegacy.set(0L)
        totalEstimatedWireBytes.set(0L)
        totalEstimatedDecodedBytes.set(0L)
        recentRecords.clear()
    }
}
