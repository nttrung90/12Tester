package com.ntt.crosstest.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Thể hiện ý định tạo đợt thưởng thêm cho tester được lưu trữ bền vững.
 * Lưu giữ cả UUID của client_request_id và nguyên vẹn payload ban đầu (reward_credits, max_recipients)
 * để đảm bảo an toàn idempotent khi timeout, quay màn hình, thoát ứng dụng hoặc process death.
 */
@Serializable
data class TesterBonusIntent(
    val clientRequestId: String,
    val campaignId: String,
    val userId: String,
    val rewardCredits: Int,
    val maxRecipients: Int,
    val createdAtMs: Long = System.currentTimeMillis(),
)

/**
 * Trạng thái đọc ý định tạo đợt thưởng từ bộ nhớ bền vững.
 */
sealed interface TesterBonusStoreState {
    object None : TesterBonusStoreState
    data class Valid(val intent: TesterBonusIntent) : TesterBonusStoreState
    data class Corrupted(val raw: String, val error: String) : TesterBonusStoreState
}

class TesterBonusStoreCorruptedStateException(
    val raw: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Quản lý lưu trữ bền vững ý định tạo đợt thưởng thêm, phân tách chặt chẽ theo userId và campaignId.
 * Tuân thủ nghiêm ngặt nguyên tắc bảo toàn tài chính:
 * 1. Không tự tạo UUID mới khi dữ liệu chưa được giải quyết hoặc khi xảy ra lỗi parse JSON.
 * 2. Chỉ xóa khi có xác nhận rõ ràng từ server và expected UUID khớp với bản ghi đang lưu.
 * 3. Không xóa intent dở dang khi đăng xuất hay xóa cache thông thường.
 * 4. Bản ghi hỏng được cách ly bảo toàn trên đĩa, không cho phép xóa tự do để tránh tạo trùng UUID.
 */
object CampaignTesterBonusStore {
    private const val PREFS_NAME = "crosstest_campaign_tester_bonus_requests"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun buildKey(userId: String, campaignId: String): String = "${userId}_$campaignId"

    @Synchronized
    fun peekState(context: Context, userId: String, campaignId: String): TesterBonusStoreState {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = buildKey(userId, campaignId)
        val raw = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: return TesterBonusStoreState.None
        return try {
            val intent = json.decodeFromString<TesterBonusIntent>(raw)
            if (intent.clientRequestId.isNotBlank() && intent.campaignId == campaignId && intent.userId == userId) {
                TesterBonusStoreState.Valid(intent)
            } else {
                TesterBonusStoreState.Corrupted(raw, "Invalid payload fields or user mismatch")
            }
        } catch (e: Exception) {
            TesterBonusStoreState.Corrupted(raw, e.message ?: "JSON decode failure")
        }
    }

    @Synchronized
    fun getOrCreatePendingIntent(
        context: Context,
        userId: String,
        campaignId: String,
        rewardCredits: Int,
        maxRecipients: Int,
    ): TesterBonusIntent {
        when (val state = peekState(context, userId, campaignId)) {
            is TesterBonusStoreState.Valid -> return state.intent
            is TesterBonusStoreState.Corrupted -> {
                throw TesterBonusStoreCorruptedStateException(
                    raw = state.raw,
                    message = "Lưu trữ ý định thưởng thêm bị hỏng (${state.error}). Cần xử lý an toàn để tránh giữ tiền lần hai.",
                )
            }
            is TesterBonusStoreState.None -> {
                val newIntent = TesterBonusIntent(
                    clientRequestId = UUID.randomUUID().toString(),
                    campaignId = campaignId,
                    userId = userId,
                    rewardCredits = rewardCredits,
                    maxRecipients = maxRecipients,
                    createdAtMs = System.currentTimeMillis(),
                )
                val key = buildKey(userId, campaignId)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val committed = prefs.edit().putString(key, json.encodeToString(newIntent)).commit()
                if (!committed) {
                    throw IllegalStateException("Failed to write pending bonus intent to disk")
                }
                return newIntent
            }
        }
    }

    @Synchronized
    fun peekPendingIntent(context: Context, userId: String, campaignId: String): TesterBonusIntent? {
        return when (val state = peekState(context, userId, campaignId)) {
            is TesterBonusStoreState.Valid -> state.intent
            is TesterBonusStoreState.Corrupted -> {
                throw TesterBonusStoreCorruptedStateException(
                    raw = state.raw,
                    message = "Lưu trữ ý định thưởng thêm bị hỏng (${state.error}). Cần xử lý an toàn để tránh giữ tiền lần hai.",
                )
            }
            is TesterBonusStoreState.None -> null
        }
    }

    /**
     * Chỉ xóa intent khi khớp chính xác expectedClientRequestId.
     * Ngăn chặn race condition khi phản hồi cũ/trễ xóa nhầm intent mới hơn.
     */
    @Synchronized
    fun clearPendingIntentIfMatching(
        context: Context,
        userId: String,
        campaignId: String,
        expectedClientRequestId: String,
    ): Boolean {
        val state = peekState(context, userId, campaignId)
        if (state is TesterBonusStoreState.Valid && state.intent.clientRequestId == expectedClientRequestId) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val key = buildKey(userId, campaignId)
            return prefs.edit().remove(key).commit()
        }
        return false
    }
}
