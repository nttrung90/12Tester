import fs from 'node:fs';
import path from 'node:path';

// Reviewed, task-scoped repair. No arguments, subprocesses, network, or global settings.
const root = fs.realpathSync('F:/AI/12Tester');
function checked(relative) {
  const resolved = fs.realpathSync(path.join(root, relative));
  if (!resolved.toLowerCase().startsWith(root.toLowerCase() + path.sep)) throw new Error('Path outside project');
  return resolved;
}
function load(relative) {
  const file = checked(relative);
  const raw = fs.readFileSync(file, 'utf8');
  return { file, raw, text: raw.replaceAll('\r\n', '\n'), crlf: raw.includes('\r\n') };
}
function uniqueIndex(text, marker) {
  const index = text.indexOf(marker);
  if (index < 0 || text.indexOf(marker, index + marker.length) >= 0) throw new Error('Missing/ambiguous marker: ' + marker);
  return index;
}
function section(text, start, end) {
  const a = uniqueIndex(text, start);
  const b = uniqueIndex(text, end);
  if (b <= a) throw new Error('Invalid method bounds');
  return text.slice(a, b);
}
function spliceSection(text, start, end, replacement) {
  const a = uniqueIndex(text, start);
  const b = uniqueIndex(text, end);
  if (b <= a) throw new Error('Invalid destination bounds');
  return text.slice(0, a) + replacement + text.slice(b);
}

const repoRelative = 'app/src/main/java/com/ntt/crosstest/data/CampaignRepository.kt';
const old = load('audit/egress-safe-2026-11-10/before/' + repoRelative);
const current = load(repoRelative);
const dashboardStart = '    suspend fun getMyDashboard(';
const dashboardEnd = 'suspend fun getMyDailyTaskStatus(';
const detailStart = '        suspend fun getCampaignDetailPage(';
const detailEnd = '    suspend fun getUserSlots(';

let dashboard = section(old.text, dashboardStart, dashboardEnd);
const dashFetchStart = uniqueIndex(dashboard, '            val result = try {');
const dashFetchEnd = uniqueIndex(dashboard, '            val mappedNotifs = result.notifications.map {');
dashboard = dashboard.slice(0, dashFetchStart) + `            val result = fetchConditionalSync(
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
            ) ?: return@deduped null
` + dashboard.slice(dashFetchEnd);

let detail = section(old.text, detailStart, detailEnd);
const detailFetchStart = uniqueIndex(detail, '            val result = try {');
const detailFetchEnd = uniqueIndex(detail, '            if (gen == sessionGeneration.get() && campMutVer == getMutationVersion("campaigns")');
detail = detail.slice(0, detailFetchStart) + `            val result = fetchConditionalSync(
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
            ) ?: return@deduped null
` + detail.slice(detailFetchEnd);

let next = spliceSection(current.text, dashboardStart, dashboardEnd, dashboard);
next = spliceSection(next, detailStart, detailEnd, detail);
if (/(homeFeedTokens|dashboardTokens|detailTokensCache)/.test(next)) throw new Error('Obsolete token-map reference remains; no write performed');
if (!next.includes('private suspend fun <T : Any> fetchConditionalSync(')) throw new Error('Shared helper missing; no write performed');

const optimization = load('app/src/main/java/com/ntt/crosstest/data/EgressOptimization.kt');
const permissive = 'msg.contains(expectedFunctionName, ignoreCase = true) ||\n                msg.contains("could not find the function", ignoreCase = true)';
const strict = 'msg.contains(expectedFunctionName, ignoreCase = true)';
let nextOptimization = optimization.text;
if (nextOptimization.includes(permissive)) nextOptimization = nextOptimization.replace(permissive, strict);

// Calculate and validate all content before writing either task-owned source file.
for (const [entry, text] of [[current, next], [optimization, nextOptimization]]) {
  fs.writeFileSync(entry.file, entry.crlf ? text.replaceAll('\n', '\r\n') : text, 'utf8');
  console.log('Updated ' + path.relative(root, entry.file));
}
console.log('CORE METHODS REPAIRED; build and behavioral verification still required.');
