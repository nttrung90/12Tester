/**
 * Supabase Authority Adapter cho Media Access Gateway (E04).
 *
 * Chức năng:
 * - Giải mã và xác thực Bearer JWT thông qua Supabase Auth API (`auth.getUser`).
 * - Xác thực chữ ký HMAC và kiểm tra quyền tác giả / owner / admin qua RPC `verify_media_access_grant_internal`.
 */

export class SupabaseAuthorityAdapter {
    constructor({ supabaseClient, logger = console }) {
        if (!supabaseClient) {
            throw new Error('SUPABASE_CLIENT_REQUIRED: SupabaseAuthorityAdapter cần client Supabase hợp lệ.');
        }
        this.supabase = supabaseClient;
        this.logger = logger;
    }

    /**
     * Xác thực Bearer JWT token và trả về thông tin user.
     */
    async resolveUserFromToken(token) {
        if (!token || typeof token !== 'string') {
            return null;
        }

        const { data: { user }, error } = await this.supabase.auth.getUser(token);
        if (error || !user) {
            this.logger.warn?.(`SupabaseAuthorityAdapter: Auth failed: ${error?.message}`);
            return null;
        }
        return { id: user.id, email: user.email };
    }

    /**
     * Xác thực grant token với Supabase Database qua hàm RPC nội bộ.
     */
    async verifyGrant({
        reportId,
        userId,
        variant = 'full',
        expiresAtEpoch,
        grantToken,
    }) {
        try {
            const { data: isValid, error } = await this.supabase.rpc('verify_media_access_grant_internal', {
                p_report_id: reportId,
                p_user_id: userId,
                p_grant_token: grantToken,
                p_variant: variant,
                p_expires_at_epoch: expiresAtEpoch,
            });

            if (error) {
                this.logger.error?.(`SupabaseAuthorityAdapter verifyGrant error: ${error.message}`);
                return { isValid: false, reason: 'RPC_ERROR', message: error.message };
            }

            if (!isValid) {
                // Kiểm tra xem có phải do hết hạn media hoặc quá hạn retention 30 ngày hay không
                const { data: report } = await this.supabase
                    .from('tester_reports')
                    .select('media_expired, created_at')
                    .eq('id', reportId)
                    .maybeSingle();

                const isRetentionExpired = report?.created_at &&
                    (Date.now() - new Date(report.created_at).getTime()) >= (30 * 24 * 60 * 60 * 1000);

                if (report?.media_expired === true || isRetentionExpired) {
                    return { isValid: false, reason: 'MEDIA_EXPIRED', message: 'Báo cáo đã hết hạn media hoặc quá hạn lưu trữ SLA 30 ngày.' };
                }
                return { isValid: false, reason: 'FORBIDDEN', message: 'Chữ ký không hợp lệ hoặc không có quyền.' };
            }

            // Lấy storage path của báo cáo để storage adapter tải
            const { data: report } = await this.supabase
                .from('tester_reports')
                .select('screenshot_url, thumbnail_url')
                .eq('id', reportId)
                .single();

            const storageUrl = variant === 'thumb'
                ? (report?.thumbnail_url || report?.screenshot_url)
                : report?.screenshot_url;

            return {
                isValid: true,
                storagePath: storageUrl,
            };
        } catch (err) {
            this.logger.error?.(`SupabaseAuthorityAdapter exception: ${err.message}`);
            return { isValid: false, reason: 'INTERNAL_ERROR', message: err.message };
        }
    }
}
