package com.ntt.crosstest.util

import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.NextCampaignCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextCampaignSelectorTest {

    private fun makeCandidate(
        id: String,
        pkg: String,
        targetTesters: Int = 12,
        todayTestedCount: Int = 0,
        installedCount: Int = 10,
    ): NextCampaignCandidate {
        return NextCampaignCandidate(
            campaign = Campaign(
                id = id,
                ownerId = "owner-$id",
                appName = "App $id",
                packageName = pkg,
                targetTesters = targetTesters,
                status = "open",
            ),
            installedCount = installedCount,
            todayTestedCount = todayTestedCount,
            statsDate = "2026-11-10",
        )
    }

    // ========================================================================
    // 1. TARGET BOUNDARY CHECKS (12, 15, 20 & Normalization)
    // ========================================================================

    @Test
    fun targetBoundaries_underTarget_classifiedAsNotMet() {
        // Target 12: 0..11 are under target
        assertEquals(false as Boolean?, NextCampaignSelector.isTargetMet(makeCandidate("c1", "pkg.1", 12, 0)))
        assertEquals(false as Boolean?, NextCampaignSelector.isTargetMet(makeCandidate("c1", "pkg.1", 12, 11)))

        // Target 15: 14 is under target
        assertEquals(false as Boolean?, NextCampaignSelector.isTargetMet(makeCandidate("c2", "pkg.2", 15, 14)))

        // Target 20: 19 is under target
        assertEquals(false as Boolean?, NextCampaignSelector.isTargetMet(makeCandidate("c3", "pkg.3", 20, 19)))
    }

    @Test
    fun targetBoundaries_reachedOrExceeded_classifiedAsMet() {
        // Target 12: 12 is reached, 13 is exceeded
        assertEquals(true as Boolean?, NextCampaignSelector.isTargetMet(makeCandidate("c1", "pkg.1", 12, 12)))
        assertEquals(true as Boolean?, NextCampaignSelector.isTargetMet(makeCandidate("c1", "pkg.1", 12, 13)))

        // Target 15: 15 is reached, 16 is exceeded
        assertEquals(true as Boolean?, NextCampaignSelector.isTargetMet(makeCandidate("c2", "pkg.2", 15, 15)))
        assertEquals(true as Boolean?, NextCampaignSelector.isTargetMet(makeCandidate("c2", "pkg.2", 15, 16)))

        // Target 20: 20 is reached, 21 is exceeded
        assertEquals(true as Boolean?, NextCampaignSelector.isTargetMet(makeCandidate("c3", "pkg.3", 20, 20)))
        assertEquals(true as Boolean?, NextCampaignSelector.isTargetMet(makeCandidate("c3", "pkg.3", 20, 21)))
    }

    @Test
    fun targetBoundaries_invalidOrMissingCount_notAssumedZero() {
        // Missing count (null) must NOT be assumed zero, returns null
        val nullCandidate = NextCampaignCandidate(
            campaign = Campaign(id = "c-null", ownerId = "o", appName = "Null Count", packageName = "pkg.null", targetTesters = 12, status = "open"),
            installedCount = 5,
            todayTestedCount = null,
            statsDate = null,
        )
        assertNull(NextCampaignSelector.isTargetMet(nullCandidate))
        assertEquals(
            NextCampaignSelector.PriorityGroup.INVALID_OR_UNKNOWN,
            NextCampaignSelector.classifyGroup(nullCandidate, setOf("pkg.null"))
        )

        // Negative count from malformed payload must NOT enter group 1/2
        val negativeCandidate = NextCampaignCandidate(
            campaign = Campaign(id = "c-neg", ownerId = "o", appName = "Negative Count", packageName = "pkg.neg", targetTesters = 12, status = "open"),
            installedCount = 5,
            todayTestedCount = -1,
            statsDate = "2026-11-10",
        )
        assertNull(NextCampaignSelector.isTargetMet(negativeCandidate))
        assertEquals(
            NextCampaignSelector.PriorityGroup.INVALID_OR_UNKNOWN,
            NextCampaignSelector.classifyGroup(negativeCandidate, setOf("pkg.neg"))
        )

        // When only invalid/missing candidates exist, selector returns null (they cannot win!)
        val selected = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(nullCandidate, negativeCandidate),
            installedPackages = setOf("pkg.null", "pkg.neg"),
            isFirstTest = false,
        )
        assertNull("Invalid/missing count candidates must never be chosen into groups 1..4", selected)
    }

    @Test
    fun targetBoundaries_invalidTargetNormalizedTo12() {
        // targetTesters = 10 (not in 12, 15, 20) -> dailyTarget normalizes to 12
        val nonStandard10 = makeCandidate("c1", "pkg.1", targetTesters = 10, todayTestedCount = 11)
        assertEquals(12, nonStandard10.campaign.dailyTarget)
        assertEquals(false as Boolean?, NextCampaignSelector.isTargetMet(nonStandard10))

        val nonStandard10Met = makeCandidate("c1", "pkg.1", targetTesters = 10, todayTestedCount = 12)
        assertEquals(true as Boolean?, NextCampaignSelector.isTargetMet(nonStandard10Met))

        // targetTesters = 30 -> normalizes to 12
        val nonStandard30 = makeCandidate("c2", "pkg.2", targetTesters = 30, todayTestedCount = 12)
        assertEquals(12, nonStandard30.campaign.dailyTarget)
        assertEquals(true as Boolean?, NextCampaignSelector.isTargetMet(nonStandard30))
    }

    // ========================================================================
    // 2. GROUP CLASSIFICATION
    // ========================================================================

    @Test
    fun groupClassification_allFourGroupsDistinct() {
        val installedPackages = setOf("pkg.installed.under", "pkg.installed.full")

        val g1 = makeCandidate("1", "pkg.installed.under", 12, 5) // under + installed
        val g2 = makeCandidate("2", "pkg.notinstalled.under", 12, 5) // under + not installed
        val g3 = makeCandidate("3", "pkg.installed.full", 12, 12) // full + installed
        val g4 = makeCandidate("4", "pkg.notinstalled.full", 12, 12) // full + not installed

        assertEquals(NextCampaignSelector.PriorityGroup.GROUP_1_UNDER_TARGET_INSTALLED, NextCampaignSelector.classifyGroup(g1, installedPackages))
        assertEquals(NextCampaignSelector.PriorityGroup.GROUP_2_UNDER_TARGET_NOT_INSTALLED, NextCampaignSelector.classifyGroup(g2, installedPackages))
        assertEquals(NextCampaignSelector.PriorityGroup.GROUP_3_REACHED_TARGET_INSTALLED, NextCampaignSelector.classifyGroup(g3, installedPackages))
        assertEquals(NextCampaignSelector.PriorityGroup.GROUP_4_REACHED_TARGET_NOT_INSTALLED, NextCampaignSelector.classifyGroup(g4, installedPackages))
    }

    // ========================================================================
    // 3. STRICT FOUR-GROUP ORDERING
    // ========================================================================

    @Test
    fun strictOrdering_allFourGroupsPresent_onlyGroup1Selected() {
        val installedPackages = setOf("pkg.1", "pkg.3")
        val c1 = makeCandidate("1", "pkg.1", 12, 5) // Group 1
        val c2 = makeCandidate("2", "pkg.2", 12, 5) // Group 2
        val c3 = makeCandidate("3", "pkg.3", 12, 12) // Group 3
        val c4 = makeCandidate("4", "pkg.4", 12, 12) // Group 4

        val selected = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(c4, c3, c2, c1),
            installedPackages = installedPackages,
            isFirstTest = false,
        )
        assertNotNull(selected)
        assertEquals("1", selected?.id)
    }

    @Test
    fun strictOrdering_group1Empty_group2Selected() {
        val installedPackages = setOf("pkg.3")
        val c2 = makeCandidate("2", "pkg.2", 12, 5) // Group 2
        val c3 = makeCandidate("3", "pkg.3", 12, 12) // Group 3
        val c4 = makeCandidate("4", "pkg.4", 12, 12) // Group 4

        val selected = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(c4, c3, c2),
            installedPackages = installedPackages,
            isFirstTest = false,
        )
        assertNotNull(selected)
        assertEquals("2", selected?.id)
    }

    @Test
    fun strictOrdering_groups1And2Empty_group3Selected() {
        val installedPackages = setOf("pkg.3")
        val c3 = makeCandidate("3", "pkg.3", 12, 12) // Group 3 (full + installed)
        val c4 = makeCandidate("4", "pkg.4", 12, 12) // Group 4 (full + not installed)

        val selected = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(c4, c3),
            installedPackages = installedPackages,
            isFirstTest = false,
        )
        assertNotNull(selected)
        assertEquals("3", selected?.id)
    }

    @Test
    fun strictOrdering_onlyGroup4Present_stillSelectsGroup4() {
        val installedPackages = emptySet<String>()
        val c4 = makeCandidate("4", "pkg.4", 12, 15) // Group 4 (exceeded + not installed)

        val selected = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(c4),
            installedPackages = installedPackages,
            isFirstTest = false,
        )
        assertNotNull(selected)
        assertEquals("4", selected?.id)
    }

    @Test
    fun strictOrdering_emptyCandidates_returnsNull() {
        val selected = NextCampaignSelector.selectNextCampaign(
            candidates = emptyList(),
            installedPackages = setOf("pkg.1"),
            isFirstTest = false,
        )
        assertNull(selected)
    }

    // ========================================================================
    // 4. MANDATORY SPEC EXAMPLE (A installed 12/12 vs B not installed 2/12)
    // ========================================================================

    @Test
    fun mandatorySpecExample_uninstalledUnderTargetBeatsInstalledFull() {
        // "Ví dụ bắt buộc: A đã cài, đủ 12/12; B chưa cài, mới 2/12. Kết quả phải chọn B."
        val installedPackages = setOf("pkg.a")
        val appA = makeCandidate("appA", "pkg.a", 12, todayTestedCount = 12, installedCount = 50)
        val appB = makeCandidate("appB", "pkg.b", 12, todayTestedCount = 2, installedCount = 10)

        val selected = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(appA, appB),
            installedPackages = installedPackages,
            isFirstTest = false,
        )
        assertEquals("Uninstalled under-target B must win over installed full A", "appB", selected?.id)

        // When B is removed (no under-target apps remain), A becomes eligible again
        val fallbackSelected = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(appA),
            installedPackages = installedPackages,
            isFirstTest = false,
        )
        assertEquals("Full app A is selected when no under-target apps remain", "appA", fallbackSelected?.id)
    }

    // ========================================================================
    // 5. FIRST-TIME TESTER PREFERENCE (Low installed_count inside winning group)
    // ========================================================================

    @Test
    fun firstTimeTester_lowInstalledCountWinsInsideWinningGroup() {
        val installedPackages = setOf("pkg.1", "pkg.2", "pkg.3")
        val c1 = makeCandidate("1", "pkg.1", 12, 0, installedCount = 20)
        val c2 = makeCandidate("2", "pkg.2", 12, 0, installedCount = 5) // lowest installed_count
        val c3 = makeCandidate("3", "pkg.3", 12, 0, installedCount = 15)

        val selected = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(c1, c2, c3),
            installedPackages = installedPackages,
            isFirstTest = true,
        )
        assertEquals("Lowest installed_count candidate must win for first-time tester", "2", selected?.id)
    }

    @Test
    fun firstTimeTester_lowInstalledCountDoesNotCrossGroupBoundaries() {
        // "A đủ chỉ tiêu có số người tham gia ít; B chưa đủ có số người tham gia nhiều; tài khoản mới -> Chọn B"
        val installedPackages = emptySet<String>()
        val appA = makeCandidate("appA", "pkg.a", 12, todayTestedCount = 12, installedCount = 1) // Full, installedCount = 1
        val appB = makeCandidate("appB", "pkg.b", 12, todayTestedCount = 2, installedCount = 500) // Under target, installedCount = 500

        val selected = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(appA, appB),
            installedPackages = installedPackages,
            isFirstTest = true,
        )
        assertEquals("Under-target group must win over full group even if full has lower installedCount", "appB", selected?.id)
    }

    @Test
    fun firstTimeTester_tiesWithinWinningGroup_pickedRandomlyAmongTies() {
        val installedPackages = emptySet<String>()
        val c1 = makeCandidate("1", "pkg.1", 12, 0, installedCount = 5)
        val c2 = makeCandidate("2", "pkg.2", 12, 0, installedCount = 5)
        val c3 = makeCandidate("3", "pkg.3", 12, 0, installedCount = 20)

        // Custom picker picks the last element among shortlisted candidates
        val picked = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(c1, c2, c3),
            installedPackages = installedPackages,
            isFirstTest = true,
            randomPicker = { it.lastOrNull() },
        )
        assertEquals("Must pick from among the tied lowest candidates (c1, c2)", "2", picked?.id)
    }

    @Test
    fun regularTester_noInstalledCountBiasWithinGroup() {
        val installedPackages = emptySet<String>()
        val c1 = makeCandidate("1", "pkg.1", 12, 0, installedCount = 100)
        val c2 = makeCandidate("2", "pkg.2", 12, 0, installedCount = 2)

        // For regular tester, all candidates in the winning group are in the pool
        var c1Selected = false
        var c2Selected = false
        for (i in 1..50) {
            val chosen = NextCampaignSelector.selectNextCampaign(
                candidates = listOf(c1, c2),
                installedPackages = installedPackages,
                isFirstTest = false,
            )
            if (chosen?.id == "1") c1Selected = true
            if (chosen?.id == "2") c2Selected = true
        }
        assertTrue("Regular tester must randomly select from entire group without installedCount filter", c1Selected && c2Selected)
    }
}
