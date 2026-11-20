// finish-client.mjs
// Reviewed-to-be-applied Node script for authorized egress task client completion.
//
// SAFETY GUARANTEES:
// - Confined strictly to the 8 files in audit/egress-safe-2026-11-10/after-core-hashes.json.
// - Verifies SHA256 hashes of all raw bytes before ANY file is modified; aborts on any mismatch.
// - Resolves real absolute paths under F:/AI/12Tester, rejecting outside paths.
// - Zero subprocesses, zero network calls, zero file deletions, zero git commands.
// - Preserves UTF-8 and per-file line-ending styles (CRLF / LF).

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const WORKSPACE_ROOT = path.resolve('F:/AI/12Tester');
const MANIFEST_REL_PATH = 'audit/egress-safe-2026-11-10/after-core-hashes.json';
const MANIFEST_ABS_PATH = path.resolve(WORKSPACE_ROOT, MANIFEST_REL_PATH);

function assertSafePath(targetAbsPath) {
    const normalizedTarget = fs.realpathSync(targetAbsPath);
    const normalizedRoot = fs.realpathSync(WORKSPACE_ROOT);
    if (!normalizedTarget.startsWith(normalizedRoot + path.sep)) {
        throw new Error(`Security violation: target path outside workspace: ${targetAbsPath}`);
    }
}

function computeSha256(buffer) {
    return crypto.createHash('sha256').update(buffer).digest('hex');
}

function detectEol(text) {
    return text.includes('\r\n') ? '\r\n' : '\n';
}

function applyReplacement(source, target, replacement, label) {
    const srcNorm = source.replace(/\r\n/g, '\n');
    const targetNorm = target.replace(/\r\n/g, '\n');
    const replNorm = replacement.replace(/\r\n/g, '\n');

    const index = srcNorm.indexOf(targetNorm);
    if (index === -1) {
        throw new Error(`Failed to locate target block for [${label}]`);
    }
    const lastIndex = srcNorm.lastIndexOf(targetNorm);
    if (index !== lastIndex) {
        throw new Error(`Ambiguous target block for [${label}] (multiple occurrences)`);
    }

    const modifiedNorm = srcNorm.slice(0, index) + replNorm + srcNorm.slice(index + targetNorm.length);
    const eol = detectEol(source);
    if (eol === '\r\n') {
        return modifiedNorm.replace(/\n/g, '\r\n');
    }
    return modifiedNorm;
}

// ----------------------------------------------------------------------------
// Step 1: Read and validate manifest
// ----------------------------------------------------------------------------
assertSafePath(MANIFEST_ABS_PATH);
const manifestRaw = fs.readFileSync(MANIFEST_ABS_PATH, 'utf8');
const manifest = JSON.parse(manifestRaw);

const ALLOWED_TARGETS = [
    "app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt",
    "app/src/main/java/com/ntt/crosstest/ui/screens/ProfileScreen.kt",
    "app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt",
    "app/src/main/java/com/ntt/crosstest/data/EgressOptimization.kt",
    "app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt",
    "app/src/main/java/com/ntt/crosstest/ui/viewmodels/ProfileViewModel.kt",
    "app/src/main/java/com/ntt/crosstest/ui/screens/CampaignDetailScreen.kt",
    "app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt",
];

console.log('[1/4] Validating manifest contents...');
const manifestKeys = Object.keys(manifest).sort();
if (manifestKeys.length !== ALLOWED_TARGETS.length || !ALLOWED_TARGETS.every(k => manifest[k])) {
    throw new Error('Manifest mismatch: does not contain exactly the 8 allowed target files');
}

// ----------------------------------------------------------------------------
// Step 2: Read raw bytes and verify SHA256 of all target files before any write
// ----------------------------------------------------------------------------
console.log('[2/4] Verifying SHA256 of raw bytes for all 8 target files...');
const fileBuffers = new Map();
const fileTexts = new Map();

for (const relPath of ALLOWED_TARGETS) {
    const absPath = path.resolve(WORKSPACE_ROOT, relPath);
    assertSafePath(absPath);

    if (!fs.existsSync(absPath)) {
        throw new Error(`Missing target file: ${relPath}`);
    }

    const buffer = fs.readFileSync(absPath);
    const hash = computeSha256(buffer);
    const expectedHash = manifest[relPath];

    if (hash !== expectedHash) {
        throw new Error(`SHA256 mismatch for ${relPath}:\n  Expected: ${expectedHash}\n  Actual:   ${hash}`);
    }

    fileBuffers.set(relPath, buffer);
    fileTexts.set(relPath, buffer.toString('utf8'));
    console.log(`  ✓ ${relPath} (${hash.slice(0, 12)}...)`);
}

// ----------------------------------------------------------------------------
// Step 3: Compute modifications in memory
// ----------------------------------------------------------------------------
console.log('[3/4] Computing modifications in memory...');
const modifiedFiles = new Map();

// --- 3.1: EgressOptimization.kt ---
{
    const rel = "app/src/main/java/com/ntt/crosstest/data/EgressOptimization.kt";
    let content = fileTexts.get(rel);

    const targetTelemetry = `object EgressTelemetry {
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
}`;

    const replTelemetry = `object EgressTelemetry {
    private val totalRequests = AtomicLong(0L)
    private val totalLocalCacheHits = AtomicLong(0L)
    private val totalSingleFlightDeduplicated = AtomicLong(0L)
    private val totalNetworkUnchanged = AtomicLong(0L)
    private val totalNetworkPartial = AtomicLong(0L)
    private val totalNetworkFull = AtomicLong(0L)
    private val totalFallbackLegacy = AtomicLong(0L)
    private val totalEstimatedWireBytes = AtomicLong(0L)
    private val totalEstimatedDecodedBytes = AtomicLong(0L)
    private val totalUnknownWireBytes = AtomicLong(0L)
    private val totalKnownWireBytesSamples = AtomicLong(0L)

    private val recentRecords = ConcurrentLinkedQueue<EgressTelemetryRecord>()
    private const val MAX_RECENT_RECORDS = 100

    /**
     * Explicit counter for actual HTTP / RPC network attempts.
     * Missing probes, repairs, legacy fallbacks, and failures call this;
     * local cache hits and single-flight joins add zero.
     */
    fun noteNetworkAttempt(rpcName: String) {
        totalRequests.incrementAndGet()
    }

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
            totalKnownWireBytesSamples.incrementAndGet()
        } else if (syncType != EgressSyncType.LOCAL_CACHE_HIT && syncType != EgressSyncType.SINGLE_FLIGHT_DEDUP) {
            totalUnknownWireBytes.incrementAndGet()
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
        "totalUnknownWireBytes" to totalUnknownWireBytes.get(),
        "totalKnownWireBytesSamples" to totalKnownWireBytesSamples.get(),
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
        totalUnknownWireBytes.set(0L)
        totalKnownWireBytesSamples.set(0L)
        recentRecords.clear()
    }
}`;

    content = applyReplacement(content, targetTelemetry, replTelemetry, "EgressTelemetry in EgressOptimization.kt");
    modifiedFiles.set(rel, content);
}

// --- 3.2: CampaignRepository.kt ---
{
    const rel = "app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt";
    let content = fileTexts.get(rel);

    // 1. Restore legacy isMissingRpc semantics
    const targetIsMissing = `    private fun isMissingRpc(error: Throwable, expectedFunctionName: String): Boolean =
        EgressOptimizationConfig.isStrictMissingRpc(error, expectedFunctionName)`;

    const replIsMissing = ``;
    content = applyReplacement(content, targetIsMissing, replIsMissing, "isMissingRpc in CampaignRepository.kt");
    content = applyReplacement(content,
        `    private fun isMissingRpc(error: Exception): Boolean {`,
        `    private fun isMissingRpc(error: Exception, functionName: String? = null): Boolean {`,
        "restore original legacy helper signature");

    // 2. deduped: accept staticRpcName and triggerSource for clean telemetry dedup
    const targetDeduped = `    private suspend fun <T> deduped(
        domain: String,
        key: String,
        forceRefresh: Boolean,
        isFresh: () -> Boolean,
        getCached: () -> T?,
        fetch: suspend () -> T,
    ): T {
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion(domain)
        val curUid = supabase.auth.currentUserOrNull()?.id
        val bizDate = businessDay().toString()

        if (!forceRefresh && isFresh()) {
            getCached()?.let { return it }
        }

        val flightKey = "$domain:$key:$gen:$mutVer:$curUid:$bizDate"

        var isNew = false
        val deferred = flightMap.computeIfAbsent(flightKey) {
            isNew = true
            CompletableDeferred()
        }

        if (isNew) {
            repositoryScope.launch {
                try {
                    val result = fetch()
                    flightMap.remove(flightKey, deferred)
                    deferred.complete(result)
                } catch (t: Throwable) {
                    flightMap.remove(flightKey, deferred)
                    deferred.completeExceptionally(t)
                } finally {
                    flightMap.remove(flightKey, deferred)
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        return deferred.await() as T
    }`;

    const replDeduped = `    private suspend fun <T> deduped(
        domain: String,
        key: String,
        forceRefresh: Boolean,
        isFresh: () -> Boolean,
        getCached: () -> T?,
        staticRpcName: String? = null,
        triggerSource: EgressTriggerSource = EgressTriggerSource.UNKNOWN,
        fetch: suspend () -> T,
    ): T {
        val gen = sessionGeneration.get()
        val mutVer = getMutationVersion(domain)
        val curUid = supabase.auth.currentUserOrNull()?.id
        val bizDate = businessDay().toString()

        if (!forceRefresh && isFresh()) {
            getCached()?.let { return it }
        }

        val flightKey = "$domain:$key:$gen:$mutVer:$curUid:$bizDate"

        var isNew = false
        val deferred = flightMap.computeIfAbsent(flightKey) {
            isNew = true
            CompletableDeferred()
        }

        if (!isNew && staticRpcName != null) {
            EgressTelemetry.recordEvent(
                rpcName = staticRpcName,
                syncType = EgressSyncType.SINGLE_FLIGHT_DEDUP,
                triggerSource = triggerSource,
            )
        }

        if (isNew) {
            repositoryScope.launch {
                try {
                    val result = fetch()
                    flightMap.remove(flightKey, deferred)
                    deferred.complete(result)
                } catch (t: Throwable) {
                    flightMap.remove(flightKey, deferred)
                    deferred.completeExceptionally(t)
                } finally {
                    flightMap.remove(flightKey, deferred)
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        return deferred.await() as T
    }`;
    content = applyReplacement(content, targetDeduped, replDeduped, "deduped in CampaignRepository.kt");

    // 3. Local mutation invalidators: retain raw snapshots across local mutations
    // invalidateCampaign
    const targetInvalCamp = `        cachedMyJoinedCampaigns = null
        cachedHomeFeed = null
        homeFeedSnapshot = null
        cachedSlotsInfo = null
        statsCache.remove(campaignId)`;
    const replInvalCamp = `        cachedMyJoinedCampaigns = null
        cachedHomeFeed = null
        cachedSlotsInfo = null
        statsCache.remove(campaignId)`;
    content = applyReplacement(content, targetInvalCamp, replInvalCamp, "invalidateCampaign snapshots in CampaignRepository.kt");

    // invalidateMembership
    const targetInvalMem = `        cachedHomeFeed = null
        homeFeedSnapshot = null
        cachedSlotsInfo = null
        invalidateDetailCache(campaignId)`;
    const replInvalMem = `        cachedHomeFeed = null
        cachedSlotsInfo = null
        invalidateDetailCache(campaignId)`;
    content = applyReplacement(content, targetInvalMem, replInvalMem, "invalidateMembership snapshots in CampaignRepository.kt");

    // invalidateReportsAndStats
    const targetInvalReports = `        cachedTodayCheckins = null
        cachedHomeFeed = null
        homeFeedSnapshot = null
        invalidateDetailCache(campaignId)`;
    const replInvalReports = `        cachedTodayCheckins = null
        cachedHomeFeed = null
        invalidateDetailCache(campaignId)`;
    content = applyReplacement(content, targetInvalReports, replInvalReports, "invalidateReportsAndStats snapshots in CampaignRepository.kt");

    // invalidateCampaignLists
    const targetInvalLists = `        statsCache.clear()
        cachedSlotsInfo = null
        cachedHomeFeed = null
        homeFeedSnapshot = null
    }`;
    const replInvalLists = `        statsCache.clear()
        cachedSlotsInfo = null
        cachedHomeFeed = null
    }`;
    content = applyReplacement(content, targetInvalLists, replInvalLists, "invalidateCampaignLists snapshots in CampaignRepository.kt");

    // invalidateDashboardCache
    const targetInvalDash = `    private fun invalidateDashboardCache() {
        bumpMutationVersion("dashboard")
        cachedDashboard = null
        dashboardSnapshot = null
    }`;
    const replInvalDash = `    private fun invalidateDashboardCache() {
        bumpMutationVersion("dashboard")
        cachedDashboard = null
    }`;
    content = applyReplacement(content, targetInvalDash, replInvalDash, "invalidateDashboardCache snapshots in CampaignRepository.kt");

    // invalidateDetailCache
    const targetInvalDetail = `    private fun invalidateDetailCache(campaignId: String) {
        detailCache.keys.removeIf { it.startsWith("$campaignId|") }
        detailSnapshots.keys.removeIf { it.startsWith("$campaignId|") }
    }`;
    const replInvalDetail = `    private fun invalidateDetailCache(campaignId: String) {
        detailCache.keys.removeIf { it.startsWith("$campaignId|") }
    }`;
    content = applyReplacement(content, targetInvalDetail, replInvalDetail, "invalidateDetailCache in CampaignRepository.kt");

    // 4. Candidate RPC: strict classifier, error propagation, honest bytes, prefetch fallback trigger
    const targetCandidates = `suspend fun getNextCampaignCandidates(currentCampaignId: String): List<NextCampaignCandidate> {
        if (EgressOptimizationConfig.isCandidatesV2Enabled && EgressOptimizationConfig.isRpcAvailable("get_next_campaign_candidates_v2")) {
            try {
                val params = buildJsonObject {
                    if (currentCampaignId.isNotBlank()) {
                        put("p_current_campaign_id", currentCampaignId)
                    }
                }
                val candidates = supabase.postgrest.rpc(
                    "get_next_campaign_candidates_v2",
                    params
                ).decodeAs<List<NextCampaignCandidate>>()
                EgressTelemetry.recordEvent(
                    rpcName = "get_next_campaign_candidates_v2",
                    syncType = EgressSyncType.NETWORK_FULL_SYNC,
                    triggerSource = EgressTriggerSource.PREFETCH,
                    wireBytesEstimate = null,
                    decodedBytesEstimate = 0L,
                    changedBlockCount = 1,
                    totalBlockCount = 1
                )
                return candidates
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isMissingRpc(e, "get_next_campaign_candidates_v2")) {
                    EgressOptimizationConfig.markRpcUnavailable("get_next_campaign_candidates_v2")
                } else {
                    Log.w(TAG, "get_next_campaign_candidates_v2 failed, falling back to home feed", e)
                }
            }
        }

        val feed = getHomeFeed(forceRefresh = true)`;

    const replCandidates = `    suspend fun getNextCampaignCandidates(
        currentCampaignId: String,
        triggerSource: EgressTriggerSource = EgressTriggerSource.PREFETCH,
    ): List<NextCampaignCandidate> {
        val startUid = supabase.auth.currentUserOrNull()?.id
        val startGen = sessionGeneration.get()
        val startDay = businessDay()
        val startMutation = getMutationVersion("campaigns")

        val rpcName = "get_next_campaign_candidates_v2"
        if (EgressOptimizationConfig.isCandidatesV2Enabled && EgressOptimizationConfig.isRpcAvailable(rpcName)) {
            try {
                EgressTelemetry.noteNetworkAttempt(rpcName)
                val params = buildJsonObject {
                    if (currentCampaignId.isNotBlank()) {
                        put("p_current_campaign_id", currentCampaignId)
                    }
                }
                val response = supabase.postgrest.rpc(rpcName, params)
                val rawJson = response.data
                val decodedBytes = rawJson.toByteArray(Charsets.UTF_8).size.toLong()
                val candidates = Json { ignoreUnknownKeys = true }.decodeFromString<List<NextCampaignCandidate>>(rawJson)

                val curUid = supabase.auth.currentUserOrNull()?.id
                val curGen = sessionGeneration.get()
                val curDay = businessDay()
                if (curGen != startGen || curUid != startUid || curDay != startDay ||
                    getMutationVersion("campaigns") != startMutation) {
                    return emptyList()
                }

                EgressTelemetry.recordEvent(
                    rpcName = rpcName,
                    syncType = EgressSyncType.NETWORK_FULL_SYNC,
                    triggerSource = triggerSource,
                    wireBytesEstimate = null,
                    decodedBytesEstimate = decodedBytes,
                    changedBlockCount = 1,
                    totalBlockCount = 1,
                )
                return candidates
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (EgressOptimizationConfig.isStrictMissingRpc(e, rpcName)) {
                    EgressOptimizationConfig.markRpcUnavailable(rpcName)
                } else {
                    throw e
                }
            }
        }

        val feed = getHomeFeed(forceRefresh = true, triggerSource = EgressTriggerSource.PREFETCH)`;
    content = applyReplacement(content, targetCandidates, replCandidates, "getNextCampaignCandidates in CampaignRepository.kt");

    // 5. fetchConditionalSync and fetchLegacy
    const targetConditionalSync = `        val rawJson = try {
            supabase.postgrest.rpc(v2RpcName, syncParams).data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (EgressOptimizationConfig.isStrictMissingRpc(e, v2RpcName)) {
                EgressOptimizationConfig.markRpcUnavailable(v2RpcName)
                return fetchLegacy(legacyRpcName, legacyFetch, onClearSnapshot, triggerSource)
            }
            throw e
        }

        var mergeResult = validator(rawJson, effectiveBase, businessDate)

        if (mergeResult !is MergeResult.Success) {
            val repairParams = buildJsonObject {
                put("p_tokens", buildJsonObject {})
                for ((k, v) in extraParams) {
                    when (v) {
                        is String -> put(k, v)
                        is Boolean -> put(k, v)
                        is Number -> put(k, v)
                    }
                }
            }
            val repairRawJson = try {
                supabase.postgrest.rpc(v2RpcName, repairParams).data
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (EgressOptimizationConfig.isStrictMissingRpc(e, v2RpcName)) {
                    EgressOptimizationConfig.markRpcUnavailable(v2RpcName)
                    return fetchLegacy(legacyRpcName, legacyFetch, onClearSnapshot, triggerSource)
                }
                throw IllegalStateException("Repair RPC failed for $v2RpcName", e)
            }
            mergeResult = validator(repairRawJson, null, businessDate)
            if (mergeResult !is MergeResult.Success) {
                throw IllegalStateException("Protocol failure in repair for $v2RpcName: $mergeResult")
            }
        }

        val success = mergeResult as MergeResult.Success<T>
        val finalPayload = success.payload

        val now = monotonicNow()
        if (sessionGeneration.get() == sessionGen &&
            getMutationVersion(domain) == mutationVer &&
            businessDay() == businessDate &&
            supabase.auth.currentUserOrNull()?.id == scopeUid
        ) {
            val newSnapshot = ScopedSyncSnapshot(
                payload = finalPayload,
                tokens = success.tokens,
                scopeUid = scopeUid,
                sessionGeneration = sessionGen,
                mutationVersion = mutationVer,
                businessDate = businessDate,
                monotonicCreatedAtMs = now,
            )
            onSuccessSnapshot(newSnapshot)
        }

        val syncType = when {
            success.isAllUnchanged -> EgressSyncType.NETWORK_UNCHANGED_304
            success.changedBlockCount == success.totalBlockCount -> EgressSyncType.NETWORK_FULL_SYNC
            else -> EgressSyncType.NETWORK_PARTIAL_SYNC
        }
        EgressTelemetry.recordEvent(
            rpcName = v2RpcName,
            syncType = syncType,
            triggerSource = triggerSource,
            wireBytesEstimate = success.wireBytesEstimate,
            decodedBytesEstimate = success.decodedBytesEstimate,
            changedBlockCount = success.changedBlockCount,
            totalBlockCount = success.totalBlockCount,
        )

        return finalPayload
    }

    private suspend fun <T : Any> fetchLegacy(
        legacyRpcName: String,
        legacyFetch: suspend () -> T?,
        onClearSnapshot: () -> Unit,
        triggerSource: EgressTriggerSource,
    ): T? {
        onClearSnapshot()
        val result = try {
            legacyFetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!EgressOptimizationConfig.isStrictMissingRpc(e, legacyRpcName)) throw e
            return null
        }
        EgressTelemetry.recordEvent(
            rpcName = legacyRpcName,
            syncType = EgressSyncType.FALLBACK_LEGACY_RPC,
            triggerSource = triggerSource,
            wireBytesEstimate = null,
            decodedBytesEstimate = 0L,
        )
        return result
    }`;

    const replConditionalSync = `        EgressTelemetry.noteNetworkAttempt(v2RpcName)
        val rawJson = try {
            supabase.postgrest.rpc(v2RpcName, syncParams).data
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (EgressOptimizationConfig.isStrictMissingRpc(e, v2RpcName)) {
                EgressOptimizationConfig.markRpcUnavailable(v2RpcName)
                return fetchLegacy(legacyRpcName, legacyFetch, onClearSnapshot, triggerSource)
            }
            throw e
        }

        val initialDecodedBytes = rawJson.toByteArray(Charsets.UTF_8).size.toLong()
        var mergeResult = validator(rawJson, effectiveBase, businessDate)
        var repairDecodedBytes = 0L

        if (mergeResult !is MergeResult.Success) {
            val repairParams = buildJsonObject {
                put("p_tokens", buildJsonObject {})
                for ((k, v) in extraParams) {
                    when (v) {
                        is String -> put(k, v)
                        is Boolean -> put(k, v)
                        is Number -> put(k, v)
                    }
                }
            }
            EgressTelemetry.noteNetworkAttempt(v2RpcName)
            val repairRawJson = try {
                supabase.postgrest.rpc(v2RpcName, repairParams).data
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (EgressOptimizationConfig.isStrictMissingRpc(e, v2RpcName)) {
                    EgressOptimizationConfig.markRpcUnavailable(v2RpcName)
                    return fetchLegacy(legacyRpcName, legacyFetch, onClearSnapshot, triggerSource)
                }
                throw IllegalStateException("Repair RPC failed for $v2RpcName", e)
            }
            repairDecodedBytes = repairRawJson.toByteArray(Charsets.UTF_8).size.toLong()
            mergeResult = validator(repairRawJson, null, businessDate)
            if (mergeResult !is MergeResult.Success) {
                throw IllegalStateException("Protocol failure in repair for $v2RpcName: $mergeResult")
            }
        }

        val success = mergeResult as MergeResult.Success<T>
        val finalPayload = success.payload

        val now = monotonicNow()
        if (sessionGeneration.get() == sessionGen &&
            getMutationVersion(domain) == mutationVer &&
            businessDay() == businessDate &&
            supabase.auth.currentUserOrNull()?.id == scopeUid
        ) {
            val newSnapshot = ScopedSyncSnapshot(
                payload = finalPayload,
                tokens = success.tokens,
                scopeUid = scopeUid,
                sessionGeneration = sessionGen,
                mutationVersion = mutationVer,
                businessDate = businessDate,
                monotonicCreatedAtMs = now,
            )
            onSuccessSnapshot(newSnapshot)
        }

        val syncType = when {
            success.isAllUnchanged -> EgressSyncType.NETWORK_UNCHANGED_304
            success.changedBlockCount == success.totalBlockCount -> EgressSyncType.NETWORK_FULL_SYNC
            else -> EgressSyncType.NETWORK_PARTIAL_SYNC
        }
        val totalDecodedBytes = if (repairDecodedBytes > 0L) {
            initialDecodedBytes + repairDecodedBytes
        } else {
            success.decodedBytesEstimate
        }
        EgressTelemetry.recordEvent(
            rpcName = v2RpcName,
            syncType = syncType,
            triggerSource = triggerSource,
            wireBytesEstimate = success.wireBytesEstimate,
            decodedBytesEstimate = totalDecodedBytes,
            changedBlockCount = success.changedBlockCount,
            totalBlockCount = success.totalBlockCount,
        )

        return finalPayload
    }

    private suspend fun <T : Any> fetchLegacy(
        legacyRpcName: String,
        legacyFetch: suspend () -> T?,
        onClearSnapshot: () -> Unit,
        triggerSource: EgressTriggerSource,
    ): T? {
        onClearSnapshot()
        EgressTelemetry.noteNetworkAttempt(legacyRpcName)
        val result = try {
            legacyFetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!isMissingRpc(e, legacyRpcName)) throw e
            return null
        }
        EgressTelemetry.recordEvent(
            rpcName = legacyRpcName,
            syncType = EgressSyncType.FALLBACK_LEGACY_RPC,
            triggerSource = triggerSource,
            wireBytesEstimate = null,
            decodedBytesEstimate = 0L,
        )
        return result
    }`;
    content = applyReplacement(content, targetConditionalSync, replConditionalSync, "fetchConditionalSync/fetchLegacy in CampaignRepository.kt");

    // 6. getHomeFeed: optional triggerSource, staticRpcName single-flight, baseSnapshot mutationVersion decoupling
    const targetGetHomeFeed = `    suspend fun getHomeFeed(forceRefresh: Boolean = false): HomeFeedResult? {
        val currentUid = supabase.auth.currentUserOrNull()?.id
        val requestDate = businessDay()
        val gen = sessionGeneration.get()
        val dashboardMutVer = getMutationVersion("dashboard")
        val campMutVer = getMutationVersion("campaigns")
        val statsMutVer = getMutationVersion("stats")
        val isFresh = { cachedHomeFeed?.isFresh(CACHE_TTL_MS, gen, dashboardMutVer, currentUid, currentBusinessDate = requestDate, nowMs = monotonicNow()) == true }
        return deduped("dashboard", "homeFeed", forceRefresh, isFresh, { cachedHomeFeed?.value }) {
            val result = fetchConditionalSync(
                v2RpcName = "get_home_feed_sync_v2",
                legacyRpcName = "get_home_feed",
                isV2Enabled = EgressOptimizationConfig.isHomeFeedSyncV2Enabled,
                domain = "dashboard",
                scopeUid = currentUid,
                sessionGen = gen,
                mutationVer = dashboardMutVer,
                businessDate = requestDate,
                triggerSource = if (forceRefresh) EgressTriggerSource.PULL_REFRESH else EgressTriggerSource.UNKNOWN,
                baseSnapshot = homeFeedSnapshot?.takeIf {
                    it.sessionGeneration == gen &&
                    it.mutationVersion == dashboardMutVer &&
                    it.scopeUid == currentUid &&
                    it.businessDate == requestDate
                },
                validator = { rawJson, base, date ->
                    EgressSyncProtocol.validateAndMergeHomeFeed(rawJson, base, date)
                },
                onSuccessSnapshot = { homeFeedSnapshot = it },
                onClearSnapshot = { homeFeedSnapshot = null },
                legacyFetch = {
                    supabase.postgrest.rpc("get_home_feed").decodeAs<HomeFeedResult>()
                }
            ) ?: return@deduped null`;

    const replGetHomeFeed = `    suspend fun getHomeFeed(
        forceRefresh: Boolean = false,
        triggerSource: EgressTriggerSource = EgressTriggerSource.UNKNOWN,
    ): HomeFeedResult? {
        val currentUid = supabase.auth.currentUserOrNull()?.id
        val requestDate = businessDay()
        val gen = sessionGeneration.get()
        val dashboardMutVer = getMutationVersion("dashboard")
        val campMutVer = getMutationVersion("campaigns")
        val statsMutVer = getMutationVersion("stats")
        val isFresh = { cachedHomeFeed?.isFresh(CACHE_TTL_MS, gen, dashboardMutVer, currentUid, currentBusinessDate = requestDate, nowMs = monotonicNow()) == true }
        if (!forceRefresh && isFresh()) {
            cachedHomeFeed?.value?.let {
                EgressTelemetry.recordEvent(
                    rpcName = "get_home_feed_sync_v2",
                    syncType = EgressSyncType.LOCAL_CACHE_HIT,
                    triggerSource = triggerSource,
                )
                return it
            }
        }
        return deduped(
            domain = "dashboard",
            key = "homeFeed",
            forceRefresh = forceRefresh,
            isFresh = isFresh,
            getCached = { cachedHomeFeed?.value },
            staticRpcName = "get_home_feed_sync_v2",
            triggerSource = triggerSource,
        ) {
            val result = fetchConditionalSync(
                v2RpcName = "get_home_feed_sync_v2",
                legacyRpcName = "get_home_feed",
                isV2Enabled = EgressOptimizationConfig.isHomeFeedSyncV2Enabled,
                domain = "dashboard",
                scopeUid = currentUid,
                sessionGen = gen,
                mutationVer = dashboardMutVer,
                businessDate = requestDate,
                triggerSource = triggerSource,
                baseSnapshot = homeFeedSnapshot?.takeIf {
                    it.sessionGeneration == gen &&
                    it.scopeUid == currentUid &&
                    it.businessDate == requestDate
                },
                validator = { rawJson, base, date ->
                    EgressSyncProtocol.validateAndMergeHomeFeed(rawJson, base, date)
                },
                onSuccessSnapshot = { homeFeedSnapshot = it },
                onClearSnapshot = { homeFeedSnapshot = null },
                legacyFetch = {
                    supabase.postgrest.rpc("get_home_feed").decodeAs<HomeFeedResult>()
                }
            ) ?: return@deduped null`;
    content = applyReplacement(content, targetGetHomeFeed, replGetHomeFeed, "getHomeFeed in CampaignRepository.kt");

    // 7. getMyDashboard: optional triggerSource, baseSnapshot decoupling, staticRpcName single-flight
    const targetGetMyDashboard = `    suspend fun getMyDashboard(forceRefresh: Boolean = false): MyDashboardResult? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        val requestDate = businessDay()
        val gen = sessionGeneration.get()
        val dashboardMutVer = getMutationVersion("dashboard")
        val campMutVer = getMutationVersion("campaigns")
        val profMutVer = getMutationVersion("profile")
        val isFresh = { cachedDashboard?.isFresh(SHORT_CACHE_TTL_MS, gen, dashboardMutVer, uid, currentBusinessDate = requestDate, nowMs = monotonicNow()) == true }
        val getCached = {
            cachedDashboard?.value?.let { d ->
                val mapped = d.notifications.map { n ->
                    if (isNotificationLocallyRead(uid, n.id)) n.copy(isRead = true) else n
                }
                d.copy(notifications = mapped)
            }
        }
        return deduped("dashboard", "myDashboard", forceRefresh, isFresh, getCached) {
            val result = fetchConditionalSync(
                v2RpcName = "get_my_dashboard_sync_v2",
                legacyRpcName = "get_my_dashboard",
                isV2Enabled = EgressOptimizationConfig.isDashboardSyncV2Enabled,
                extraParams = mapOf("p_include_leaderboard" to true),
                domain = "dashboard",
                scopeUid = uid,
                sessionGen = gen,
                mutationVer = dashboardMutVer,
                businessDate = requestDate,
                triggerSource = EgressTriggerSource.UNKNOWN,
                baseSnapshot = dashboardSnapshot?.takeIf {
                    it.sessionGeneration == gen && it.mutationVersion == dashboardMutVer &&
                        it.scopeUid == uid && it.businessDate == requestDate
                },
                validator = { body, base, date -> EgressSyncProtocol.validateAndMergeDashboard(body, base, date) },
                onSuccessSnapshot = { dashboardSnapshot = it },
                onClearSnapshot = { dashboardSnapshot = null },
                legacyFetch = { supabase.postgrest.rpc("get_my_dashboard").decodeAs<MyDashboardResult>() },
            ) ?: return@deduped null`;

    const replGetMyDashboard = `    suspend fun getMyDashboard(
        forceRefresh: Boolean = false,
        triggerSource: EgressTriggerSource = EgressTriggerSource.UNKNOWN,
    ): MyDashboardResult? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        val requestDate = businessDay()
        val gen = sessionGeneration.get()
        val dashboardMutVer = getMutationVersion("dashboard")
        val campMutVer = getMutationVersion("campaigns")
        val profMutVer = getMutationVersion("profile")
        val isFresh = { cachedDashboard?.isFresh(SHORT_CACHE_TTL_MS, gen, dashboardMutVer, uid, currentBusinessDate = requestDate, nowMs = monotonicNow()) == true }
        val getCached = {
            cachedDashboard?.value?.let { d ->
                val mapped = d.notifications.map { n ->
                    if (isNotificationLocallyRead(uid, n.id)) n.copy(isRead = true) else n
                }
                d.copy(notifications = mapped)
            }
        }
        if (!forceRefresh && isFresh()) {
            getCached()?.let {
                EgressTelemetry.recordEvent(
                    rpcName = "get_my_dashboard_sync_v2",
                    syncType = EgressSyncType.LOCAL_CACHE_HIT,
                    triggerSource = triggerSource,
                )
                return it
            }
        }
        return deduped(
            domain = "dashboard",
            key = "myDashboard",
            forceRefresh = forceRefresh,
            isFresh = isFresh,
            getCached = getCached,
            staticRpcName = "get_my_dashboard_sync_v2",
            triggerSource = triggerSource,
        ) {
            val result = fetchConditionalSync(
                v2RpcName = "get_my_dashboard_sync_v2",
                legacyRpcName = "get_my_dashboard",
                isV2Enabled = EgressOptimizationConfig.isDashboardSyncV2Enabled,
                extraParams = mapOf("p_include_leaderboard" to true),
                domain = "dashboard",
                scopeUid = uid,
                sessionGen = gen,
                mutationVer = dashboardMutVer,
                businessDate = requestDate,
                triggerSource = triggerSource,
                baseSnapshot = dashboardSnapshot?.takeIf {
                    it.sessionGeneration == gen &&
                        it.scopeUid == uid && it.businessDate == requestDate
                },
                validator = { body, base, date -> EgressSyncProtocol.validateAndMergeDashboard(body, base, date) },
                onSuccessSnapshot = { dashboardSnapshot = it },
                onClearSnapshot = { dashboardSnapshot = null },
                legacyFetch = { supabase.postgrest.rpc("get_my_dashboard").decodeAs<MyDashboardResult>() },
            ) ?: return@deduped null`;
    content = applyReplacement(content, targetGetMyDashboard, replGetMyDashboard, "getMyDashboard in CampaignRepository.kt");

    // 8. getCampaignDetailPage: optional triggerSource, baseSnapshot decoupling, staticRpcName single-flight, bound detail snapshots
    const targetGetDetail = `        suspend fun getCampaignDetailPage(campaignId: String, forceRefresh: Boolean = false): CampaignDetailResult? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        val requestDate = businessDay()
        val cacheKey = "\${campaignId}|\${uid}"
        val gen = sessionGeneration.get()
        val campMutVer = getMutationVersion("campaigns")
        val reportsMutVer = getMutationVersion("reports")
        val profMutVer = getMutationVersion("profile")
        val isFresh = { detailCache[cacheKey]?.isFresh(CACHE_TTL_MS, gen, campMutVer, uid, currentBusinessDate = requestDate, nowMs = monotonicNow()) == true }
        return deduped("campaigns", "detail:$cacheKey", forceRefresh, isFresh, { detailCache[cacheKey]?.value }) {
            val now = monotonicNow()
            val result = fetchConditionalSync(
                v2RpcName = "get_campaign_detail_sync_v2",
                legacyRpcName = "get_campaign_detail",
                isV2Enabled = EgressOptimizationConfig.isCampaignDetailSyncV2Enabled,
                extraParams = mapOf("p_campaign_id" to campaignId),
                domain = "campaigns",
                scopeUid = uid,
                sessionGen = gen,
                mutationVer = campMutVer,
                businessDate = requestDate,
                triggerSource = EgressTriggerSource.UNKNOWN,
                baseSnapshot = detailSnapshots[cacheKey]?.takeIf {
                    it.sessionGeneration == gen && it.mutationVersion == campMutVer &&
                        it.scopeUid == uid && it.businessDate == requestDate
                },
                validator = { body, base, date -> EgressSyncProtocol.validateAndMergeCampaignDetail(body, base, date) },
                onSuccessSnapshot = { detailSnapshots[cacheKey] = it },
                onClearSnapshot = { detailSnapshots.remove(cacheKey) },
                legacyFetch = {
                    supabase.postgrest.rpc("get_campaign_detail", CampaignIdParams(campaignId = campaignId))
                        .decodeAs<CampaignDetailResult>()
                },
            ) ?: return@deduped null`;

    const replGetDetail = `    suspend fun getCampaignDetailPage(
        campaignId: String,
        forceRefresh: Boolean = false,
        triggerSource: EgressTriggerSource = EgressTriggerSource.UNKNOWN,
    ): CampaignDetailResult? {
        val uid = supabase.auth.currentUserOrNull()?.id ?: return null
        val requestDate = businessDay()
        val cacheKey = "\${campaignId}|\${uid}"
        val gen = sessionGeneration.get()
        val campMutVer = getMutationVersion("campaigns")
        val reportsMutVer = getMutationVersion("reports")
        val profMutVer = getMutationVersion("profile")
        val isFresh = { detailCache[cacheKey]?.isFresh(CACHE_TTL_MS, gen, campMutVer, uid, currentBusinessDate = requestDate, nowMs = monotonicNow()) == true }
        if (!forceRefresh && isFresh()) {
            detailCache[cacheKey]?.value?.let {
                EgressTelemetry.recordEvent(
                    rpcName = "get_campaign_detail_sync_v2",
                    syncType = EgressSyncType.LOCAL_CACHE_HIT,
                    triggerSource = triggerSource,
                )
                return it
            }
        }
        return deduped(
            domain = "campaigns",
            key = "detail:$cacheKey",
            forceRefresh = forceRefresh,
            isFresh = isFresh,
            getCached = { detailCache[cacheKey]?.value },
            staticRpcName = "get_campaign_detail_sync_v2",
            triggerSource = triggerSource,
        ) {
            val now = monotonicNow()
            val result = fetchConditionalSync(
                v2RpcName = "get_campaign_detail_sync_v2",
                legacyRpcName = "get_campaign_detail",
                isV2Enabled = EgressOptimizationConfig.isCampaignDetailSyncV2Enabled,
                extraParams = mapOf("p_campaign_id" to campaignId),
                domain = "campaigns",
                scopeUid = uid,
                sessionGen = gen,
                mutationVer = campMutVer,
                businessDate = requestDate,
                triggerSource = triggerSource,
                baseSnapshot = detailSnapshots[cacheKey]?.takeIf {
                    it.sessionGeneration == gen &&
                        it.scopeUid == uid && it.businessDate == requestDate
                },
                validator = { body, base, date -> EgressSyncProtocol.validateAndMergeCampaignDetail(body, base, date) },
                onSuccessSnapshot = {
                    detailSnapshots[cacheKey] = it
                    if (detailSnapshots.size > MAX_MAP_ENTRIES) {
                        val oldestKey = detailSnapshots.entries.minByOrNull { entry -> entry.value.monotonicCreatedAtMs }?.key
                        if (oldestKey != null) detailSnapshots.remove(oldestKey)
                    }
                },
                onClearSnapshot = { detailSnapshots.remove(cacheKey) },
                legacyFetch = {
                    supabase.postgrest.rpc("get_campaign_detail", CampaignIdParams(campaignId = campaignId))
                        .decodeAs<CampaignDetailResult>()
                },
            ) ?: return@deduped null`;
    content = applyReplacement(content, targetGetDetail, replGetDetail, "getCampaignDetailPage in CampaignRepository.kt");

    modifiedFiles.set(rel, content);
}

// --- 3.3: HomeViewModel.kt ---
{
    const rel = "app/src/main/java/com/ntt/crosstest/ui/viewmodels/HomeViewModel.kt";
    let content = fileTexts.get(rel);

    // Add import
    const targetImport = `import com.ntt.crosstest.data.CachedSessionData`;
    const replImport = `import com.ntt.crosstest.data.CachedSessionData\nimport com.ntt.crosstest.data.EgressTriggerSource`;
    content = applyReplacement(content, targetImport, replImport, "import EgressTriggerSource in HomeViewModel.kt");

    // COLD_START trigger in init
    const targetInitRefresh = `        refresh(context = context, forceRefresh = false)`;
    const replInitRefresh = `        refresh(context = context, forceRefresh = false, triggerSource = EgressTriggerSource.COLD_START)`;
    content = applyReplacement(content, targetInitRefresh, replInitRefresh, "cold start refresh in HomeViewModel.kt");

    // MUTATION trigger in observeSubmittedReports
    const targetObsReports = `                    if (!event.isMissionReport) {
                        markCampaignTestedToday(event.campaignId)
                    }
                    refresh(forceRefresh = true)`;
    const replObsReports = `                    if (!event.isMissionReport) {
                        markCampaignTestedToday(event.campaignId)
                    }
                    refresh(forceRefresh = true, triggerSource = EgressTriggerSource.MUTATION)`;
    content = applyReplacement(content, targetObsReports, replObsReports, "mutation refresh in HomeViewModel.kt");

    // Update refresh signature and pass triggerSource to repo
    const targetRefreshSig = `    fun refresh(context: android.content.Context? = null, forceRefresh: Boolean = false) {`;
    const replRefreshSig = `    fun refresh(
        context: android.content.Context? = null,
        forceRefresh: Boolean = false,
        triggerSource: EgressTriggerSource = EgressTriggerSource.UNKNOWN,
    ) {`;
    content = applyReplacement(content, targetRefreshSig, replRefreshSig, "refresh signature in HomeViewModel.kt");

    const targetAsyncCalls = `                val (feed, dashboard) = if (isAuthed) {
                    coroutineScope {
                        val feedDeferred = async { repository.getHomeFeed(forceRefresh = forceRefresh) }
                        val dashDeferred = async { repository.getMyDashboard(forceRefresh = forceRefresh) }
                        Pair(feedDeferred.await(), dashDeferred.await())
                    }
                } else {`;
    const replAsyncCalls = `                val (feed, dashboard) = if (isAuthed) {
                    coroutineScope {
                        val feedDeferred = async { repository.getHomeFeed(forceRefresh = forceRefresh, triggerSource = triggerSource) }
                        val dashDeferred = async { repository.getMyDashboard(forceRefresh = forceRefresh, triggerSource = triggerSource) }
                        Pair(feedDeferred.await(), dashDeferred.await())
                    }
                } else {`;
    content = applyReplacement(content, targetAsyncCalls, replAsyncCalls, "async repo calls in HomeViewModel.kt");

    modifiedFiles.set(rel, content);
}

// --- 3.4: HomeScreen.kt ---
{
    const rel = "app/src/main/java/com/ntt/crosstest/ui/screens/HomeScreen.kt";
    let content = fileTexts.get(rel);

    // Add import
    const targetImport = `import com.ntt.crosstest.ui.viewmodels.CampaignDisplayItem`;
    const replImport = `import com.ntt.crosstest.ui.viewmodels.CampaignDisplayItem\nimport com.ntt.crosstest.data.EgressTriggerSource`;
    content = applyReplacement(content, targetImport, replImport, "import EgressTriggerSource in HomeScreen.kt");

    // RESUME in LifecycleResumeEffect
    const targetResume = `    LifecycleResumeEffect(Unit) {
        viewModel.scheduleMidnightRefresh()
        viewModel.refreshInstalledStatus(context)
        viewModel.refresh(context, forceRefresh = false)
        viewModel.pruneExpiredMissions()`;
    const replResume = `    LifecycleResumeEffect(Unit) {
        viewModel.scheduleMidnightRefresh()
        viewModel.refreshInstalledStatus(context)
        viewModel.refresh(context, forceRefresh = false, triggerSource = EgressTriggerSource.RESUME)
        viewModel.pruneExpiredMissions()`;
    content = applyReplacement(content, targetResume, replResume, "LifecycleResumeEffect in HomeScreen.kt");

    // PULL_REFRESH in DiscoverTab
    const targetDiscoverPull = `                    error = uiState.error,
                    onRefresh = { viewModel.refresh(context, forceRefresh = true) },`;
    const replDiscoverPull = `                    error = uiState.error,
                    onRefresh = { viewModel.refresh(context, forceRefresh = true, triggerSource = EgressTriggerSource.PULL_REFRESH) },`;
    content = applyReplacement(content, targetDiscoverPull, replDiscoverPull, "DiscoverTab onRefresh in HomeScreen.kt");
    content = applyReplacement(content,
        `                    onRefresh = { viewModel.refresh(context, forceRefresh = true) },
                    onCampaignClick = onCampaignClick,`,
        `                    onRefresh = { viewModel.refresh(context, forceRefresh = true, triggerSource = EgressTriggerSource.PULL_REFRESH) },
                    onCampaignClick = onCampaignClick,`,
        "joined tab onRefresh in HomeScreen.kt");

    // PULL_REFRESH in QuestsScreen
    const targetQuestsPull = `                        onRefresh = { viewModel.refresh(context, forceRefresh = true) },
                        onClaimReward = { type, milestone ->`;
    const replQuestsPull = `                        onRefresh = { viewModel.refresh(context, forceRefresh = true, triggerSource = EgressTriggerSource.PULL_REFRESH) },
                        onClaimReward = { type, milestone ->`;
    content = applyReplacement(content, targetQuestsPull, replQuestsPull, "QuestsScreen onRefresh in HomeScreen.kt");

    modifiedFiles.set(rel, content);
}

// --- 3.5: ProfileViewModel.kt ---
{
    const rel = "app/src/main/java/com/ntt/crosstest/ui/viewmodels/ProfileViewModel.kt";
    let content = fileTexts.get(rel);

    // Add import
    const targetImport = `import com.ntt.crosstest.data.CampaignRepository`;
    const replImport = `import com.ntt.crosstest.data.CampaignRepository\nimport com.ntt.crosstest.data.EgressTriggerSource`;
    content = applyReplacement(content, targetImport, replImport, "import EgressTriggerSource in ProfileViewModel.kt");

    // Update refresh signature and pass triggerSource to repo
    const targetRefreshSig = `    fun refresh(forceRefresh: Boolean = false) {`;
    const replRefreshSig = `    fun refresh(
        forceRefresh: Boolean = false,
        triggerSource: EgressTriggerSource = EgressTriggerSource.UNKNOWN,
    ) {`;
    content = applyReplacement(content, targetRefreshSig, replRefreshSig, "refresh signature in ProfileViewModel.kt");

    const targetRepoCall = `                // Fast path: 1 RPC gộp (get_my_dashboard) phủ profile + logs + slots.
                val dashboard = campaignRepository.getMyDashboard(forceRefresh = forceRefresh)
                val profile = dashboard?.profile ?: campaignRepository.getMyProfile(forceRefresh = forceRefresh)`;
    const replRepoCall = `                // Fast path: 1 RPC gộp (get_my_dashboard) phủ profile + logs + slots.
                val dashboard = campaignRepository.getMyDashboard(forceRefresh = forceRefresh, triggerSource = triggerSource)
                val profile = dashboard?.profile ?: campaignRepository.getMyProfile(forceRefresh = forceRefresh)`;
    content = applyReplacement(content, targetRepoCall, replRepoCall, "repo dashboard call in ProfileViewModel.kt");

    modifiedFiles.set(rel, content);
}

// --- 3.6: ProfileScreen.kt ---
{
    const rel = "app/src/main/java/com/ntt/crosstest/ui/screens/ProfileScreen.kt";
    let content = fileTexts.get(rel);

    // Add import
    const targetImport = `import com.ntt.crosstest.data.SlotInfo`;
    const replImport = `import com.ntt.crosstest.data.SlotInfo\nimport com.ntt.crosstest.data.EgressTriggerSource`;
    content = applyReplacement(content, targetImport, replImport, "import EgressTriggerSource in ProfileScreen.kt");

    // RESUME and TAB_SWITCH
    const targetResumeTab = `    // Quay lại app hoặc mở lại tab Hồ sơ: đồng bộ nền để số dư/slot/danh sách không bị cũ.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh(forceRefresh = false)
        onPauseOrDispose { }
    }
    LaunchedEffect(isActive) {
        if (isActive) viewModel.refresh(forceRefresh = false)
    }`;
    const replResumeTab = `    // Quay lại app hoặc mở lại tab Hồ sơ: đồng bộ nền để số dư/slot/danh sách không bị cũ.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh(forceRefresh = false, triggerSource = EgressTriggerSource.RESUME)
        onPauseOrDispose { }
    }
    LaunchedEffect(isActive) {
        if (isActive) viewModel.refresh(forceRefresh = false, triggerSource = EgressTriggerSource.TAB_SWITCH)
    }`;
    content = applyReplacement(content, targetResumeTab, replResumeTab, "LifecycleResumeEffect and tab switch in ProfileScreen.kt");

    // PULL_REFRESH in PullToRefreshBox
    const targetPull = `    PullToRefreshBox(
        isRefreshing = uiState.isBackgroundSyncing,
        onRefresh = { viewModel.refresh(forceRefresh = true) },
        modifier = modifier.fillMaxSize(),
    ) {`;
    const replPull = `    PullToRefreshBox(
        isRefreshing = uiState.isBackgroundSyncing,
        onRefresh = { viewModel.refresh(forceRefresh = true, triggerSource = EgressTriggerSource.PULL_REFRESH) },
        modifier = modifier.fillMaxSize(),
    ) {`;
    content = applyReplacement(content, targetPull, replPull, "PullToRefreshBox onRefresh in ProfileScreen.kt");

    // MANUAL on retry error card
    const targetRetry = `                if (uiState.profile == null) {
                    ProfileLoadErrorCard(
                        message = uiState.error,
                        onRetry = { viewModel.refresh(forceRefresh = true) },
                    )
                } else {`;
    const replRetry = `                if (uiState.profile == null) {
                    ProfileLoadErrorCard(
                        message = uiState.error,
                        onRetry = { viewModel.refresh(forceRefresh = true, triggerSource = EgressTriggerSource.MANUAL) },
                    )
                } else {`;
    content = applyReplacement(content, targetRetry, replRetry, "ProfileLoadErrorCard onRetry in ProfileScreen.kt");

    modifiedFiles.set(rel, content);
}

// --- 3.7: CampaignDetailViewModel.kt ---
{
    const rel = "app/src/main/java/com/ntt/crosstest/ui/viewmodels/CampaignDetailViewModel.kt";
    let content = fileTexts.get(rel);

    // Add import
    const targetImport = `import com.ntt.crosstest.data.Localizer`;
    const replImport = `import com.ntt.crosstest.data.Localizer\nimport com.ntt.crosstest.data.EgressTriggerSource`;
    content = applyReplacement(content, targetImport, replImport, "import EgressTriggerSource in CampaignDetailViewModel.kt");

    // MUTATION refresh after submit report
    const targetAfterSubmit = `                        submittedPreparedPath = null
                    }
                    refresh()
                }
            }
        }`;
    const replAfterSubmit = `                        submittedPreparedPath = null
                    }
                    refresh(triggerSource = EgressTriggerSource.MUTATION)
                }
            }
        }`;
    content = applyReplacement(content, targetAfterSubmit, replAfterSubmit, "post-submit refresh in CampaignDetailViewModel.kt");

    // COLD_START refresh in init
    const targetInit = `            refresh(forceRefresh = false)
        }
    }

    /** True khi phiên chưa sẵn sàng (đang khôi phục hoặc refresh tạm thời thất bại). */`;
    const replInit = `            refresh(forceRefresh = false, triggerSource = EgressTriggerSource.COLD_START)
        }
    }

    /** True khi phiên chưa sẵn sàng (đang khôi phục hoặc refresh tạm thời thất bại). */`;
    content = applyReplacement(content, targetInit, replInit, "cold start in CampaignDetailViewModel.kt");

    // refresh signature and pass to repo
    const targetRefreshSig = `    fun refresh(forceRefresh: Boolean = false) {`;
    const replRefreshSig = `    fun refresh(
        forceRefresh: Boolean = false,
        triggerSource: EgressTriggerSource = EgressTriggerSource.UNKNOWN,
    ) {`;
    content = applyReplacement(content, targetRefreshSig, replRefreshSig, "refresh signature in CampaignDetailViewModel.kt");

    const targetDetailCall = `                // Fast path: 1 RPC gộp toàn bộ dữ liệu màn chi tiết (migration 020).
                val detail = repository.getCampaignDetailPage(campaignId, forceRefresh = forceRefresh)`;
    const replDetailCall = `                // Fast path: 1 RPC gộp toàn bộ dữ liệu màn chi tiết (migration 020).
                val detail = repository.getCampaignDetailPage(campaignId, forceRefresh = forceRefresh, triggerSource = triggerSource)`;
    content = applyReplacement(content, targetDetailCall, replDetailCall, "repo getCampaignDetailPage in CampaignDetailViewModel.kt");

    modifiedFiles.set(rel, content);
}

// --- 3.8: CampaignDetailScreen.kt ---
{
    const rel = "app/src/main/java/com/ntt/crosstest/ui/screens/CampaignDetailScreen.kt";
    let content = fileTexts.get(rel);

    // Add import
    const targetImport = `import com.ntt.crosstest.data.TesterReport`;
    const replImport = `import com.ntt.crosstest.data.TesterReport\nimport com.ntt.crosstest.data.EgressTriggerSource`;
    content = applyReplacement(content, targetImport, replImport, "import EgressTriggerSource in CampaignDetailScreen.kt");

    // RESUME in LifecycleResumeEffect
    const targetResume = `            viewModel.checkInstalledAppVersion(context)
        }
        viewModel.refresh()
        onPauseOrDispose { }
    }`;
    const replResume = `            viewModel.checkInstalledAppVersion(context)
        }
        viewModel.refresh(forceRefresh = false, triggerSource = EgressTriggerSource.RESUME)
        onPauseOrDispose { }
    }`;
    content = applyReplacement(content, targetResume, replResume, "LifecycleResumeEffect in CampaignDetailScreen.kt");

    // MANUAL on refresh IconButton in TopAppBar
    const targetIconRefresh = `                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.campaign_refresh_cd))
                    }`;
    const replIconRefresh = `                    IconButton(onClick = { viewModel.refresh(triggerSource = EgressTriggerSource.MANUAL) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.campaign_refresh_cd))
                    }`;
    content = applyReplacement(content, targetIconRefresh, replIconRefresh, "IconButton refresh in CampaignDetailScreen.kt");

    modifiedFiles.set(rel, content);
}

// ----------------------------------------------------------------------------
// Step 4: Write changes after all replacements have validated in memory
// ----------------------------------------------------------------------------
console.log('[4/4] Writing changes to the 8 allowed files...');
if (process.argv.includes('--dry-run')) {
    console.log('All replacements validated; dry run leaves source files unchanged.');
    process.exit(0);
}
for (const relPath of ALLOWED_TARGETS) {
    const absPath = path.resolve(WORKSPACE_ROOT, relPath);
    assertSafePath(absPath);
    const newContent = modifiedFiles.get(relPath);
    if (!newContent) {
        throw new Error(`Missing modified content for ${relPath}`);
    }
    fs.writeFileSync(absPath, newContent, 'utf8');
    const newSha = computeSha256(Buffer.from(newContent, 'utf8'));
    console.log(`  ✓ Updated ${relPath} (new SHA256: ${newSha.slice(0, 12)}...)`);
}

console.log('\\nSuccessfully updated all 8 files safely.');
