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
        playStoreState: String = "unknown",
        playProductionDetected: Boolean = false,
    ): NextCampaignCandidate {
        return NextCampaignCandidate(
            campaign = Campaign(
                id = id,
                ownerId = "owner-$id",
                appName = "App $id",
                packageName = pkg,
                targetTesters = targetTesters,
                status = "open",
                playStoreState = playStoreState,
                playProductionDetected = playProductionDetected,
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

    // ========================================================================
    // 6. PLAY PRODUCTION DETECTION (6-GROUP STRICT PRIORITY)
    // ========================================================================

    @Test
    fun groupClassification_allSixGroupsDistinct() {
        val installedPackages = setOf(
            "pkg.g1",
            "pkg.g3",
            "pkg.g5",
        )

        val g1 = makeCandidate("1", "pkg.g1", 12, 3, playProductionDetected = false)
        val g2 = makeCandidate("2", "pkg.g2", 12, 3, playProductionDetected = false)
        val g3 = makeCandidate("3", "pkg.g3", 12, 12, playProductionDetected = false)
        val g4 = makeCandidate("4", "pkg.g4", 12, 12, playProductionDetected = false)
        val g5 = makeCandidate("5", "pkg.g5", 12, 0, playStoreState = "production", playProductionDetected = true)
        val g6 = makeCandidate("6", "pkg.g6", 12, 0, playStoreState = "production", playProductionDetected = true)

        assertEquals(NextCampaignSelector.PriorityGroup.GROUP_1_UNDER_TARGET_INSTALLED, NextCampaignSelector.classifyGroup(g1, installedPackages))
        assertEquals(NextCampaignSelector.PriorityGroup.GROUP_2_UNDER_TARGET_NOT_INSTALLED, NextCampaignSelector.classifyGroup(g2, installedPackages))
        assertEquals(NextCampaignSelector.PriorityGroup.GROUP_3_REACHED_TARGET_INSTALLED, NextCampaignSelector.classifyGroup(g3, installedPackages))
        assertEquals(NextCampaignSelector.PriorityGroup.GROUP_4_REACHED_TARGET_NOT_INSTALLED, NextCampaignSelector.classifyGroup(g4, installedPackages))
        assertEquals(NextCampaignSelector.PriorityGroup.GROUP_5_PRODUCTION_DETECTED_INSTALLED, NextCampaignSelector.classifyGroup(g5, installedPackages))
        assertEquals(NextCampaignSelector.PriorityGroup.GROUP_6_PRODUCTION_DETECTED_NOT_INSTALLED, NextCampaignSelector.classifyGroup(g6, installedPackages))
    }

    @Test
    fun strictOrdering_productionZeroOfTwelveInstalled_losesToClosedTestingFullNotInstalled() {
        val installedPackages = setOf("pkg.prod")
        val prodInstalledZero = makeCandidate(
            id = "prod-0-12",
            pkg = "pkg.prod",
            targetTesters = 12,
            todayTestedCount = 0,
            installedCount = 1,
            playStoreState = "production",
            playProductionDetected = true,
        )
        val closedFullNotInstalled = makeCandidate(
            id = "closed-12-12",
            pkg = "pkg.closed",
            targetTesters = 12,
            todayTestedCount = 12,
            installedCount = 500,
            playStoreState = "not_public",
            playProductionDetected = false,
        )

        val selectedRegular = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(prodInstalledZero, closedFullNotInstalled),
            installedPackages = installedPackages,
            isFirstTest = false,
        )
        assertEquals("Group 4 (closed 12/12 not installed) must beat Group 5 (prod 0/12 installed)", "closed-12-12", selectedRegular?.id)

        val selectedFirstTest = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(prodInstalledZero, closedFullNotInstalled),
            installedPackages = installedPackages,
            isFirstTest = true,
        )
        assertEquals("isFirstTest must not cross from Group 4 into Group 5", "closed-12-12", selectedFirstTest?.id)
    }

    @Test
    fun strictOrdering_groups1To4Empty_group5BeatsGroup6_andGroup6FallbackWorks() {
        val installedPackages = setOf("pkg.prod.installed")
        val g5 = makeCandidate("prod-inst", "pkg.prod.installed", 12, 12, installedCount = 50, playProductionDetected = true)
        val g6 = makeCandidate("prod-uninst", "pkg.prod.uninstalled", 12, 0, installedCount = 2, playProductionDetected = true)

        val selected = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(g6, g5),
            installedPackages = installedPackages,
            isFirstTest = false,
        )
        assertEquals("Group 5 (prod installed) must beat Group 6 (prod not installed)", "prod-inst", selected?.id)

        val fallback = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(g6),
            installedPackages = installedPackages,
            isFirstTest = false,
        )
        assertEquals("Group 6 must be selected when Groups 1..5 are empty", "prod-uninst", fallback?.id)
    }

    @Test
    fun firstTimeTester_lowestInstalledCountPreservedInsideGroup5AndGroup6() {
        val installedPackages = setOf("pkg.p1", "pkg.p2")
        val p1 = makeCandidate("p1", "pkg.p1", 12, 5, installedCount = 30, playProductionDetected = true)
        val p2 = makeCandidate("p2", "pkg.p2", 12, 10, installedCount = 4, playProductionDetected = true)
        val p3 = makeCandidate("p3", "pkg.p3", 12, 0, installedCount = 1, playProductionDetected = true) // Group 6

        val selectedG5 = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(p1, p2, p3),
            installedPackages = installedPackages,
            isFirstTest = true,
        )
        assertEquals("p2", selectedG5?.id)

        val u1 = makeCandidate("u1", "pkg.u1", 12, 0, installedCount = 25, playProductionDetected = true)
        val u2 = makeCandidate("u2", "pkg.u2", 12, 12, installedCount = 3, playProductionDetected = true)
        val selectedG6 = NextCampaignSelector.selectNextCampaign(
            candidates = listOf(u1, u2),
            installedPackages = emptySet(),
            isFirstTest = true,
        )
        assertEquals("u2", selectedG6?.id)
    }

    @Test
    fun playProductionDetectedFlag_hysteresisRespected_andInvalidCountStillRejected() {
        // playStoreState == "production" with playProductionDetected == false (awaiting 2nd confirmation or admin override)
        // must remain in Closed-Testing groups (Group 1..4), NOT Group 5..6.
        val unconfirmedProd = makeCandidate(
            id = "unconfirmed",
            pkg = "pkg.unconfirmed",
            targetTesters = 12,
            todayTestedCount = 2,
            playStoreState = "production",
            playProductionDetected = false,
        )
        assertEquals(
            NextCampaignSelector.PriorityGroup.GROUP_1_UNDER_TARGET_INSTALLED,
            NextCampaignSelector.classifyGroup(unconfirmedProd, setOf("pkg.unconfirmed")),
        )

        val invalidProd = NextCampaignCandidate(
            campaign = Campaign(
                id = "invalid-prod",
                appName = "Invalid",
                packageName = "pkg.invalid",
                status = "open",
                playStoreState = "production",
                playProductionDetected = true,
            ),
            installedCount = 5,
            todayTestedCount = null,
            statsDate = null,
        )
        assertEquals(
            NextCampaignSelector.PriorityGroup.INVALID_OR_UNKNOWN,
            NextCampaignSelector.classifyGroup(invalidProd, setOf("pkg.invalid")),
        )
        assertNull(
            NextCampaignSelector.selectNextCampaign(
                candidates = listOf(invalidProd),
                installedPackages = setOf("pkg.invalid"),
                isFirstTest = false,
            )
        )
    }
}
