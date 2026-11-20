package com.ntt.crosstest.util

import com.ntt.crosstest.R
import com.ntt.crosstest.data.TesterReport
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Helper chuẩn hoá việc chọn báo cáo đối chiếu giữa các ngày test hoặc khi nộp lại báo cáo bị từ chối.
 *
 * Tiêu chí cốt lõi:
 * 1. Cùng userId, campaignId và submission_context.
 * 2. Mission: cùng missionId, không đoán mission khi dữ liệu không xác định; app_test không ghép với mission.
 * 3. So sánh ngày: lấy báo cáo gần nhất ở ngày trước ngày báo cáo hiện tại theo `Asia/Ho_Chi_Minh`.
 *    Parse ISO-8601 sang Instant/LocalDate, không dùng chuỗi thô hay take(10).
 * 4. Độc lập thứ tự danh sách đầu vào, tie-break ổn định bằng id khi timestamp trùng nhau.
 * 5. Báo cáo nộp lại khi bị từ chối: mốc đối chiếu là chính ảnh bị từ chối của hôm nay (REJECTED_SAME_DAY),
 *    được gắn nhãn riêng, không coi là "ngày trước".
 * 6. Lịch sử tải một phần (partial history): không tuyên bố "báo cáo đầu tiên" khi chỉ mới tải danh sách thu gọn.
 */
object ReportComparisonSelector {

    val VN_ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
    private val VN_DATE_FORMATTER: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun formatIsoUtcToVnDate(isoTimestamp: String?): String {
        if (isoTimestamp.isNullOrBlank()) return ""
        val date = parseVnDate(isoTimestamp) ?: return ""
        return VN_DATE_FORMATTER.format(date)
    }

    enum class TargetRelation {
        NONE_FOUND,            // Không có ảnh đối chiếu trong dữ liệu
        PREVIOUS_DAY,          // Báo cáo gần nhất ở ngày trước theo giờ VN
        REJECTED_SAME_DAY,     // Ảnh của chính báo cáo bị từ chối hôm nay (đang làm lại)
    }

    data class SelectionResult(
        val targetReport: TesterReport?,
        val relation: TargetRelation,
        val targetDateVn: LocalDate?,
        val currentDateVn: LocalDate?,
        val isHistoryPartial: Boolean = false,
        val noteResId: Int? = null,
    )

    fun parseVnDate(isoTimestamp: String?): LocalDate? {
        if (isoTimestamp.isNullOrBlank()) return null
        return try {
            Instant.parse(isoTimestamp).atZone(VN_ZONE).toLocalDate()
        } catch (_: Exception) {
            try {
                ZonedDateTime.parse(isoTimestamp).withZoneSameInstant(VN_ZONE).toLocalDate()
            } catch (_: Exception) {
                null
            }
        }
    }

    fun parseInstant(isoTimestamp: String?): Instant? {
        if (isoTimestamp.isNullOrBlank()) return null
        return try {
            Instant.parse(isoTimestamp)
        } catch (_: Exception) {
            try {
                ZonedDateTime.parse(isoTimestamp).toInstant()
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Chọn báo cáo đối chiếu phù hợp nhất cho `currentReport` từ tập hợp `availableReports`.
     *
     * @param currentReport Báo cáo đang xem / đang chuẩn bị nộp
     * @param availableReports Danh sách các báo cáo đã tải về bộ nhớ
     * @param isHistoryPartial Cờ đánh dấu danh sách reports chỉ mới tải một phần (ví dụ preview 20 items / trang đầu)
     * @param rejectedReportOverride Nếu người dùng đang làm lại báo cáo bị từ chối hôm nay, truyền báo cáo bị từ chối vào đây
     */
    fun selectComparisonTarget(
        currentReport: TesterReport,
        availableReports: List<TesterReport>,
        isHistoryPartial: Boolean = false,
        rejectedReportOverride: TesterReport? = null,
    ): SelectionResult {
        val curVnDate = parseVnDate(currentReport.createdAt)

        // Trường hợp 1: Có báo cáo bị từ chối hôm nay cần thay thế ảnh.
        // Bắt buộc kiểm tra nghiêm ngặt: cùng user, cùng campaign, cùng context/mission,
        // thực sự là rejected, có screenshotUrl và cùng ngày VN với currentReport.
        val validRejectedOverride = rejectedReportOverride?.takeIf { override ->
            override.isRejected &&
            !override.screenshotUrl.isNullOrBlank() &&
            override.userId == currentReport.userId &&
            override.campaignId == currentReport.campaignId &&
            (if (currentReport.isMissionReport) {
                override.isMissionReport && !override.missionId.isNullOrBlank() && override.missionId == currentReport.missionId
            } else {
                !override.isMissionReport && override.missionId.isNullOrBlank()
            }) &&
            (curVnDate != null && parseVnDate(override.createdAt) == curVnDate)
        }

        if (validRejectedOverride != null) {
            val rejDate = parseVnDate(validRejectedOverride.createdAt)
            return SelectionResult(
                targetReport = validRejectedOverride,
                relation = TargetRelation.REJECTED_SAME_DAY,
                targetDateVn = rejDate,
                currentDateVn = curVnDate ?: rejDate,
                isHistoryPartial = isHistoryPartial,
                noteResId = R.string.report_selector_rejected_same_day,
            )
        }

        val curInstant = parseInstant(currentReport.createdAt)

        // Không thể xác định ngày VN của báo cáo hiện tại (timestamp lỗi/trống).
        // Tuyệt đối không gán nhãn "báo cáo đầu tiên" khi timestamp bị lỗi.
        if (curInstant == null || curVnDate == null) {
            return SelectionResult(
                targetReport = null,
                relation = TargetRelation.NONE_FOUND,
                targetDateVn = null,
                currentDateVn = null,
                isHistoryPartial = isHistoryPartial,
                noteResId = null,
            )
        }

        val currentIsMission = currentReport.isMissionReport
        val currentMissionId = currentReport.missionId

        // Lọc các ứng viên hợp lệ
        val matchingCandidates = availableReports.filter { candidate ->
            // Bỏ qua chính báo cáo hiện tại
            if (!currentReport.id.isNullOrBlank() && candidate.id == currentReport.id) {
                return@filter false
            }
            // Bắt buộc cùng userId và campaignId
            if (candidate.userId != currentReport.userId || candidate.campaignId != currentReport.campaignId) {
                return@filter false
            }
            // Bắt buộc có ảnh
            if (candidate.screenshotUrl.isNullOrBlank()) {
                return@filter false
            }
            // Kiểm tra ngữ cảnh mission vs app_test
            if (currentIsMission) {
                if (!candidate.isMissionReport || candidate.missionId.isNullOrBlank() || candidate.missionId != currentMissionId) {
                    return@filter false
                }
            } else {
                if (candidate.isMissionReport || !candidate.missionId.isNullOrBlank()) {
                    return@filter false
                }
            }

            // Parse ngày VN của ứng viên
            val candVnDate = parseVnDate(candidate.createdAt) ?: return@filter false
            // Bắt buộc ngày trước (strictly before) theo Asia/Ho_Chi_Minh
            candVnDate < curVnDate
        }

        if (matchingCandidates.isEmpty()) {
            return SelectionResult(
                targetReport = null,
                relation = TargetRelation.NONE_FOUND,
                targetDateVn = null,
                currentDateVn = curVnDate,
                isHistoryPartial = isHistoryPartial,
                noteResId = if (isHistoryPartial) R.string.report_selector_partial_history else R.string.report_selector_first_report,
            )
        }

        // Tìm báo cáo gần nhất ở ngày trước: sắp xếp theo Instant giảm dần, tie-break bằng id giảm dần
        val bestMatch = matchingCandidates.maxWithOrNull(
            compareBy<TesterReport> { parseInstant(it.createdAt) ?: Instant.MIN }
                .thenBy { it.id.orEmpty() }
        )

        val bestDate = parseVnDate(bestMatch?.createdAt)
        return SelectionResult(
            targetReport = bestMatch,
            relation = TargetRelation.PREVIOUS_DAY,
            targetDateVn = bestDate,
            currentDateVn = curVnDate,
            isHistoryPartial = isHistoryPartial,
            noteResId = null,
        )
    }
}
