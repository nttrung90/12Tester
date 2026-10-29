package com.ntt.crosstest.ui.viewmodels

import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.util.VnTime
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Thuật toán xáo trộn ngẫu nhiên xác định theo ngày cho các danh sách app trên Trang chủ.
 *
 * Quy tắc:
 * 1. Khóa theo ngày: SHA-256 UTF-8("home-order-v1|YYYY-MM-DD|campaignUUID").
 *    - Ngày theo Asia/Ho_Chi_Minh (YYYY-MM-DD).
 *    - UUID chuẩn hóa lowercase, trim.
 *    - Digest 64 ký tự hex lowercase.
 *    - Tính khóa 1 lần cho mỗi app trong mỗi lần sort, không tính trong comparator.
 *
 * 2. Tab Đang mở (Open):
 *    - isTargetReached ASC (chưa đủ lượt test hôm nay trước -> todayTestedCount < dailyTarget).
 *    - isBoosted DESC (Boost trước trong cùng nhóm).
 *    - tipCredits DESC (tip cao trước trong cùng nhóm).
 *    - dailyKey ASC (khóa ngẫu nhiên theo ngày khi 3 tiêu chí trên bằng nhau).
 *    - stableId ASC (phá hòa ổn định theo ID).
 *
 * 3. Tab Tạm dừng & Hoàn thành:
 *    - dailyKey ASC.
 *    - stableId ASC.
 *    (Không bổ sung tiêu chí Boost/tip, không trộn giữa các tab).
 */
object DailyHomeOrder {

    private const val HASH_VERSION_PREFIX = "home-order-v1"

    /**
     * Lấy định danh ổn định của campaign.
     * Ưu tiên UUID hợp lệ chuẩn hóa lowercase.
     * Fallback xác định: "${ownerId}_${packageName}_${slotNumber}".
     */
    fun getStableCampaignIdentifier(campaign: Campaign): String {
        val rawId = campaign.id?.trim()?.lowercase()
        if (!rawId.isNullOrEmpty()) {
            return rawId
        }
        val owner = campaign.ownerId?.trim()?.lowercase().orEmpty()
        val pkg = campaign.packageName.trim().lowercase()
        val slot = campaign.slotNumber
        return "${owner}_${pkg}_$slot"
    }

    /**
     * Tính khóa băm SHA-256 xác định theo ngày và định danh campaign.
     * Trả về chuỗi hex 64 ký tự lowercase.
     */
    fun computeDailyOrderKey(date: String, stableId: String): String {
        val input = "$HASH_VERSION_PREFIX|$date|$stableId"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(StandardCharsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    private data class ItemSortWrapper(
        val item: CampaignDisplayItem,
        val dailyKey: String,
        val stableId: String,
        val isPushActiveToday: Boolean = false,
        val pushOrder: Long = 0L
    )

    /**
     * Sắp xếp danh sách app cho tab "Đang mở".
     *
     * Ưu tiên:
     * 1. Nhóm App được đẩy còn hiệu lực hôm nay (isPushActive == true):
     *    - push_order DESC (lượt đẩy thành công sau đứng trên lượt trước)
     *    - stableId ASC (phá hòa ổn định)
     * 2. Nhóm App thông thường (giữ nguyên thuật toán cũ):
     *    - Chưa đủ chỉ tiêu hôm nay trước (item.isTargetReached == false)
     *    - Boost trước (campaign.isBoosted == true)
     *    - Tip cao trước (campaign.tipCredits giảm dần)
     *    - Khóa ngẫu nhiên xác định theo ngày tăng dần
     *    - ID ổn định tăng dần (phá hòa)
     */
    fun sortOpenCampaigns(
        items: List<CampaignDisplayItem>,
        date: String
    ): List<CampaignDisplayItem> {
        if (items.size <= 1) return items
        val effectiveDate = if (date.isBlank()) VnTime.today().toString() else date

        val wrapped = items.map { item ->
            val stableId = getStableCampaignIdentifier(item.campaign)
            val dailyKey = computeDailyOrderKey(effectiveDate, stableId)
            val isPushActive = item.campaign.isPushActive(effectiveDate)
            val pushOrder = item.campaign.pushOrder ?: 0L
            ItemSortWrapper(item, dailyKey, stableId, isPushActive, pushOrder)
        }

        val (pushedItems, normalItems) = wrapped.partition { it.isPushActiveToday }

        val pushedComparator = compareByDescending<ItemSortWrapper> { it.pushOrder }
            .thenBy { it.stableId }

        val normalComparator = compareBy<ItemSortWrapper> { it.item.isTargetReached }
            .thenByDescending { it.item.campaign.isBoosted }
            .thenByDescending { it.item.campaign.tipCredits }
            .thenBy { it.dailyKey }
            .thenBy { it.stableId }

        val sortedPushed = pushedItems.sortedWith(pushedComparator)
        val sortedNormal = normalItems.sortedWith(normalComparator)

        return (sortedPushed + sortedNormal).map { it.item }
    }

    fun sortOpenCampaigns(
        items: List<CampaignDisplayItem>,
        date: LocalDate = VnTime.today()
    ): List<CampaignDisplayItem> = sortOpenCampaigns(items, date.toString())

    /**
     * Sắp xếp danh sách app cho tab "Tạm dừng" và "Hoàn thành".
     *
     * Ưu tiên:
     * 1. Khóa ngẫu nhiên xác định theo ngày tăng dần
     * 2. ID ổn định tăng dần (phá hòa)
     */
    fun sortOtherCampaigns(
        items: List<CampaignDisplayItem>,
        date: String
    ): List<CampaignDisplayItem> {
        if (items.size <= 1) return items
        val effectiveDate = if (date.isBlank()) VnTime.today().toString() else date

        val wrapped = items.map { item ->
            val stableId = getStableCampaignIdentifier(item.campaign)
            val dailyKey = computeDailyOrderKey(effectiveDate, stableId)
            ItemSortWrapper(item, dailyKey, stableId)
        }

        val comparator = compareBy<ItemSortWrapper> { it.dailyKey }
            .thenBy { it.stableId }

        return wrapped.sortedWith(comparator).map { it.item }
    }

    fun sortOtherCampaigns(
        items: List<CampaignDisplayItem>,
        date: LocalDate = VnTime.today()
    ): List<CampaignDisplayItem> = sortOtherCampaigns(items, date.toString())
}
