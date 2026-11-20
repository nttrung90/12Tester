package com.ntt.crosstest.util

import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.NextCampaignCandidate

/**
 * Bộ chọn thuần túy phân nhóm và gợi ý app tiếp theo theo thứ tự ưu tiên 4 nhóm nghiêm ngặt:
 * 1. Chưa đủ chỉ tiêu hôm nay + Đã cài trên máy
 * 2. Chưa đủ chỉ tiêu hôm nay + Chưa cài trên máy
 * 3. Đã đủ/vượt chỉ tiêu hôm nay + Đã cài trên máy
 * 4. Đã đủ/vượt chỉ tiêu hôm nay + Chưa cài trên máy
 *
 * Chỉ chọn nhóm tiếp theo khi tất cả các nhóm trên nó rỗng.
 * Với tài khoản test lần đầu, tiêu chí lượt cài thấp nhất chỉ xét bên trong nhóm đã thắng cuộc.
 * Ứng viên thiếu số liệu hôm nay hoặc có số liệu không hợp lệ (< 0) bị loại hoàn toàn,
 * tuyệt đối không suy đoán số thiếu thành 0 để tránh đẩy nhầm lên nhóm 1/2.
 */
object NextCampaignSelector {

    enum class PriorityGroup {
        GROUP_1_UNDER_TARGET_INSTALLED,
        GROUP_2_UNDER_TARGET_NOT_INSTALLED,
        GROUP_3_REACHED_TARGET_INSTALLED,
        GROUP_4_REACHED_TARGET_NOT_INSTALLED,
        INVALID_OR_UNKNOWN,
    }

    /**
     * Xác định xem một ứng viên đã đạt hoặc vượt chỉ tiêu test hôm nay hay chưa.
     * Công thức: todayTestedCount >= campaign.dailyTarget (chuẩn hóa 12, 15, hoặc 20).
     * Trả về null nếu todayTestedCount bị thiếu (null) hoặc không hợp lệ (< 0).
     */
    fun isTargetMet(candidate: NextCampaignCandidate): Boolean? {
        val count = candidate.todayTestedCount ?: return null
        if (count < 0) return null
        val target = candidate.campaign.dailyTarget
        return count >= target
    }

    /**
     * Phân nhóm ưu tiên của một ứng viên dựa trên trạng thái chỉ tiêu và cài đặt trên máy.
     * Ứng viên có số liệu thiếu hoặc âm bị xếp vào [PriorityGroup.INVALID_OR_UNKNOWN]
     * và không bao giờ được xếp vào nhóm 1 hoặc 2.
     */
    fun classifyGroup(
        candidate: NextCampaignCandidate,
        installedPackages: Set<String>,
    ): PriorityGroup {
        val met = isTargetMet(candidate) ?: return PriorityGroup.INVALID_OR_UNKNOWN
        val isInstalled = candidate.campaign.packageName.isNotBlank() &&
                candidate.campaign.packageName in installedPackages
        return when {
            !met && isInstalled -> PriorityGroup.GROUP_1_UNDER_TARGET_INSTALLED
            !met && !isInstalled -> PriorityGroup.GROUP_2_UNDER_TARGET_NOT_INSTALLED
            met && isInstalled -> PriorityGroup.GROUP_3_REACHED_TARGET_INSTALLED
            else -> PriorityGroup.GROUP_4_REACHED_TARGET_NOT_INSTALLED
        }
    }

    /**
     * Chọn app tiếp theo từ danh sách ứng viên đủ điều kiện.
     *
     * @param candidates Danh sách ứng viên (đã lọc server và loại trừ cục bộ)
     * @param installedPackages Tập các package name đã cài trên thiết bị
     * @param isFirstTest True nếu tester chưa từng test app nào trước đây
     * @param randomPicker Lambda chọn phần tử, mặc định là List.randomOrNull()
     */
    fun selectNextCampaign(
        candidates: List<NextCampaignCandidate>,
        installedPackages: Set<String>,
        isFirstTest: Boolean,
        randomPicker: (List<NextCampaignCandidate>) -> NextCampaignCandidate? = { it.randomOrNull() },
    ): Campaign? {
        if (candidates.isEmpty()) return null

        val grouped = candidates.groupBy { classifyGroup(it, installedPackages) }

        val winnerGroup = grouped[PriorityGroup.GROUP_1_UNDER_TARGET_INSTALLED]?.takeIf { it.isNotEmpty() }
            ?: grouped[PriorityGroup.GROUP_2_UNDER_TARGET_NOT_INSTALLED]?.takeIf { it.isNotEmpty() }
            ?: grouped[PriorityGroup.GROUP_3_REACHED_TARGET_INSTALLED]?.takeIf { it.isNotEmpty() }
            ?: grouped[PriorityGroup.GROUP_4_REACHED_TARGET_NOT_INSTALLED]?.takeIf { it.isNotEmpty() }
            ?: return null

        val shortlist = if (isFirstTest) {
            val minInstalledCount = winnerGroup.minOf { it.installedCount }
            winnerGroup.filter { it.installedCount == minInstalledCount }
        } else {
            winnerGroup
        }

        return randomPicker(shortlist)?.campaign
    }
}
