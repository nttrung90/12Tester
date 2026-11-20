package com.ntt.crosstest.data

import android.util.Log
import com.ntt.crosstest.util.MediaAccessGrantProvider
import com.ntt.crosstest.util.MediaAccessGrantResult
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class GetReportMediaAccessRpcParams(
    @SerialName("p_report_id") val reportId: String
)

/**
 * Concrete implementation của MediaAccessGrantProvider gọi database RPC
 * `get_report_media_access(uuid)` của Supabase (E04).
 *
 * Giải mã đúng snake_case định dạng JSON từ backend PostgreSQL / PostgREST.
 */
class SupabaseMediaAccessGrantProvider(
    private val supabase: SupabaseClient
) : MediaAccessGrantProvider {

    override suspend fun getMediaAccessGrant(reportId: String): MediaAccessGrantResult? {
        if (reportId.isBlank()) return null
        return try {
            supabase.postgrest.rpc(
                function = "get_report_media_access",
                parameters = GetReportMediaAccessRpcParams(reportId = reportId)
            ).decodeAs<MediaAccessGrantResult>()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi khi gọi RPC get_report_media_access cho report $reportId: ${e.javaClass.simpleName}")
            null
        }
    }

    override fun getCurrentAuthToken(): String? {
        return try {
            val status = supabase.auth.sessionStatus.value
            if (status is SessionStatus.Authenticated) {
                status.session.accessToken
            } else {
                supabase.auth.currentAccessTokenOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "SupabaseMediaGrant"
    }
}
