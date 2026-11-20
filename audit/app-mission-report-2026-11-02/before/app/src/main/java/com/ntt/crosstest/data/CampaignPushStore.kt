package com.ntt.crosstest.data

import android.content.Context
import java.util.UUID

/**
 * Lưu trữ bền vững client_request_id cho các giao dịch Đẩy app.
 *
 * Đảm bảo:
 * - Khi gặp timeout, lỗi mạng chập chờn hoặc process death, lượt thử lại tiếp theo
 *   gửi lại chính xác cùng request_id để backend replay receipt mà không trừ tiền lần hai.
 * - Sử dụng commit() đồng bộ xuống đĩa trước khi gửi request để đảm bảo độ bền (durability).
 * - Phân tách theo userId và campaignId: không dùng chung hay nhầm lẫn giữa các tài khoản/chiến dịch.
 * - Chỉ xóa khi backend đã trả về kết quả (thành công hoặc replayed).
 */
object CampaignPushStore {
    private const val PREFS_NAME = "crosstest_campaign_push_requests"

    @Synchronized
    fun getOrCreatePendingRequestId(context: Context, userId: String, campaignId: String): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "${userId}_$campaignId"
        val existing = prefs.getString(key, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val newId = UUID.randomUUID().toString()
        val committed = prefs.edit().putString(key, newId).commit()
        if (!committed) {
            throw IllegalStateException("Failed to write pending push request ID to disk")
        }
        return newId
    }

    @Synchronized
    fun peekPendingRequestId(context: Context, userId: String, campaignId: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "${userId}_$campaignId"
        return prefs.getString(key, null)?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun clearPendingRequestId(context: Context, userId: String, campaignId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "${userId}_$campaignId"
        prefs.edit().remove(key).commit()
    }

    @Synchronized
    fun clearAllForUser(context: Context, userId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val prefix = "${userId}_"
        for (key in prefs.all.keys) {
            if (key.startsWith(prefix)) {
                editor.remove(key)
            }
        }
        editor.commit()
    }
}
