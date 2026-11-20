// Authenticated report bytes only. Never fetch arbitrary URLs or forward service credentials.
const SUPABASE_BUCKET = 'report-screenshots';
function objectPath(value) {
    let path;
    try { path = decodeURIComponent(value); } catch { return null; }
    if (!/^reports\/(?:[a-zA-Z0-9_-]+\/){1,2}[a-zA-Z0-9_.-]+\.(?:jpg|jpeg|png|webp)$/.test(path) ||
        path.includes('%') || path.split('/').some(p=>p==='.' || p==='..')) return null;
    return path;
}
export function parseReportStorageLocation(value, { supabaseUrl, googleBuckets = [], provider, bucket } = {}) {
    if (typeof value !== 'string' || !value || value.length > 4096) return null;
    let parsedProvider, parsedBucket, raw;
    if (value.startsWith('reports/')) {
        parsedProvider=provider; parsedBucket=bucket; raw=value;
    } else if (value.startsWith('supabase://') || value.startsWith('/storage/')) {
        const m=value.startsWith('supabase://')
            ? value.match(/^supabase:\/\/([^/]+)\/(.+)$/)
            : value.match(/^\/storage\/v1\/object\/(?:(?:authenticated|public)\/)?([^/]+)\/([^?#]+)$/);
        if (!m) return null;
        parsedProvider='supabase'; parsedBucket=m[1]; raw=m[2];
    } else {
        let url;
        try { url=new URL(value); } catch { return null; }
        if (url.username || url.password || url.protocol!=='https:') return null;
        let origin=null;
        try { if(supabaseUrl) origin=new URL(supabaseUrl).origin; } catch { return null; }
        const supa=url.pathname.match(/^\/storage\/v1\/object\/(?:(?:authenticated|public)\/)?([^/]+)\/(.+)$/);
        if (origin && url.origin===origin && supa) {
            parsedProvider='supabase'; parsedBucket=supa[1]; raw=supa[2];
        } else if (url.hostname==='storage.googleapis.com' && !url.port) {
            const parts=url.pathname.slice(1).split('/');
            parsedProvider='gcs'; parsedBucket=parts.shift(); raw=parts.join('/');
        } else if (url.hostname==='firebasestorage.googleapis.com' && !url.port) {
            const m=url.pathname.match(/^\/v0\/b\/([^/]+)\/o\/(.+)$/);
            if(!m) return null;
            parsedProvider='gcs'; parsedBucket=m[1]; raw=m[2];
        } else if (googleBuckets.includes(url.hostname) && !url.port) {
            const m=url.pathname.match(/^\/v0\/b\/([^/]+)\/o\/(.+)$/);
            if(m) { parsedBucket=m[1]; raw=m[2]; }
            else if(url.pathname.startsWith('/o/')) { parsedBucket=url.hostname; raw=url.pathname.slice(3); }
            else return null;
            if(parsedBucket!==url.hostname) return null;
            parsedProvider='gcs';
        } else return null;
    }
    const path=objectPath(raw || '');
    if(!path || (provider && provider!==parsedProvider) || (bucket && bucket!==parsedBucket)) return null;
    if(parsedProvider==='supabase' && parsedBucket===SUPABASE_BUCKET && supabaseUrl) {
        return { provider:parsedProvider, bucket:parsedBucket, path };
    }
    if(parsedProvider==='gcs' && googleBuckets.includes(parsedBucket)) {
        return { provider:parsedProvider, bucket:parsedBucket, path };
    }
    return null;
}
export class FirebaseStorageAdapter {
    constructor({ bucketName, storageClient, allowedBuckets = [bucketName], logger = console }) {
        this.bucketName=bucketName; this.storageClient=storageClient;
        this.allowedBuckets=[...new Set(allowedBuckets.filter(Boolean))]; this.logger=logger;
    }
    async getObject(params) {
        const location=parseReportStorageLocation(params.storagePath, {
            provider:'gcs', bucket:params.bucket, googleBuckets:this.allowedBuckets,
            ...(params.storagePath?.startsWith('reports/') ? { bucket:params.bucket || this.bucketName } : {}),
        });
        if(!location || !this.storageClient) return null;
        const file=this.storageClient.bucket(location.bucket).file(location.path);
        try {
            const [exists]=await file.exists();
            if(!exists) return null;
            const [metadata]=await file.getMetadata();
            const [bytes]=await file.download();
            return { bytes, contentType:metadata.contentType || 'image/jpeg' };
        } catch(e) {
            if(Number(e.code)===404) return null;
            this.logger.error?.('Firebase report download failed');
            throw e;
        }
    }
}
export class SupabaseStorageAdapter {
    constructor({ supabaseClient, supabaseUrl, bucketName=SUPABASE_BUCKET, logger=console }) {
        if(bucketName!==SUPABASE_BUCKET) throw Error('UNEXPECTED_SUPABASE_BUCKET');
        this.supabaseClient=supabaseClient;
        this.supabaseUrl=supabaseUrl || supabaseClient?.supabaseUrl;
        this.bucketName=bucketName; this.logger=logger;
    }
    async getObject(params) {
        const location=parseReportStorageLocation(params.storagePath, {
            provider:'supabase', bucket:this.bucketName, supabaseUrl:this.supabaseUrl,
        });
        if(!location || !this.supabaseClient) return null;
        const { data,error }=await this.supabaseClient.storage.from(location.bucket).download(location.path);
        if(error) {
            if(Number(error.statusCode ?? error.status)===404) return null;
            this.logger.error?.('Supabase report download failed');
            throw error;
        }
        if(!data) return null;
        return { bytes:Buffer.from(await data.arrayBuffer()), contentType:data.type || 'image/jpeg' };
    }
}
export class DualStorageAdapter {
    constructor({ firebaseAdapter, supabaseAdapter }) {
        this.firebaseAdapter=firebaseAdapter; this.supabaseAdapter=supabaseAdapter;
    }
    async getObject(params) {
        const location=parseReportStorageLocation(params?.storagePath, {
            supabaseUrl:this.supabaseAdapter?.supabaseUrl,
            googleBuckets:this.firebaseAdapter?.allowedBuckets || [],
            provider:params?.provider, bucket:params?.bucket,
        });
        if(!location) return null;
        const adapter=location.provider==='supabase' ? this.supabaseAdapter : this.firebaseAdapter;
        return adapter?.getObject({ ...params, provider:location.provider, bucket:location.bucket, storagePath:location.path }) ?? null;
    }
}
export function createDualStorageAdapter({ supabaseClient, supabaseUrl, storageClient, bucketName,
    allowedGoogleBuckets=[bucketName], logger=console }) {
    return new DualStorageAdapter({
        firebaseAdapter:new FirebaseStorageAdapter({ bucketName, allowedBuckets:allowedGoogleBuckets, storageClient, logger }),
        supabaseAdapter:new SupabaseStorageAdapter({ supabaseClient, supabaseUrl, logger }),
    });
}
