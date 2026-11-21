package com.ntt.crosstest.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.ntt.crosstest.R

/** Các liên kết nhóm cộng đồng dùng chung giữa Trang chủ và Hồ sơ. */
object CommunityLinks {
    const val ZALO_GROUP_URL = "https://zalo.me/g/cc0vbi7igt77w10yikch"
    const val TELEGRAM_GROUP_URL = "https://t.me/Crosstesters"
    const val WHATSAPP_GROUP_URL = "https://chat.whatsapp.com/DaEC045t8t38A7iBUX5tOZ?s=sh&p=a&mlu=4&ilr=4"
}

/** Mở URL ngoài ứng dụng với cờ NEW_TASK cho LocalContext và báo Toast khi không thể mở. */
fun openExternalLink(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                // LocalContext is a localized wrapper (not an Activity), so the
                // intent needs NEW_TASK just like openStore().
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (_: Exception) {
        Toast.makeText(context, R.string.oad_open_link_failed, Toast.LENGTH_SHORT).show()
    }
}
