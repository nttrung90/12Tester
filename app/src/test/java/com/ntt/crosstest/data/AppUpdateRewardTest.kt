package com.ntt.crosstest.data

import android.app.Application
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.ntt.crosstest.ui.viewmodels.AppUpdateReleaseUiState
import com.ntt.crosstest.ui.viewmodels.CampaignDetailUiState
import com.ntt.crosstest.util.InstalledAppVersionReader
import com.ntt.crosstest.util.InstalledVersionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppUpdateRewardTest {

    // =========================================================================
    // 1. Kiểm thử InstalledAppVersionReader
    // =========================================================================

    @Test
    fun reader_returns_not_installed_when_package_missing() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val result = InstalledAppVersionReader.readInstalledVersion(context, "com.missing.app")
        assertTrue(result is InstalledVersionResult.NotInstalled)
    }

    @Test
    fun reader_returns_unverifiable_on_blank_package() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val result = InstalledAppVersionReader.readInstalledVersion(context, "  ")
        assertTrue(result is InstalledVersionResult.Unverifiable)
    }

    @Test
    fun reader_returns_installed_version_code_and_name() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val shadowPm = shadowOf(context.packageManager)
        val info = PackageInfo().apply {
            packageName = "com.installed.app"
            versionName = "2.3.4"
            @Suppress("DEPRECATION")
            versionCode = 42
        }
        shadowPm.installPackage(info)

        val result = InstalledAppVersionReader.readInstalledVersion(context, "com.installed.app")
        assertTrue(result is InstalledVersionResult.Installed)
        val installed = result as InstalledVersionResult.Installed
        assertEquals(42L, installed.versionCode)
        assertEquals("2.3.4", installed.versionName)
    }

    // =========================================================================
    // 2. Kiểm thử logic ngân sách và validation trong AppUpdateReleaseUiState
    // =========================================================================

    @Test
    fun owner_ui_state_calculates_budget_correctly_without_old_release() {
        val state = AppUpdateReleaseUiState(
            ownerProfile = Profile(id = "owner", credits = 100),
            currentRelease = null,
        )
        assertEquals(100, state.availableCredits)
        assertEquals(0, state.refundFromCurrent)
        assertEquals(100, state.effectiveBudget)
        assertEquals(-20, state.projectedRemaining)
        assertFalse(state.hasEnoughCredits)
    }

    @Test
    fun owner_ui_state_calculates_budget_correctly_with_refund_from_old_release() {
        val oldRelease = AppUpdateRelease(
            id = "rel-old",
            targetVersionCode = 10,
            status = "active",
            paidCount = 7,
            remainingCredits = 78,
            refundedCredits = 0,
        )
        // Chủ có 42 Credit khả dụng + hoàn 78 = 120 -> vừa đủ giữ 120 cho bản mới
        val state = AppUpdateReleaseUiState(
            ownerProfile = Profile(id = "owner", credits = 42),
            currentRelease = oldRelease,
        )
        assertEquals(42, state.availableCredits)
        assertEquals(78, state.refundFromCurrent)
        assertEquals(120, state.effectiveBudget)
        assertEquals(0, state.projectedRemaining)
        assertTrue(state.hasEnoughCredits)
    }

    @Test
    fun owner_can_publish_requires_version_code_greater_than_current() {
        val oldRelease = AppUpdateRelease(
            id = "rel-old",
            targetVersionCode = 25,
            status = "active",
            remainingCredits = 78,
        )
        val validBase = AppUpdateReleaseUiState(
            ownerProfile = Profile(id = "owner", credits = 200),
            currentRelease = oldRelease,
            versionName = "2.0.0",
            releaseNotes = "Fix bugs and enhance UI",
        )

        // VersionCode bằng bản cũ (25) -> Không được phép
        val stateEqual = validBase.copy(versionCode = "25")
        assertFalse(stateEqual.canPublish)

        // VersionCode nhỏ hơn bản cũ (24) -> Không được phép
        val stateLower = validBase.copy(versionCode = "24")
        assertFalse(stateLower.canPublish)

        // VersionCode lớn hơn bản cũ (26) -> Được phép
        val stateGreater = validBase.copy(versionCode = "26")
        assertTrue(stateGreater.canPublish)
    }

    @Test
    fun owner_can_publish_validates_blank_fields_and_lengths() {
        val state = AppUpdateReleaseUiState(
            ownerProfile = Profile(id = "owner", credits = 200),
            currentRelease = null,
            versionName = "",
            versionCode = "10",
            releaseNotes = "Notes",
        )
        assertFalse("versionName rỗng không được publish", state.canPublish)

        val stateNoNotes = state.copy(versionName = "1.0", releaseNotes = "   ")
        assertFalse("releaseNotes rỗng không được publish", stateNoNotes.canPublish)

        val stateTooLongName = state.copy(versionName = "A".repeat(101), releaseNotes = "Notes")
        assertFalse("versionName > 100 ký tự không được publish", stateTooLongName.canPublish)
    }

    // =========================================================================
    // 3. Kiểm thử trạng thái hiển thị của Tester (CampaignDetailUiState)
    // =========================================================================

    @Test
    fun tester_is_app_updated_checks_greater_or_equal() {
        val activeRelease = AppUpdateRelease(
            id = "rel-1",
            versionName = "1.5.0",
            targetVersionCode = 50,
            status = "active",
            paidCount = 10,
        )
        val baseState = CampaignDetailUiState(
            isOwner = false,
            appUpdate = activeRelease,
        )

        // Chưa cài đặt -> false
        assertFalse(baseState.copy(installedVersionCode = null).isAppUpdated)

        // Đang cài bản cũ (49 < 50) -> false
        assertFalse(baseState.copy(installedVersionCode = 49).isAppUpdated)

        // Đã cập nhật đúng bản yêu cầu (50 == 50) -> true
        assertTrue(baseState.copy(installedVersionCode = 50).isAppUpdated)

        // Đã cập nhật bản mới hơn (52 > 50) -> true (chấp nhận >=)
        assertTrue(baseState.copy(installedVersionCode = 52).isAppUpdated)
    }

    @Test
    fun tester_show_app_update_block_rules() {
        val activeRelease = AppUpdateRelease(
            id = "rel-1",
            targetVersionCode = 50,
            status = "active",
            paidCount = 5,
            remainingCredits = 90,
        )

        // Chủ app thật -> KHÔNG hiển thị khối cập nhật của tester
        val ownerState = CampaignDetailUiState(
            isOwner = true,
            appUpdate = activeRelease,
        )
        assertFalse(ownerState.showAppUpdateBlock)

        // Tester với đợt đang active và còn suất -> Hiển thị
        val testerState = CampaignDetailUiState(
            isOwner = false,
            appUpdate = activeRelease,
        )
        assertTrue(testerState.showAppUpdateBlock)

        // Đợt đã đủ 20 suất (paidCount = 20) -> Ẩn toàn bộ đối với tester
        val exhaustedState = testerState.copy(
            appUpdate = activeRelease.copy(paidCount = 20, remainingCredits = 0, status = "exhausted")
        )
        assertFalse("Đủ 20 người nhận thưởng thì ẩn khối cập nhật ở phía tester", exhaustedState.showAppUpdateBlock)

        // Đợt bị thay thế (superseded) -> Ẩn
        val supersededState = testerState.copy(
            appUpdate = activeRelease.copy(status = "superseded")
        )
        assertFalse(supersededState.showAppUpdateBlock)

        // Đợt đã kết thúc (closed) -> Ẩn
        val closedState = testerState.copy(
            appUpdate = activeRelease.copy(status = "closed")
        )
        assertFalse(closedState.showAppUpdateBlock)
    }

    // =========================================================================
    // 4. Bất biến kế toán: reserved_total = remaining + (paid_count * 6) + refunded = 120
    // =========================================================================

    @Test
    fun accounting_invariant_holds_across_all_lifecycle_states() {
        fun assertInvariant(rel: AppUpdateRelease) {
            val sum = rel.remainingCredits + (rel.paidCount * rel.rewardPerClaim) + rel.refundedCredits
            assertEquals(120, rel.reservedCredits)
            assertEquals(6, rel.rewardPerClaim)
            assertEquals(rel.reservedCredits, sum)
            assertTrue(rel.paidCount in 0..20)
            assertTrue(rel.remainingCredits >= 0)
            assertTrue(rel.refundedCredits >= 0)
        }

        // 1. Mới khởi tạo
        assertInvariant(AppUpdateRelease(status = "active", paidCount = 0, remainingCredits = 120, refundedCredits = 0))

        // 2. Sau 7 người nhận
        assertInvariant(AppUpdateRelease(status = "active", paidCount = 7, remainingCredits = 78, refundedCredits = 0))

        // 3. Đủ 20 người nhận
        assertInvariant(AppUpdateRelease(status = "exhausted", paidCount = 20, remainingCredits = 0, refundedCredits = 0))

        // 4. Bị đóng sớm sau 7 người nhận (hoàn 78)
        assertInvariant(AppUpdateRelease(status = "closed", paidCount = 7, remainingCredits = 0, refundedCredits = 78))

        // 5. Bị thay thế sau 7 người nhận (hoàn 78)
        assertInvariant(AppUpdateRelease(status = "superseded", paidCount = 7, remainingCredits = 0, refundedCredits = 78))

        // 6. Bị đóng sớm khi chưa ai nhận (hoàn 120)
        assertInvariant(AppUpdateRelease(status = "closed", paidCount = 0, remainingCredits = 0, refundedCredits = 120))
    }
}
