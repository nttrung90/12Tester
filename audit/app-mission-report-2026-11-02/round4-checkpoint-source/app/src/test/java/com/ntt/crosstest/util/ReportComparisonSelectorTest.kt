package com.ntt.crosstest.util

import com.ntt.crosstest.data.TesterReport
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReportComparisonSelectorTest {

    private val user1 = "user-1111"
    private val user2 = "user-2222"
    private val camp1 = "camp-aaaa"
    private val camp2 = "camp-bbbb"
    private val mission1 = "mission-xxxx"
    private val mission2 = "mission-yyyy"

    @Test
    fun selectsMostRecentPreviousDayReportInSameContext() {
        // Today in VN is 2026-10-30T10:00:00+07:00 -> 2026-10-30T03:00:00Z
        val currentReport = TesterReport(
            id = "rep-today",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-30T03:00:00Z",
            screenshotUrl = "https://example.com/today.jpg",
        )

        // Previous day: 2026-10-29
        val repYesterdayMorning = TesterReport(
            id = "rep-yest-morning",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-29T02:00:00Z",
            screenshotUrl = "https://example.com/yest1.jpg",
        )
        val repYesterdayEvening = TesterReport(
            id = "rep-yest-evening",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-29T14:00:00Z",
            screenshotUrl = "https://example.com/yest2.jpg",
        )
        // Two days ago: 2026-10-28
        val repTwoDaysAgo = TesterReport(
            id = "rep-2days-ago",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-28T10:00:00Z",
            screenshotUrl = "https://example.com/2days.jpg",
        )

        val reports = listOf(repTwoDaysAgo, repYesterdayMorning, repYesterdayEvening)
        val result = ReportComparisonSelector.selectComparisonTarget(
            currentReport = currentReport,
            availableReports = reports,
        )

        assertEquals(ReportComparisonSelector.TargetRelation.PREVIOUS_DAY, result.relation)
        assertEquals("rep-yest-evening", result.targetReport?.id)
        assertEquals(LocalDate.of(2026, 10, 29), result.targetDateVn)
        assertEquals(LocalDate.of(2026, 10, 30), result.currentDateVn)
    }

    @Test
    fun ignoresOtherUsersAndOtherCampaigns() {
        val currentReport = TesterReport(
            id = "rep-current",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-30T03:00:00Z",
            screenshotUrl = "https://example.com/current.jpg",
        )

        val otherUserReport = TesterReport(
            id = "rep-other-user",
            userId = user2,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-29T03:00:00Z",
            screenshotUrl = "https://example.com/other_user.jpg",
        )

        val otherCampReport = TesterReport(
            id = "rep-other-camp",
            userId = user1,
            campaignId = camp2,
            submissionContext = "app_test",
            createdAt = "2026-10-29T03:00:00Z",
            screenshotUrl = "https://example.com/other_camp.jpg",
        )

        val result = ReportComparisonSelector.selectComparisonTarget(
            currentReport = currentReport,
            availableReports = listOf(otherUserReport, otherCampReport),
        )

        assertEquals(ReportComparisonSelector.TargetRelation.NONE_FOUND, result.relation)
        assertNull(result.targetReport)
    }

    @Test
    fun separatesMissionFromAppTest() {
        val currentMissionReport = TesterReport(
            id = "rep-mission-today",
            userId = user1,
            campaignId = camp1,
            submissionContext = "mission",
            missionId = mission1,
            createdAt = "2026-10-30T03:00:00Z",
            screenshotUrl = "https://example.com/m_today.jpg",
        )

        val yesterdayAppTestReport = TesterReport(
            id = "rep-apptest-yest",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-29T03:00:00Z",
            screenshotUrl = "https://example.com/apptest_yest.jpg",
        )

        val yesterdayDifferentMissionReport = TesterReport(
            id = "rep-mission2-yest",
            userId = user1,
            campaignId = camp1,
            submissionContext = "mission",
            missionId = mission2,
            createdAt = "2026-10-29T03:00:00Z",
            screenshotUrl = "https://example.com/m2_yest.jpg",
        )

        val yesterdaySameMissionReport = TesterReport(
            id = "rep-mission1-yest",
            userId = user1,
            campaignId = camp1,
            submissionContext = "mission",
            missionId = mission1,
            createdAt = "2026-10-29T03:00:00Z",
            screenshotUrl = "https://example.com/m1_yest.jpg",
        )

        val result = ReportComparisonSelector.selectComparisonTarget(
            currentReport = currentMissionReport,
            availableReports = listOf(yesterdayAppTestReport, yesterdayDifferentMissionReport, yesterdaySameMissionReport),
        )

        assertEquals(ReportComparisonSelector.TargetRelation.PREVIOUS_DAY, result.relation)
        assertEquals("rep-mission1-yest", result.targetReport?.id)
    }

    @Test
    fun handlesVnTimezoneCorrectlyAcrossUtcDayBoundaries() {
        // In UTC: 2026-10-29T18:00:00Z is 2026-10-29 UTC.
        // BUT in Asia/Ho_Chi_Minh (+7): 18:00 + 7 hours = 2026-10-30T01:00:00 VN!
        val reportEarlyMorningVn = TesterReport(
            id = "rep-vn-same-day",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-29T18:00:00Z", // 01:00 on Oct 30 VN
            screenshotUrl = "https://example.com/same_day_vn.jpg",
        )

        val reportNoonVn = TesterReport(
            id = "rep-vn-current",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-30T05:00:00Z", // 12:00 on Oct 30 VN
            screenshotUrl = "https://example.com/noon.jpg",
        )

        // Previous day VN: 2026-10-29T16:00:00Z is 23:00 on Oct 29 VN
        val reportPreviousDayVn = TesterReport(
            id = "rep-vn-prev-day",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-29T16:00:00Z", // 23:00 on Oct 29 VN
            screenshotUrl = "https://example.com/prev_day_vn.jpg",
        )

        val result = ReportComparisonSelector.selectComparisonTarget(
            currentReport = reportNoonVn,
            availableReports = listOf(reportEarlyMorningVn, reportPreviousDayVn),
        )

        // Must NOT select reportEarlyMorningVn because they are on the SAME day in Vietnam!
        assertEquals(ReportComparisonSelector.TargetRelation.PREVIOUS_DAY, result.relation)
        assertEquals("rep-vn-prev-day", result.targetReport?.id)
    }

    @Test
    fun handlesResubmissionOfRejectedReportToday() {
        val todayRejectedReport = TesterReport(
            id = "rep-rejected-today",
            userId = user1,
            campaignId = camp1,
            status = "rejected",
            createdAt = "2026-10-30T02:00:00Z",
            screenshotUrl = "https://example.com/rejected_photo.jpg",
        )

        val draftReport = TesterReport(
            id = "rep-rejected-today",
            userId = user1,
            campaignId = camp1,
            status = "rejected",
            createdAt = "2026-10-30T02:00:00Z",
            screenshotUrl = null,
        )

        val result = ReportComparisonSelector.selectComparisonTarget(
            currentReport = draftReport,
            availableReports = emptyList(),
            rejectedReportOverride = todayRejectedReport,
        )

        assertEquals(ReportComparisonSelector.TargetRelation.REJECTED_SAME_DAY, result.relation)
        assertEquals("rep-rejected-today", result.targetReport?.id)
        assertEquals("https://example.com/rejected_photo.jpg", result.targetReport?.screenshotUrl)
    }

    @Test
    fun partialHistoryReportsSpecialStatus() {
        val currentReport = TesterReport(
            id = "rep-today",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-30T03:00:00Z",
            screenshotUrl = "https://example.com/today.jpg",
        )

        val result = ReportComparisonSelector.selectComparisonTarget(
            currentReport = currentReport,
            availableReports = emptyList(),
            isHistoryPartial = true,
        )

        assertEquals(ReportComparisonSelector.TargetRelation.NONE_FOUND, result.relation)
        assertTrue(result.isHistoryPartial)
    }

    @Test
    fun stableTieBreakByIdWhenTimestampsMatch() {
        val currentReport = TesterReport(
            id = "rep-today",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-30T03:00:00Z",
            screenshotUrl = "https://example.com/today.jpg",
        )

        val reportA = TesterReport(
            id = "rep-aaa",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-29T10:00:00Z",
            screenshotUrl = "https://example.com/a.jpg",
        )
        val reportZ = TesterReport(
            id = "rep-zzz",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-29T10:00:00Z",
            screenshotUrl = "https://example.com/z.jpg",
        )

        // Passing in order [A, Z] vs [Z, A] must return identical tie-break result
        val result1 = ReportComparisonSelector.selectComparisonTarget(currentReport, listOf(reportA, reportZ))
        val result2 = ReportComparisonSelector.selectComparisonTarget(currentReport, listOf(reportZ, reportA))

        assertEquals(result1.targetReport?.id, result2.targetReport?.id)
        assertEquals("rep-zzz", result1.targetReport?.id)
    }

    @Test
    fun rejectsArbitraryOrMismatchedRejectedOverride() {
        val draftReport = TesterReport(
            id = "rep-draft",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-30T03:00:00Z",
            screenshotUrl = null,
        )

        // 1. Override from different user -> rejected
        val differentUserOverride = TesterReport(
            id = "rep-rej-user2",
            userId = user2,
            campaignId = camp1,
            status = "rejected",
            createdAt = "2026-10-30T02:00:00Z",
            screenshotUrl = "https://example.com/rej.jpg",
        )
        val resUser = ReportComparisonSelector.selectComparisonTarget(draftReport, emptyList(), rejectedReportOverride = differentUserOverride)
        assertEquals(ReportComparisonSelector.TargetRelation.NONE_FOUND, resUser.relation)

        // 2. Override from different campaign -> rejected
        val differentCampOverride = TesterReport(
            id = "rep-rej-camp2",
            userId = user1,
            campaignId = camp2,
            status = "rejected",
            createdAt = "2026-10-30T02:00:00Z",
            screenshotUrl = "https://example.com/rej.jpg",
        )
        val resCamp = ReportComparisonSelector.selectComparisonTarget(draftReport, emptyList(), rejectedReportOverride = differentCampOverride)
        assertEquals(ReportComparisonSelector.TargetRelation.NONE_FOUND, resCamp.relation)

        // 3. Override is pending (not rejected) -> rejected
        val pendingOverride = TesterReport(
            id = "rep-pending",
            userId = user1,
            campaignId = camp1,
            status = "pending",
            createdAt = "2026-10-30T02:00:00Z",
            screenshotUrl = "https://example.com/pending.jpg",
        )
        val resPending = ReportComparisonSelector.selectComparisonTarget(draftReport, emptyList(), rejectedReportOverride = pendingOverride)
        assertEquals(ReportComparisonSelector.TargetRelation.NONE_FOUND, resPending.relation)

        // 4. Override is from a previous day (not same day) -> rejected
        val prevDayOverride = TesterReport(
            id = "rep-rej-prev-day",
            userId = user1,
            campaignId = camp1,
            status = "rejected",
            createdAt = "2026-10-29T02:00:00Z",
            screenshotUrl = "https://example.com/rej_old.jpg",
        )
        val resDay = ReportComparisonSelector.selectComparisonTarget(draftReport, emptyList(), rejectedReportOverride = prevDayOverride)
        assertEquals(ReportComparisonSelector.TargetRelation.NONE_FOUND, resDay.relation)
    }

    @Test
    fun partialHistoryFlagPreservedEvenWhenMatchFound() {
        val currentReport = TesterReport(
            id = "rep-today",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-30T03:00:00Z",
            screenshotUrl = "https://example.com/today.jpg",
        )

        val yesterdayReport = TesterReport(
            id = "rep-yest",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "2026-10-29T10:00:00Z",
            screenshotUrl = "https://example.com/yest.jpg",
        )

        val result = ReportComparisonSelector.selectComparisonTarget(
            currentReport = currentReport,
            availableReports = listOf(yesterdayReport),
            isHistoryPartial = true,
        )

        assertEquals(ReportComparisonSelector.TargetRelation.PREVIOUS_DAY, result.relation)
        assertEquals("rep-yest", result.targetReport?.id)
        assertTrue("isHistoryPartial must be preserved even when a match is found", result.isHistoryPartial)
    }

    @Test
    fun invalidTimestampDoesNotClaimFirstReport() {
        val brokenReport = TesterReport(
            id = "rep-broken",
            userId = user1,
            campaignId = camp1,
            submissionContext = "app_test",
            createdAt = "invalid-date-format",
            screenshotUrl = "https://example.com/broken.jpg",
        )

        val result = ReportComparisonSelector.selectComparisonTarget(
            currentReport = brokenReport,
            availableReports = emptyList(),
            isHistoryPartial = false,
        )

        assertEquals(ReportComparisonSelector.TargetRelation.NONE_FOUND, result.relation)
        assertNull("Must not claim first report when timestamp is invalid", result.noteResId)
    }

    @Test
    fun formatIsoUtcToVnDateFormatsCorrectly() {
        // 2026-10-29T18:00:00Z is 01:00 on 30/10/2026 VN (+7)
        assertEquals("30/10/2026", ReportComparisonSelector.formatIsoUtcToVnDate("2026-10-29T18:00:00Z"))
        // 2026-10-29T16:00:00Z is 23:00 on 29/10/2026 VN (+7)
        assertEquals("29/10/2026", ReportComparisonSelector.formatIsoUtcToVnDate("2026-10-29T16:00:00Z"))
        assertEquals("", ReportComparisonSelector.formatIsoUtcToVnDate(null))
        assertEquals("", ReportComparisonSelector.formatIsoUtcToVnDate("not-a-date"))
    }
}
