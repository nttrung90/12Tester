package com.ntt.crosstest.localization

import android.app.Application
import android.content.Context
import com.ntt.crosstest.data.AppConfig
import com.ntt.crosstest.data.CreditLog
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.UserNotification
import com.ntt.crosstest.data.RejectReportResult
import com.ntt.crosstest.ui.viewmodels.AppUpdateReleaseUiState
import com.ntt.crosstest.util.CreditLogRenderer
import com.ntt.crosstest.util.NotificationRenderer
import com.ntt.crosstest.util.RejectionReasonRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/** Independent acceptance probes. Failures are expected until remaining defects are fixed. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalizationRecheckTest {
    private fun localizer(language: String): Localizer {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("testcheo_settings_sync", Context.MODE_PRIVATE)
            .edit().putString("app_language", language).commit()
        RuntimeEnvironment.setQualifiers(language)
        return Localizer(app)
    }

    @Test fun customRejectionContainingAdminMustBePreserved() {
        val text = "Tôi đã hỏi admin, ảnh bị cắt mất nút xác nhận; hãy chụp toàn màn hình."
        val actual = RejectionReasonRenderer.render(text, null, localizer("en"))
        assertEquals("Custom text must not be replaced based on a word match", text, actual)
    }

    @Test fun customAdminNotificationMustBePreserved() {
        val notification = UserNotification(id = "custom", type = "admin",
            title = "Cảnh báo bảo trì: không gửi lại báo cáo trong 15 phút",
            message = "Admin thông báo bảo trì, không phải cảnh báo vi phạm tài khoản")
        assertEquals(notification.title, NotificationRenderer.renderTitle(notification, localizer("en")))
    }

    @Test fun realCreditNotificationMustBeLocalizedInEnglish() {
        val notification = UserNotification(id = "credit", type = "credit",
            title = "Biến động số dư: +10 Credit 🪙", message = "Số dư hiện tại: 110 Credit")
        val actual = NotificationRenderer.renderTitle(notification, localizer("en"))
        assertNotEquals("Current SQL trigger title must not pass through unchanged", notification.title, actual)
    }

    @Test fun realWelcomeCreditLogMustBeLocalizedInEnglish() {
        val log = CreditLog(userId = "tester", amount = 100, action = "welcome",
            note = "Thưởng 100 Credit khi mở tài khoản")
        assertNotEquals("Real current welcome log falls through to Vietnamese note",
            log.note, CreditLogRenderer.render(log, localizer("en")))
    }

    @Test fun newRejectPayloadMustPreserveClientContract() {
        val payload = """{"success":true,"clawed_credits":10,"new_balance":90,"tester_locked":true,"owner_locked":true}"""
        val result = Json { ignoreUnknownKeys = true }.decodeFromString<RejectReportResult>(payload)
        assertEquals("Migration response must decode the actual clawback", 10, result.creditsDeducted)
        assertEquals(90, result.testerBalance)
        assertTrue(result.isTesterLocked)
        assertTrue(result.isOwnerLocked)
    }

    @Test fun realNewReportNotificationMustBeLocalizedInEnglish() {
        val notification = UserNotification(id = "report", type = "report",
            title = "Báo cáo kiểm thử mới 📋",
            message = "Ứng dụng \"DemoApp\" vừa nhận được 1 báo cáo kiểm thử mới từ tester. Vui lòng kiểm tra và duyệt.")
        val actual = NotificationRenderer.renderMessage(notification, localizer("en"))
        assertNotEquals("Current SQL trigger message must not pass through unchanged", notification.message, actual)
    }

    @Test fun clawbackMustNotBeLabeledAsProvisionalReward() {
        val log = CreditLog(userId = "tester", amount = -10, action = "legacy_unknown",
            note = "Thu hồi Credit tạm nhận do chủ app từ chối kết quả")
        val actual = CreditLogRenderer.render(log, localizer("en"))
        assertFalse("Negative clawback incorrectly classified as provisional reward: $actual",
            actual.lowercase().contains("provisional") || actual.lowercase().contains("temporary"))
    }

    @Test fun customLegacyEnglishConfigMustNotLeakIntoVietnamese() {
        val config = AppConfig(id = "android_client", minVersionCode = 1,
            latestVersionCode = 2, latestVersionName = "0.0.2",
            title = "Security update required", message = "Please install the latest security update")
        assertNull("Unknown legacy language must use localized default instead", config.localizedTitle(true))
        assertNull(config.localizedMessage(true))
    }

    @Test fun stateMessageMustChangeWithLocaleWithoutReissuingOperation() {
        val en = localizer("en")
        val original = en.get(com.ntt.crosstest.R.string.error_network)
        val state = AppUpdateReleaseUiState(error = original)
        val vi = localizer("vi")
        assertEquals("Persisted error must resolve in the current locale",
            vi.get(com.ntt.crosstest.R.string.error_network), state.error)
    }
}
