#!/usr/bin/env node
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

export const ALLOWED_BUCKET = 'report-screenshots';
export const TARGET_PREFIX = 'reports/';
export const DEFAULT_TARGET_AGE_HOURS = 216;
export const DEFAULT_SLA_THRESHOLD_HOURS = 240;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const status = e => Number(e?.statusCode ?? e?.status ?? e?.response?.status);
const missing = e => status(e) === 404;
const transient = e => status(e) === 429 || status(e) >= 500 ||
    ['ECONNRESET', 'ETIMEDOUT', 'EAI_AGAIN'].includes(e?.code) || e instanceof TypeError;

export function isReportObjectPath(path, prefix = TARGET_PREFIX) {
    return typeof path === 'string' && path.startsWith(prefix) &&
        /^reports\/[^/]+\/[^/]+\/[^/]+\.(?:jpg|jpeg|png|webp)$/.test(path) &&
        !path.includes('%') && !path.includes('\\') &&
        !path.split('/').some(p => p === '.' || p === '..' || /[\x00-\x1f?#]/.test(p));
}
async function absent(bucket, path) {
    if (typeof bucket.info === 'function') {
        const r = await bucket.info(path);
        if (r.error) { if (missing(r.error)) return true; throw r.error; }
        return false;
    }
    if (typeof bucket.list !== 'function') throw Error('DELETE_NOT_CONFIRMED');
    const split = path.lastIndexOf('/'), parent = path.slice(0, split), name = path.slice(split + 1);
    for (let offset = 0; offset < 10000; offset += 100) {
        const r = await bucket.list(parent, { search: name, offset, limit: 100 });
        if (r.error) { if (missing(r.error)) return true; throw r.error; }
        if (!Array.isArray(r.data)) throw Error('INVALID_STORAGE_LIST_RESPONSE');
        if (r.data.some(item => item.name === name)) return false;
        if (r.data.length < 100) return true;
    }
    throw Error('STORAGE_LIST_LIMIT');
}
async function removeOne(bucket, path, wait, leaseValid) {
    for (let attempt = 0; attempt < 3; attempt++) {
        if (!leaseValid()) throw Error('CLEANUP_LEASE_EXPIRED');
        try {
            const r = await bucket.remove([path]);
            if (r.error) { if (missing(r.error)) return; throw r.error; }
            const confirmed = Array.isArray(r.data) &&
                r.data.some(item => (typeof item === 'string' ? item : item?.name) === path);
            if (confirmed || await absent(bucket, path)) return;
            throw Error('DELETE_NOT_CONFIRMED: ' + path);
        } catch (e) {
            if (!transient(e) || attempt === 2) throw e;
            await wait(200 * 2 ** attempt);
        }
    }
}
export async function cleanupSupabaseStorage({
    supabaseClient, bucketName = process.env.SUPABASE_STORAGE_BUCKET || ALLOWED_BUCKET,
    prefix = TARGET_PREFIX, targetAgeHours = DEFAULT_TARGET_AGE_HOURS,
    slaThresholdHours = DEFAULT_SLA_THRESHOLD_HOURS,
    dryRun = process.env.DRY_RUN === 'true' || process.argv.includes('--dry-run'),
    batchSize = 50, maxBatches = 20, now = new Date(), wait = sleep, logger = console,
} = {}) {
    if (bucketName !== ALLOWED_BUCKET) throw Error('SECURITY ALERT: Bucket "' + bucketName + '" khong nam trong allowlist');
    if (!prefix.startsWith(TARGET_PREFIX) || /(?:\.\.|%|\\)/.test(prefix)) {
        throw Error('SECURITY ALERT: Prefix "' + prefix + '" khong hop le');
    }
    if (!supabaseClient) throw Error('SUPABASE_CLIENT_REQUIRED');
    if (!Number.isInteger(batchSize) || batchSize < 1 || batchSize > 100 ||
        !Number.isInteger(maxBatches) || maxBatches < 1 || maxBatches > 100) throw Error('INVALID_BATCH_LIMIT');
    const started = Date.now(), seen = new Set();
    const clock = () => now.getTime() + Date.now() - started;
    const stats = { bucket: bucketName, prefix, target_age_hours: targetAgeHours,
        sla_threshold_hours: slaThresholdHours, dry_run: dryRun, total_scanned: 0,
        candidates_found: 0, candidates_deleted: 0, candidates_would_delete: 0,
        sla_violations_count: 0, bytes_reclaimed: 0, oldest_object_age_hours: 0,
        errors_count: 0, orphan_candidates_count: 0, batches: 0, backlog_remaining: false };
    const error = (event, e, id) => {
        stats.errors_count++;
        logger.error?.(JSON.stringify({ event, media_id: id, error: e?.message || String(e) }));
    };
    logger.info?.(JSON.stringify({ event: 'SUPABASE_CLEANUP_JOB_START', dryRun, timestamp: now.toISOString() }));
    try {
        for (let batch = 0; batch < maxBatches; batch++) {
            const claim = await supabaseClient.rpc('get_expired_report_media_for_cleanup', {
                p_limit: batchSize, p_lease_seconds: 300, p_dry_run: dryRun,
            });
            if (claim.error) throw claim.error;
            if (!Array.isArray(claim.data)) throw Error('INVALID_CLEANUP_RPC_RESPONSE');
            stats.batches++;
            if (!claim.data.length) break;
            stats.total_scanned += claim.data.length;
            let fresh = 0;
            for (const c of claim.data) {
                if (seen.has(c.id)) continue;
                seen.add(c.id); fresh++; stats.candidates_found++;
                try {
                    if (c.provider !== 'supabase' || c.bucket !== ALLOWED_BUCKET ||
                        !UUID.test(c.id || '') || !isReportObjectPath(c.object_path, prefix) ||
                        (c.thumbnail_object_path && !isReportObjectPath(c.thumbnail_object_path, prefix))) {
                        throw Error('INVALID_CLEANUP_CANDIDATE');
                    }
                    const paths = [...new Set([c.object_path, c.thumbnail_object_path].filter(Boolean))];
                    if (paths.length === 2) {
                        const f = paths[0].split('/'), t = paths[1].split('/');
                        if (f.slice(0,-1).join('/') !== t.slice(0,-1).join('/') ||
                            t.at(-1) !== 'thumb_' + f.at(-1)) throw Error('INVALID_THUMBNAIL_PAIR');
                    }
                    const age = c.server_created_at ? (clock() - Date.parse(c.server_created_at)) / 3600000 : 0;
                    stats.oldest_object_age_hours = Math.max(stats.oldest_object_age_hours, age || 0);
                    if (age >= DEFAULT_SLA_THRESHOLD_HOURS ||
                        (c.delete_deadline_at && clock() >= Date.parse(c.delete_deadline_at))) {
                        stats.sla_violations_count++;
                        logger.warn?.(JSON.stringify({ event: 'SUPABASE_SLA_RETENTION_VIOLATION', media_id:c.id, age_hours:age }));
                    }
                    if (!c.report_id) stats.orphan_candidates_count++;
                    if (dryRun) { stats.candidates_would_delete++; continue; }
                    const until = Date.parse(c.lease_expires_at);
                    const valid = () => UUID.test(c.lease_token || '') && Number.isFinite(until) && clock() < until - 5000;
                    if (!valid()) throw Error('INVALID_OR_EXPIRED_CLEANUP_LEASE');
                    const bucket = supabaseClient.storage.from(ALLOWED_BUCKET);
                    for (const path of paths) await removeOne(bucket, path, wait, valid);
                    if (!valid()) throw Error('CLEANUP_LEASE_EXPIRED_BEFORE_ACK');
                    const ack = await supabaseClient.rpc('record_report_media_deleted', {
                        p_media_id:c.id, p_lease_token:c.lease_token, p_deleted_paths:paths,
                    });
                    if (ack.error) throw ack.error;
                    if (ack.data?.success !== true) throw Error('DELETE_ACK_REJECTED');
                    stats.candidates_deleted++;
                    const bytes = Number(ack.data.bytes_freed);
                    if (Number.isSafeInteger(bytes) && bytes >= 0) stats.bytes_reclaimed += bytes;
                    logger.info?.(JSON.stringify({ event:'MEDIA_DELETED_SUCCESS', media_id:c.id }));
                } catch (e) { error('SUPABASE_MEDIA_CLEANUP_FAILED', e, c.id); }
            }
            if (dryRun || claim.data.length < batchSize) break;
            if (!fresh || batch === maxBatches - 1) { stats.backlog_remaining = true; break; }
        }
    } catch (e) { error('SUPABASE_CLEANUP_CRITICAL_ERROR', e); }
    logger.info?.(JSON.stringify({ event:'SUPABASE_CLEANUP_JOB_END', stats }));
    return stats;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
    try {
        if (!process.env.SUPABASE_URL || !process.env.SUPABASE_SERVICE_ROLE_KEY) throw Error('SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY required');
        const { createClient } = await import('@supabase/supabase-js');
        const client = createClient(process.env.SUPABASE_URL, process.env.SUPABASE_SERVICE_ROLE_KEY,
            { auth: { persistSession:false, autoRefreshToken:false } });
        const stats = await cleanupSupabaseStorage({ supabaseClient:client });
        process.exitCode = stats.errors_count || stats.sla_violations_count || stats.backlog_remaining ? 1 : 0;
    } catch (e) { console.error(e.message); process.exitCode = 1; }
}
