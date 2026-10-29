package com.ntt.crosstest.ui.viewmodels

import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CachedSessionData
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

class DailyHomeOrderTest {

    private fun createDisplayItem(
        id: String?,
        appName: String = "App $id",
        packageName: String = "com.test.${id ?: "pkg"}",
        ownerId: String? = "owner1",
        slotNumber: Int = 1,
        targetTesters: Int = 12,
        todayTestedCount: Int = 0,
        isBoosted: Boolean = false,
        tipCredits: Int = 0,
        homeTestedCount: Int? = null,
        statsDate: String? = "2026-10-28",
        hasTestedToday: Boolean = false,
        isInstalledLocally: Boolean = false,
        ownerName: String = "Owner",
        pushBusinessDate: String? = null,
        pushExpiresAt: String? = null,
        pushOrder: Long? = null,
    ): CampaignDisplayItem {
        val campaign = Campaign(
            id = id,
            ownerId = ownerId,
            appName = appName,
            packageName = packageName,
            targetTesters = targetTesters,
            isBoosted = isBoosted,
            tipCredits = tipCredits,
            slotNumber = slotNumber,
            status = "open",
            pushBusinessDate = pushBusinessDate,
            pushExpiresAt = pushExpiresAt,
            pushOrder = pushOrder,
        )
        return CampaignDisplayItem(
            campaign = campaign,
            ownerName = ownerName,
            installedCount = 10,
            todayTestedCount = todayTestedCount,
            hasTestedToday = hasTestedToday,
            isInstalledLocally = isInstalledLocally,
            homeTestedCount = homeTestedCount,
            statsDate = statsDate,
        )
    }

    @Test
    fun testHashAlgorithmKnownVector() {
        val date = "2026-10-28"
        val stableId = "b7e28328-97ff-4375-9c9e-5e3be0a3ce57"
        val key = DailyHomeOrder.computeDailyOrderKey(date, stableId)

        // Băm trực tiếp bằng SHA-256 chuẩn
        val expectedDigest = MessageDigest.getInstance("SHA-256")
            .digest("home-order-v1|2026-10-28|b7e28328-97ff-4375-9c9e-5e3be0a3ce57".toByteArray(StandardCharsets.UTF_8))
        val expectedHex = expectedDigest.joinToString("") { "%02x".format(it) }

        assertEquals(64, key.length)
        assertEquals(expectedHex, key)
        assertEquals(key.lowercase(), key)
    }

    @Test
    fun testStableCampaignIdentifierNormalization() {
        // UUID có chữ hoa và khoảng trắng
        val c1 = Campaign(id = "  B7E28328-97FF-4375-9C9E-5E3BE0A3CE57  ", ownerId = "u1", packageName = "com.app")
        assertEquals("b7e28328-97ff-4375-9c9e-5e3be0a3ce57", DailyHomeOrder.getStableCampaignIdentifier(c1))

        // Null id -> Fallback ownerId_packageName_slotNumber
        val c2 = Campaign(id = null, ownerId = "  Owner1  ", packageName = "  com.Test.App  ", slotNumber = 2)
        assertEquals("owner1_com.test.app_2", DailyHomeOrder.getStableCampaignIdentifier(c2))

        // Blank id -> Fallback
        val c3 = Campaign(id = "   ", ownerId = "Owner2", packageName = "com.pkg", slotNumber = 1)
        assertEquals("owner2_com.pkg_1", DailyHomeOrder.getStableCampaignIdentifier(c3))
    }

    @Test
    fun testSortOrderStableWithinDay_independentOfInputOrder() {
        val date = "2026-10-28"
        // 8 app cùng nhóm ưu tiên: chưa đạt target, không boost, không tip
        val items = (1..8).map { i ->
            createDisplayItem(id = "campaign-uuid-$i", appName = "App $i")
        }

        val sorted1 = DailyHomeOrder.sortOpenCampaigns(items, date)
        val sorted2 = DailyHomeOrder.sortOpenCampaigns(items.reversed(), date)
        val sorted3 = DailyHomeOrder.sortOpenCampaigns(items.shuffled(), date)

        val ids1 = sorted1.map { it.campaign.id }
        val ids2 = sorted2.map { it.campaign.id }
        val ids3 = sorted3.map { it.campaign.id }

        // Kết quả phải tuyệt đối đồng nhất bất kể thứ tự đầu vào
        assertEquals(ids1, ids2)
        assertEquals(ids1, ids3)
    }

    @Test
    fun testPrioritiesPreserved_randomOnlyBreaksTies() {
        val date = "2026-10-28"

        // App 1: Chưa đủ chỉ tiêu (2/12), không Boost, tip 0
        val app1 = createDisplayItem(id = "app1", todayTestedCount = 2, isBoosted = false, tipCredits = 0)
        // App 2: Đã đủ chỉ tiêu (12/12), CÓ Boost, tip khủng 100
        val app2 = createDisplayItem(id = "app2", todayTestedCount = 12, isBoosted = true, tipCredits = 100)
        // App 3: Chưa đủ chỉ tiêu (2/12), CÓ Boost, tip 0
        val app3 = createDisplayItem(id = "app3", todayTestedCount = 2, isBoosted = true, tipCredits = 0)
        // App 4: Chưa đủ chỉ tiêu (2/12), CÓ Boost, tip 50
        val app4 = createDisplayItem(id = "app4", todayTestedCount = 2, isBoosted = true, tipCredits = 50)

        val input = listOf(app1, app2, app3, app4).shuffled()
        val sorted = DailyHomeOrder.sortOpenCampaigns(input, date)

        // 1. Chưa đủ chỉ tiêu + Boost + tip cao nhất (app4: tip 50)
        assertEquals("app4", sorted[0].campaign.id)
        // 2. Chưa đủ chỉ tiêu + Boost + tip thấp hơn (app3: tip 0)
        assertEquals("app3", sorted[1].campaign.id)
        // 3. Chưa đủ chỉ tiêu + không Boost (app1)
        assertEquals("app1", sorted[2].campaign.id)
        // 4. Đã đủ chỉ tiêu (app2) - dù có Boost và tip 100 vẫn phải đứng sau app chưa đủ chỉ tiêu
        assertEquals("app2", sorted[3].campaign.id)
    }

    @Test
    fun testAddingOrRemovingApp_preservesRelativeOrderOfRemainingApps() {
        val date = "2026-10-28"
        // 5 app cùng nhóm ưu tiên
        val apps = (1..5).map { i ->
            createDisplayItem(id = "camp-$i")
        }

        val sortedInitial = DailyHomeOrder.sortOpenCampaigns(apps, date)
        val initialOrder = sortedInitial.map { it.campaign.id!! }

        // Chọn app đứng giữa để xóa
        val appToRemove = initialOrder[2]
        val remainingInput = apps.filter { it.campaign.id != appToRemove }
        val sortedAfterRemoval = DailyHomeOrder.sortOpenCampaigns(remainingInput, date)
        val orderAfterRemoval = sortedAfterRemoval.map { it.campaign.id!! }

        val expectedOrder = initialOrder.filter { it != appToRemove }
        assertEquals("Thứ tự tương đối của các app còn lại không được đổi sau khi xóa", expectedOrder, orderAfterRemoval)

        // Thêm một app mới vào danh sách
        val newApp = createDisplayItem(id = "camp-new-6")
        val withNewApp = DailyHomeOrder.sortOpenCampaigns(remainingInput + newApp, date)
        val filteredNewOrder = withNewApp.map { it.campaign.id!! }.filter { it != "camp-new-6" }

        assertEquals("Thêm app mới không làm đảo thứ tự tương đối của các app cũ", expectedOrder, filteredNewOrder)
    }

    @Test
    fun testStableTieBreakerById() {
        val date = "2026-10-28"
        // Hai app có ID khác nhau nhưng giả định cùng priority
        val appA = createDisplayItem(id = "uuid-aaa")
        val appB = createDisplayItem(id = "uuid-bbb")

        val sorted1 = DailyHomeOrder.sortOpenCampaigns(listOf(appB, appA), date)
        val sorted2 = DailyHomeOrder.sortOpenCampaigns(listOf(appA, appB), date)

        assertEquals(sorted1.map { it.campaign.id }, sorted2.map { it.campaign.id })
    }

    @Test
    fun testEdgeCases_emptyList_singleApp_missingIdentifiers() {
        val date = "2026-10-28"

        // Danh sách rỗng
        val emptyResult = DailyHomeOrder.sortOpenCampaigns(emptyList(), date)
        assertTrue(emptyResult.isEmpty())

        // 1 app
        val single = createDisplayItem(id = "single")
        val singleResult = DailyHomeOrder.sortOpenCampaigns(listOf(single), date)
        assertEquals(1, singleResult.size)
        assertEquals("single", singleResult.first().campaign.id)

        // App thiếu ID (null)
        val noId1 = createDisplayItem(id = null, ownerId = "owner1", packageName = "com.app.a", slotNumber = 1)
        val noId2 = createDisplayItem(id = null, ownerId = "owner2", packageName = "com.app.b", slotNumber = 1)
        val noIdSorted1 = DailyHomeOrder.sortOpenCampaigns(listOf(noId1, noId2), date)
        val noIdSorted2 = DailyHomeOrder.sortOpenCampaigns(listOf(noId2, noId1), date)

        assertEquals(
            noIdSorted1.map { it.campaign.packageName },
            noIdSorted2.map { it.campaign.packageName }
        )
    }

    @Test
    fun testPreservesAllDataFieldsAfterSort() {
        val date = "2026-10-28"
        val item = createDisplayItem(
            id = "c1",
            appName = "Demo App",
            packageName = "com.demo.app",
            ownerId = "owner_xyz",
            slotNumber = 3,
            targetTesters = 15,
            todayTestedCount = 4,
            isBoosted = true,
            tipCredits = 25,
            homeTestedCount = 8,
            statsDate = "2026-10-28",
            hasTestedToday = true,
            isInstalledLocally = true,
            ownerName = "Nguyen Van A"
        )

        val sorted = DailyHomeOrder.sortOpenCampaigns(listOf(item), date)
        val result = sorted.first()

        assertEquals("c1", result.campaign.id)
        assertEquals("Demo App", result.campaign.appName)
        assertEquals("com.demo.app", result.campaign.packageName)
        assertEquals("owner_xyz", result.campaign.ownerId)
        assertEquals(3, result.campaign.slotNumber)
        assertEquals(15, result.campaign.targetTesters)
        assertEquals(4, result.todayTestedCount)
        assertTrue(result.campaign.isBoosted)
        assertEquals(25, result.campaign.tipCredits)
        assertEquals(8, result.homeTestedCount)
        assertEquals("2026-10-28", result.statsDate)
        assertTrue(result.hasTestedToday)
        assertTrue(result.isInstalledLocally)
        assertEquals("Nguyen Van A", result.ownerName)
        assertEquals(10, result.installedCount)
    }

    @Test
    fun testPausedAndCompletedTabs_useDailyKeyWithoutBoostOrTipPriority() {
        val date = "2026-10-28"

        // Hai app ở tab Tạm dừng: app1 có boost và tip cao, app2 không có
        val app1 = createDisplayItem(id = "paused1", isBoosted = true, tipCredits = 100)
        val app2 = createDisplayItem(id = "paused2", isBoosted = false, tipCredits = 0)

        // Tính trực tiếp khóa băm theo ngày để biết app nào có key nhỏ hơn
        val key1 = DailyHomeOrder.computeDailyOrderKey(date, "paused1")
        val key2 = DailyHomeOrder.computeDailyOrderKey(date, "paused2")

        val sorted = DailyHomeOrder.sortOtherCampaigns(listOf(app1, app2), date)
        val expectedFirstId = if (key1 < key2) "paused1" else "paused2"

        // Thứ tự tab tạm dừng/hoàn thành phải hoàn toàn theo dailyKey, không ưu tiên Boost/tip
        assertEquals(expectedFirstId, sorted.first().campaign.id)
    }

    @Test
    fun testDifferentDatesChangeSortOrder() {
        // Nhiều app cùng nhóm để xác suất đổi vị trí cao
        val apps = (1..10).map { i ->
            createDisplayItem(id = "campaign-seq-$i")
        }

        val orderDay1 = DailyHomeOrder.sortOpenCampaigns(apps, "2026-10-28").map { it.campaign.id }
        val orderDay2 = DailyHomeOrder.sortOpenCampaigns(apps, "2026-10-29").map { it.campaign.id }

        // Bộ khóa của ngày khác nhau phải dẫn đến hoán vị khác nhau
        assertNotEquals("Sang ngày mới thứ tự app phải thay đổi", orderDay1, orderDay2)
    }

    @Test
    fun testCycleStatsGreaterThanTarget_butTodayNotReached_groupedInNotReached() {
        val date = "2026-10-28"

        // Campaign A: Chu kỳ 3 ngày cộng dồn 18 lượt (vượt mục tiêu 12), nhưng HÔM NAY mới test 2 lượt
        val campaignA = createDisplayItem(
            id = "campA",
            targetTesters = 12,
            todayTestedCount = 2,
            homeTestedCount = 18,
            isBoosted = false,
            tipCredits = 0
        )

        // Campaign B: Hôm nay đã test đủ 12 lượt (isTargetReached = true)
        val campaignB = createDisplayItem(
            id = "campB",
            targetTesters = 12,
            todayTestedCount = 12,
            homeTestedCount = 12,
            isBoosted = true, // có boost
            tipCredits = 50   // có tip
        )

        // Campaign A chưa đạt chỉ tiêu hôm nay (2 < 12) nên isTargetReached = false
        assertFalse(campaignA.isTargetReached)
        assertTrue(campaignB.isTargetReached)

        val sorted = DailyHomeOrder.sortOpenCampaigns(listOf(campaignB, campaignA), date)

        // Campaign A BẮT BUỘC phải đứng trước Campaign B dù homeTestedCount (18) > (12)
        assertEquals(
            "App chưa đủ chỉ tiêu hôm nay phải đứng trước, không dùng số chu kỳ cộng dồn để xếp",
            "campA",
            sorted[0].campaign.id
        )
        assertEquals("campB", sorted[1].campaign.id)
    }

    @Test
    fun testAcrossMidnightInMultiDayCycle_updatesOrderKeyWithoutChangingCycleStats() {
        val day1 = "2026-10-28"
        val day2 = "2026-10-29"

        val item = createDisplayItem(
            id = "camp-cycle",
            todayTestedCount = 4,
            homeTestedCount = 15, // Chu kỳ 2 hoặc 3 ngày
            statsDate = "2026-10-28"
        )

        val sortedDay1 = DailyHomeOrder.sortOpenCampaigns(listOf(item), day1).first()
        val sortedDay2 = DailyHomeOrder.sortOpenCampaigns(listOf(item), day2).first()

        // Số liệu chu kỳ không bị thay đổi bởi sort
        assertEquals(15, sortedDay1.homeTestedCount)
        assertEquals(15, sortedDay2.homeTestedCount)
        assertEquals(4, sortedDay1.todayTestedCount)
        assertEquals(4, sortedDay2.todayTestedCount)
    }

    @Test
    fun testSearchFilter_preservesRelativeOrderOfSortedItems() {
        val date = "2026-10-28"
        // 5 apps với tên chứa từ khóa
        val items = listOf(
            createDisplayItem(id = "app-alpha", appName = "Alpha Tool"),
            createDisplayItem(id = "app-beta", appName = "Beta Tool"),
            createDisplayItem(id = "app-gamma", appName = "Gamma Game"),
            createDisplayItem(id = "app-delta", appName = "Delta Tool"),
            createDisplayItem(id = "app-epsilon", appName = "Epsilon Tool"),
        )

        val sorted = DailyHomeOrder.sortOpenCampaigns(items, date)

        // Lọc theo từ khóa "Tool"
        val query = "Tool"
        val filtered = sorted.filter {
            it.campaign.appName.contains(query, ignoreCase = true) ||
            it.campaign.packageName.contains(query, ignoreCase = true) ||
            it.ownerName.contains(query, ignoreCase = true)
        }

        // Kết quả lọc phải giữ nguyên thứ tự tương đối của các app "Tool" trong danh sách đã sort
        val expectedIds = sorted.filter { it.campaign.appName.contains("Tool") }.map { it.campaign.id }
        assertEquals(expectedIds, filtered.map { it.campaign.id })
    }

    @Test
    fun testSanitizeOldSnapshot_andReSortForToday() {
        val yesterday = "2026-10-27"
        val today = "2026-10-28"

        // Hôm qua: App1 test đủ 12 lượt (reached = true), App2 test 2 lượt (reached = false)
        val app1 = createDisplayItem(id = "c1", todayTestedCount = 12, statsDate = yesterday, hasTestedToday = true, homeTestedCount = 12)
        val app2 = createDisplayItem(id = "c2", todayTestedCount = 2, statsDate = yesterday, hasTestedToday = false, homeTestedCount = 2)

        // Hôm qua: app2 đứng trước app1 vì app2 chưa đủ target
        val yesterdaySorted = DailyHomeOrder.sortOpenCampaigns(listOf(app1, app2), yesterday)
        assertEquals("c2", yesterdaySorted[0].campaign.id)
        assertEquals("c1", yesterdaySorted[1].campaign.id)

        // Lưu vào snapshot hôm qua
        val snapshot = CachedSessionData(
            userId = "user1",
            dateVn = yesterday,
            displayItems = listOf(app1, app2),
            todayCheckinCount = 5
        )

        // Mở app sang ngày mới: sanitize làm sạch dữ liệu hôm qua
        val sanitized = snapshot.sanitizeForDate()
        assertEquals(0, sanitized.todayCheckinCount)
        sanitized.displayItems.forEach { item ->
            assertEquals(0, item.todayTestedCount)
            assertFalse(item.hasTestedToday)
            assertNull(item.homeTestedCount)
            assertNull(item.statsDate)
            assertFalse(item.isTargetReached) // cả hai đều trở về chưa đạt target
        }

        // Sắp xếp lại cho ngày hôm nay: cả hai app cùng nhóm ưu tiên (đều chưa đạt target)
        // -> Thứ tự hoàn toàn do dailyKey của ngày hôm nay quyết định
        val todaySorted = DailyHomeOrder.sortOpenCampaigns(sanitized.displayItems, today)
        val key1 = DailyHomeOrder.computeDailyOrderKey(today, "c1")
        val key2 = DailyHomeOrder.computeDailyOrderKey(today, "c2")
        val expectedFirstId = if (key1 < key2) "c1" else "c2"

        assertEquals(expectedFirstId, todaySorted[0].campaign.id)
    }

    @Test
    fun testComposeStableKeyLogic() {
        // App có ID hợp lệ -> key không phụ thuộc index
        val itemWithId = createDisplayItem(id = "uuid-1234")
        val keyIndex0 = if (!itemWithId.campaign.id.isNullOrEmpty()) "disc_${itemWithId.campaign.id}" else "fallback_0"
        val keyIndex5 = if (!itemWithId.campaign.id.isNullOrEmpty()) "disc_${itemWithId.campaign.id}" else "fallback_5"
        assertEquals("disc_uuid-1234", keyIndex0)
        assertEquals("disc_uuid-1234", keyIndex5)
        assertEquals("Key phải giữ nguyên khi app đổi vị trí index", keyIndex0, keyIndex5)

        // App fixture thiếu ID -> key fallback có phân biệt index
        val itemNoId = createDisplayItem(id = null, ownerId = "owner1", packageName = "com.mock", slotNumber = 2)
        val fallbackKey0 = "disc_fallback_${itemNoId.campaign.ownerId}_${itemNoId.campaign.packageName}_${itemNoId.campaign.slotNumber}_0"
        val fallbackKey1 = "disc_fallback_${itemNoId.campaign.ownerId}_${itemNoId.campaign.packageName}_${itemNoId.campaign.slotNumber}_1"
        assertNotEquals(fallbackKey0, fallbackKey1)
    }

    @Test
    fun testBumpedAppAheadOfNormalAppsEvenIfTargetReachedAndNormalHasTip() {
        val today = "2026-10-29"

        // App thông thường: Chưa đủ chỉ tiêu (reached = false), có Tip 10, isBoosted = true
        val normalSuperApp = createDisplayItem(
            id = "normal_super",
            todayTestedCount = 2,
            targetTesters = 12,
            isBoosted = true,
            tipCredits = 10,
        )

        // App được đẩy hôm nay: ĐÃ đủ chỉ tiêu (reached = true), không có Tip, isBoosted = false
        val bumpedApp = createDisplayItem(
            id = "bumped_app",
            todayTestedCount = 12,
            targetTesters = 12,
            isBoosted = false,
            tipCredits = 0,
            pushBusinessDate = today,
            pushExpiresAt = "2026-10-29T17:00:00Z",
            pushOrder = 1L,
        )

        val result = DailyHomeOrder.sortOpenCampaigns(listOf(normalSuperApp, bumpedApp), today)

        assertEquals("App được đẩy phải đứng trước app thông thường dù đã đạt target và app kia có tip",
            "bumped_app", result[0].campaign.id)
        assertEquals("normal_super", result[1].campaign.id)
    }

    @Test
    fun testMultipleBumpedAppsOrder_NewerBumpStandsAboveOlderBump() {
        val today = "2026-10-29"

        // A đẩy lúc 09:00 (pushOrder = 10)
        val appA_first = createDisplayItem(
            id = "app_a",
            pushBusinessDate = today,
            pushExpiresAt = "2026-10-29T17:00:00Z",
            pushOrder = 10L,
        )

        // B đẩy lúc 10:00 (pushOrder = 20)
        val appB = createDisplayItem(
            id = "app_b",
            pushBusinessDate = today,
            pushExpiresAt = "2026-10-29T17:00:00Z",
            pushOrder = 20L,
        )

        val appNormal = createDisplayItem(id = "app_normal")

        // Thứ tự mong đợi: B (20) -> A (10) -> Normal
        val round1 = DailyHomeOrder.sortOpenCampaigns(listOf(appA_first, appB, appNormal), today)
        assertEquals(listOf("app_b", "app_a", "app_normal"), round1.map { it.campaign.id })

        // A mua lượt mới lúc 11:00 (pushOrder = 30)
        val appA_repushed = createDisplayItem(
            id = "app_a",
            pushBusinessDate = today,
            pushExpiresAt = "2026-10-29T17:00:00Z",
            pushOrder = 30L,
        )

        // Thứ tự mong đợi: A (30) -> B (20) -> Normal
        val round2 = DailyHomeOrder.sortOpenCampaigns(listOf(appB, appA_repushed, appNormal), today)
        assertEquals(listOf("app_a", "app_b", "app_normal"), round2.map { it.campaign.id })
    }

    @Test
    fun testMidnightRollover_LosesPushPriorityAutomatically() {
        val yesterday = "2026-10-28"
        val today = "2026-10-29"

        // App A được đẩy hôm qua (yesterday)
        val appA = createDisplayItem(
            id = "app_a",
            pushBusinessDate = yesterday,
            pushExpiresAt = "2026-10-28T17:00:00Z",
            pushOrder = 100L,
            todayTestedCount = 12,
            targetTesters = 12,
        )

        // App B là app thông thường, chưa đạt target
        val appB = createDisplayItem(
            id = "app_b",
            todayTestedCount = 2,
            targetTesters = 12,
        )

        // Hôm qua: App A được đẩy nên đứng trước App B dù App A đã đạt target
        val yesterdaySorted = DailyHomeOrder.sortOpenCampaigns(listOf(appA, appB), yesterday)
        assertEquals("app_a", yesterdaySorted[0].campaign.id)
        assertEquals("app_b", yesterdaySorted[1].campaign.id)

        // Sang hôm nay: Lượt đẩy của hôm qua hết hiệu lực -> Cả hai quay lại thuật toán cũ
        // Vì appB chưa đạt target còn appA đã đạt target (hoặc nếu chưa sanitize thì appB đứng trước vì targetReached)
        val todaySorted = DailyHomeOrder.sortOpenCampaigns(listOf(appA, appB), today)
        assertEquals("Sang ngày mới, app được đẩy hôm qua mất ưu tiên và quay về luật cũ",
            "app_b", todaySorted[0].campaign.id)
        assertEquals("app_a", todaySorted[1].campaign.id)
    }

    @Test
    fun testRevertToExactOldAlgorithmWhenNoPushedApps() {
        val date = "2026-10-29"
        val items = (1..15).map { idx ->
            createDisplayItem(
                id = "camp_$idx",
                todayTestedCount = if (idx % 3 == 0) 12 else idx % 10,
                targetTesters = 12,
                isBoosted = idx % 2 == 0,
                tipCredits = idx % 5,
            )
        }

        val sorted = DailyHomeOrder.sortOpenCampaigns(items, date)
        val sortedReversedInput = DailyHomeOrder.sortOpenCampaigns(items.reversed(), date)

        // Tính xác định: kết quả giống nhau bất kể thứ tự input
        assertEquals(sorted.map { it.campaign.id }, sortedReversedInput.map { it.campaign.id })

        // Toàn bộ app chưa đạt target phải đứng trước app đã đạt target
        val notReached = sorted.filter { !it.isTargetReached }
        val reached = sorted.filter { it.isTargetReached }
        assertEquals(sorted, notReached + reached)
    }

    @Test
    fun testBumpedAppTieBreaker_ByStableId() {
        val today = "2026-10-29"
        val appZ = createDisplayItem(id = "uuid_z", pushBusinessDate = today, pushOrder = 50L)
        val appA = createDisplayItem(id = "uuid_a", pushBusinessDate = today, pushOrder = 50L)

        val result = DailyHomeOrder.sortOpenCampaigns(listOf(appZ, appA), today)
        assertEquals("Cùng pushOrder thì phá hòa ổn định theo stableId ASC",
            listOf("uuid_a", "uuid_z"), result.map { it.campaign.id })
    }
}
