export class SupabaseAuthorityAdapter {
    constructor({ supabaseClient, logger=console }) {
        if(!supabaseClient) throw Error('SUPABASE_CLIENT_REQUIRED');
        this.supabase=supabaseClient; this.logger=logger;
    }
    async resolveUserFromToken(token) {
        if(typeof token!=='string' || !token) return null;
        const { data,error }=await this.supabase.auth.getUser(token);
        return error || !data?.user ? null : { id:data.user.id, email:data.user.email };
    }
    async verifyGrant({ reportId, userId, variant='full', expiresAtEpoch, grantToken, attachmentId=null }) {
        try {
            const { data:valid,error }=await this.supabase.rpc('verify_media_access_grant_internal', {
                p_report_id:reportId, p_user_id:userId, p_grant_token:grantToken,
                p_variant:variant, p_expires_at_epoch:expiresAtEpoch, p_attachment_id:attachmentId,
            });
            if(error) return { isValid:false, reason:'RPC_ERROR' };
            let query=this.supabase.from(attachmentId ? 'tester_report_attachments' : 'tester_reports')
                .select('screenshot_url,thumbnail_url,storage_provider,view_expires_at,media_expired,created_at');
            query=attachmentId ? query.eq('report_id',reportId).eq('id',attachmentId) : query.eq('id',reportId);
            const media=await query.maybeSingle();
            if(media.error || !media.data) return { isValid:false, reason:'FORBIDDEN' };
            const row=media.data;
            const provider=row.storage_provider || 'gcs';
            let cutoff=row.view_expires_at ? Date.parse(row.view_expires_at) :
                Date.parse(row.created_at)+(provider==='supabase' ? 216 : 30*24)*3600000;
            if(row.media_expired===true || !Number.isFinite(cutoff) || Date.now()>=cutoff) {
                return { isValid:false, reason:'MEDIA_EXPIRED' };
            }
            if(valid!==true) return { isValid:false, reason:'FORBIDDEN' };
            if(provider==='supabase') {
                const deadline=await this.supabase.rpc('_report_media_deadline',{
                    p_report_id:reportId,p_attachment_id:attachmentId,
                });
                const actual=typeof deadline.data==='string'?Date.parse(deadline.data):NaN;
                if(deadline.error || !Number.isFinite(actual) || Date.now()>=actual) return {isValid:false,reason:'MEDIA_EXPIRED'};
                cutoff=Math.min(cutoff,actual);
            }
            return { isValid:true, provider,
                storagePath:variant==='thumb' ? (row.thumbnail_url || row.screenshot_url) : row.screenshot_url,
                viewExpiresAt:new Date(cutoff).toISOString() };
        } catch {
            this.logger.error?.('Media authority verification failed');
            return { isValid:false, reason:'INTERNAL_ERROR' };
        }
    }
}
