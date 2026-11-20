#!/usr/bin/env node
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

// No guessed quota. Configure the actual organization allowance in bytes.
export const DEFAULT_PACKAGE_CAPACITY_BYTES = null;
const MAX_BYTES = 92233720368547758n;
export function integerBytes(value, positive = false) {
    if (typeof value === 'number' && !Number.isSafeInteger(value)) throw Error('UNSAFE_BYTE_NUMBER');
    if (!/^\d+$/.test(String(value ?? ''))) throw Error('INVALID_BYTE_COUNT');
    const n = BigInt(value);
    if (n < (positive ? 1n : 0n) || n > MAX_BYTES) throw Error('BYTE_COUNT_OUT_OF_RANGE');
    return n;
}
function envAlias(primary, alias) {
    const a = process.env[primary], b = process.env[alias];
    if (a && b && a !== b) throw Error('CONFLICTING_ENV: ' + primary + '/' + alias);
    return a || b;
}
async function apiJson(path, token, fetchFn, init = {}) {
    const response = await fetchFn('https://api.supabase.com' + path, {
        ...init, redirect:'error', signal:AbortSignal.timeout(20000),
        headers:{ Authorization:'Bearer ' + token, 'Content-Type':'application/json' },
    });
    if (!response.ok) throw Error('MANAGEMENT_API_HTTP_' + response.status);
    return response.json();
}
export async function fetchOrganizationProjects({ organizationSlug, managementToken, fetchFn = fetch }) {
    if (!/^[\w-]+$/.test(organizationSlug || '') || !managementToken) throw Error('ORG_SCOPE_AND_TOKEN_REQUIRED');
    const refs = [], seen = new Set();
    let expected;
    for (let offset = 0; offset < 10000;) {
        const body = await apiJson('/v1/organizations/' + encodeURIComponent(organizationSlug) +
            '/projects?limit=100&offset=' + offset, managementToken, fetchFn);
        const page = body?.pagination;
        if (!Array.isArray(body?.projects) || !Number.isInteger(page?.count) || page.count < 0 ||
            !Number.isInteger(page.limit) || page.limit < 1 || page.limit > 100 || page.offset !== offset) {
            throw Error('INVALID_PROJECT_INVENTORY_RESPONSE');
        }
        if (expected !== undefined && expected !== page.count) throw Error('ORG_INVENTORY_CHANGED_DURING_SCAN');
        expected = page.count;
        for (const p of body.projects) {
            if (!/^[a-z]{20}$/.test(p.ref || '') || seen.has(p.ref)) throw Error('INVALID_OR_DUPLICATE_PROJECT_REF');
            seen.add(p.ref); refs.push(p.ref);
        }
        offset += body.projects.length;
        if (offset === expected) return refs.sort();
        if (offset > expected || !body.projects.length) throw Error('INCOMPLETE_ORG_INVENTORY');
    }
    throw Error('ORG_INVENTORY_LIMIT');
}

// Bytes and object manifest are read in one MVCC statement. Metadata only;
// never mutate the provider-owned storage schema or issue billing-period queries.
export const STORAGE_SNAPSHOT_SQL = [
    'select coalesce(sum(case when metadata->>\'size\' ~ \'^[0-9]+$\'',
    'then (metadata->>\'size\')::numeric end),0)::text as bytes,',
    'count(*) filter (where metadata->>\'size\' is null or metadata->>\'size\' !~ \'^[0-9]+$\')::integer as invalid_objects,',
    'clock_timestamp() as observed_at,',
    'coalesce(jsonb_agg(jsonb_build_object(\'bucket_id\',bucket_id,\'name\',name,',
    '\'created_at\',created_at,\'size_bytes\',metadata->>\'size\'))',
    'filter (where bucket_id=\'report-screenshots\' and name like \'reports/%\'),\'[]\'::jsonb) as objects',
    'from storage.objects',
].join(' ');
export async function fetchProjectStorageBytes({ projectRef, managementToken, fetchFn = fetch }) {
    if (!/^[a-z]{20}$/.test(projectRef || '') || !managementToken) throw Error('PROJECT_REF_AND_TOKEN_REQUIRED');
    const body = await apiJson('/v1/projects/' + projectRef + '/database/query/read-only',
        managementToken, fetchFn, { method:'POST', body:JSON.stringify({ query:STORAGE_SNAPSHOT_SQL }) });
    if (!Array.isArray(body) || body.length !== 1 || Number(body[0].invalid_objects) !== 0) {
        throw Error('INVALID_STORAGE_SNAPSHOT_RESPONSE');
    }
    return { ...body[0], projectRef };
}
function sameSet(a, b) { return JSON.stringify([...a].sort()) === JSON.stringify([...b].sort()); }
export async function reconcileOrganizationStorage({
    supabaseClient,
    totalCapacityBytes,
    projectRefs = (process.env.SUPABASE_ORG_PROJECT_REFS || '').split(',').map(s=>s.trim()).filter(Boolean),
    organizationSlug = process.env.SUPABASE_ORG_SLUG,
    managementToken,
    fetchProjectFn = fetchProjectStorageBytes,
    inventoryFn = fetchOrganizationProjects,
    fetchFn = fetch,
    now = () => Date.now(),
    dryRun = process.env.DRY_RUN === 'true' || process.argv.includes('--dry-run'),
    logger = console,
} = {}) {
    if (!supabaseClient) throw Error('SUPABASE_CLIENT_REQUIRED');
    const summary = { total_capacity_bytes:null, project_count_expected:0, projects_measured:[],
        total_used_bytes:'0', is_metric_valid:false, fail_safe_triggered:false,
        fail_safe_reason:null, dispatch_mode_result:null };
    try {
        const capacity = integerBytes(totalCapacityBytes ?? envAlias('SUPABASE_TOTAL_CAPACITY_BYTES','SUPABASE_ORG_CAPACITY_BYTES'), true);
        summary.total_capacity_bytes = capacity.toString();
        const token = managementToken ?? envAlias('SUPABASE_MANAGEMENT_TOKEN','SUPABASE_MANAGEMENT_API_TOKEN');
        const discovered = await inventoryFn({ organizationSlug, managementToken:token, fetchFn });
        if (!Array.isArray(discovered) || !discovered.length || new Set(discovered).size !== discovered.length) {
            throw Error('EMPTY_OR_INVALID_ORG_INVENTORY');
        }
        if (new Set(projectRefs).size !== projectRefs.length ||
            (projectRefs.length && !sameSet(discovered,projectRefs))) throw Error('ORG_PROJECT_SCOPE_MISMATCH');
        summary.project_count_expected = discovered.length;
        let total = 0n;
        for (const ref of discovered) {
            const metric = await fetchProjectFn({ projectRef:ref, managementToken:token, fetchFn });
            const bytes = integerBytes(metric.bytes);
            const observed = Date.parse(metric.observed_at);
            if (!Number.isFinite(observed) || observed > now()+60000 || now()-observed >= 30*60000 ||
                !Array.isArray(metric.objects)) throw Error('STALE_OR_INCOMPLETE_PROJECT_SNAPSHOT');
            const names = new Set();
            for (const o of metric.objects) {
                if (o.bucket_id !== 'report-screenshots' || typeof o.name !== 'string' ||
                    !o.name.startsWith('reports/') || !Number.isFinite(Date.parse(o.created_at)) ||
                    names.has(o.name)) throw Error('INVALID_OBJECT_MANIFEST');
                names.add(o.name);
                integerBytes(o.size_bytes);
            }
            total += bytes;
            if (total > MAX_BYTES) throw Error('ORG_BYTE_SUM_OUT_OF_RANGE');
            summary.projects_measured.push({ project_ref:ref, bytes:bytes.toString(),
                observed_at:metric.observed_at, objects:metric.objects, status:'SUCCESS' });
        }
        const inventoryAfter = await inventoryFn({ organizationSlug, managementToken:token, fetchFn });
        if (!sameSet(discovered,inventoryAfter)) throw Error('ORG_INVENTORY_CHANGED_DURING_COLLECTION');
        if (summary.projects_measured.some(p=>now()-Date.parse(p.observed_at)>=30*60000)) throw Error('STALE_ORG_SNAPSHOT');
        summary.total_used_bytes = total.toString();
        summary.is_metric_valid = true;
    } catch (error) {
        summary.fail_safe_triggered = true;
        summary.fail_safe_reason = error.message;
        logger.warn?.(JSON.stringify({ event:'ORG_METRICS_FAIL_SAFE', reason:error.message }));
    }
    if (!dryRun) {
        // An invalid observation must not replace the previous good U/B snapshot.
        // 092 preserves these when p_is_valid=false, including null capacity.
        const r = await supabaseClient.rpc('reconcile_storage_organization_usage', {
            p_total_capacity_bytes:summary.total_capacity_bytes,
            p_used_storage_bytes:summary.total_used_bytes,
            p_measured_projects:summary.projects_measured,
            p_is_valid:summary.is_metric_valid,
        });
        if (r.error) throw Error('RECONCILE_RPC_FAILED: ' + r.error.message);
        if (r.data?.success !== true) throw Error('RECONCILE_RPC_REJECTED');
        summary.dispatch_mode_result = r.data.dispatch_mode;
        if (r.data?.is_org_metric_valid === true && summary.is_metric_valid) {
            summary.is_metric_valid = true;
        } else {
            summary.is_metric_valid = false;
            summary.fail_safe_triggered = true;
            summary.fail_safe_reason = summary.fail_safe_reason || 'DATABASE_REJECTED_METRICS';
            logger.warn?.(JSON.stringify({ event:'ORG_METRICS_FAIL_SAFE', reason:'DATABASE_REJECTED_METRICS' }));
        }
    }
    // Do not log manifests (paths/user ids) or tokens in routine scheduler output.
    logger.info?.(JSON.stringify({ event:'ORG_STORAGE_RECONCILE_END', valid:summary.is_metric_valid,
        project_count:summary.project_count_expected, used_bytes:summary.total_used_bytes,
        mode:summary.dispatch_mode_result, dry_run:dryRun }));
    return summary;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        if (!process.env.SUPABASE_URL || !process.env.SUPABASE_SERVICE_ROLE_KEY) throw Error('SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY required');
        const { createClient } = await import('@supabase/supabase-js');
        const client = createClient(process.env.SUPABASE_URL, process.env.SUPABASE_SERVICE_ROLE_KEY,
            { auth:{ persistSession:false, autoRefreshToken:false } });
        const r = await reconcileOrganizationStorage({ supabaseClient:client });
        process.exitCode = (r.is_metric_valid && !r.fail_safe_triggered) ? 0 : 1;
    } catch (e) { console.error(e.message); process.exitCode = 1; }
}
