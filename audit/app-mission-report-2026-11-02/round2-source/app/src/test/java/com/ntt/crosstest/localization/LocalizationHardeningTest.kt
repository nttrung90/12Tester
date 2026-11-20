package com.ntt.crosstest.localization

import android.app.Application
import android.content.Context
import com.ntt.crosstest.R
import com.ntt.crosstest.data.AppConfig
import com.ntt.crosstest.data.CreditLog
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SettingsRepository
import com.ntt.crosstest.data.TesterReport
import com.ntt.crosstest.data.UserNotification
import com.ntt.crosstest.data.UserWarning
import com.ntt.crosstest.util.CreditLogRenderer
import com.ntt.crosstest.util.NotificationRenderer
import com.ntt.crosstest.util.RejectionReasonRenderer
import com.ntt.crosstest.util.UiText
import com.ntt.crosstest.util.WarningRenderer
import com.ntt.crosstest.ui.viewmodels.friendlyError
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalizationHardeningTest {

    private fun withLocale(language: String, block: (Localizer) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("testcheo_settings_sync", Context.MODE_PRIVATE)
            .edit()
            .putString("app_language", language)
            .commit()
        RuntimeEnvironment.setQualifiers(language)
        val localizer = Localizer(app)
        block(localizer)
    }

    @Test
    fun testSettingsRepository_localeResolution() {
        val viLocale = SettingsRepository.resolveLocale("vi")
        assertEquals("vi", viLocale.language)

        val enLocale = SettingsRepository.resolveLocale("en")
        assertEquals("en", enLocale.language)

        val fallbackLocale = SettingsRepository.resolveLocale("invalid_lang")
        assertEquals(SettingsRepository.getSystemLocale().language, fallbackLocale.language)

        assertEquals("vi", SettingsRepository.normalizeLanguageSetting("vi"))
        assertEquals("en", SettingsRepository.normalizeLanguageSetting("en"))
        assertEquals("system", SettingsRepository.normalizeLanguageSetting("system"))
        assertEquals("system", SettingsRepository.normalizeLanguageSetting("unknown"))
    }

    @Test
    fun testRejectionReasonRenderer_predefinedCodes() {
        val reportIncorrect = TesterReport(
            id = "rep-1",
            campaignId = "camp-1",
            userId = "user-1",
            rejectionReason = "[CODE:${RejectionReasonRenderer.CODE_INCORRECT_APP}] Ghi chú thêm",
            reasonCode = RejectionReasonRenderer.CODE_INCORRECT_APP
        )

        withLocale("vi") { viLocalizer ->
            val viText = RejectionReasonRenderer.render(reportIncorrect, viLocalizer)
            assertTrue("VI should contain không phải app", viText.contains("không phải app") || viText.contains("không rõ") || viText.contains("app"))
        }

        withLocale("en") { enLocalizer ->
            val enText = RejectionReasonRenderer.render(reportIncorrect, enLocalizer)
            assertTrue("EN should contain not this app or unclear", enText.lowercase().contains("not this app") || enText.lowercase().contains("unclear"))
        }
    }

    @Test
    fun testRejectionReasonRenderer_legacyVietnameseMapping() {
        val reportLegacyVi = TesterReport(
            id = "rep-2",
            campaignId = "camp-1",
            userId = "user-1",
            rejectionReason = "Ảnh không đúng ứng dụng cần kiểm thử"
        )

        withLocale("en") { enLocalizer ->
            val enText = RejectionReasonRenderer.render(reportLegacyVi, enLocalizer)
            assertTrue("Legacy Vietnamese text should map to English text", enText.lowercase().contains("not this app") || enText.lowercase().contains("unclear") || enText.lowercase().contains("app"))
        }
    }

    @Test
    fun testRejectionReasonRenderer_customTextPreserved() {
        val customNote = "Người dùng chỉ mở app 10 giây rồi tắt ngay"
        val reportCustom = TesterReport(
            id = "rep-3",
            campaignId = "camp-1",
            userId = "user-1",
            rejectionReason = customNote
        )

        withLocale("vi") { viLocalizer ->
            val rendered = RejectionReasonRenderer.render(reportCustom, viLocalizer)
            assertEquals(customNote, rendered)
        }
    }

    @Test
    fun testCreditLogRenderer_standardActions() {
        val dailyLog = CreditLog(
            id = "log-1",
            userId = "user-1",
            amount = 5,
            action = "daily_login",
            note = null
        )

        withLocale("vi") { viLocalizer ->
            val dailyVi = CreditLogRenderer.render(dailyLog, viLocalizer)
            assertFalse(dailyVi.isBlank())
            assertTrue(dailyVi.contains("đăng nhập") || dailyVi.contains("Điểm danh"))
        }

        withLocale("en") { enLocalizer ->
            val dailyEn = CreditLogRenderer.render(dailyLog, enLocalizer)
            assertFalse(dailyEn.isBlank())
            assertTrue(dailyEn.lowercase().contains("daily") || dailyEn.lowercase().contains("login"))
        }
    }

    @Test
    fun testCreditLogRenderer_legacyVietnameseNotes() {
        val legacyLog = CreditLog(
            id = "log-2",
            userId = "user-1",
            amount = 10,
            action = "test_completed",
            note = "Thưởng kiểm thử ứng dụng DemoApp"
        )

        withLocale("en") { enLocalizer ->
            val renderedEn = CreditLogRenderer.render(legacyLog, enLocalizer)
            assertFalse(renderedEn.isBlank())
            assertFalse("Legacy Vietnamese note must not remain untranslated in English", renderedEn.contains("Thưởng") || renderedEn.contains("ứng dụng"))
            assertTrue(renderedEn.lowercase().contains("reward") || renderedEn.lowercase().contains("credit"))
        }
    }

    @Test
    fun testNotificationRenderer_eventCodes() {
        val notif = UserNotification(
            id = "notif-1",
            userId = "user-1",
            title = "Báo cáo được chấp nhận",
            message = "Chủ app đã duyệt báo cáo của bạn",
            type = "report",
            data = buildJsonObject {
                put("event_code", "tester_reviewed_accepted")
            }
        )

        withLocale("vi") { viLocalizer ->
            val titleVi = NotificationRenderer.renderTitle(notif, viLocalizer)
            assertTrue(titleVi.contains("chấp nhận") || titleVi.contains("duyệt"))
        }

        withLocale("en") { enLocalizer ->
            val titleEn = NotificationRenderer.renderTitle(notif, enLocalizer)
            assertTrue(titleEn.lowercase().contains("approved") || titleEn.lowercase().contains("accepted"))
        }
    }

    @Test
    fun testWarningRenderer_types() {
        val warning = UserWarning(
            id = "warn-1",
            userId = "user-1",
            warningType = "tester_rejection_streak",
            message = "Hệ thống ghi nhận bạn tham gia kiểm thử chưa nghiêm túc"
        )

        withLocale("vi") { viLocalizer ->
            val warnVi = WarningRenderer.render(warning, viLocalizer)
            assertFalse(warnVi.isBlank())
            assertTrue(warnVi.contains("cảnh báo") || warnVi.contains("nghiêm túc"))
        }

        withLocale("en") { enLocalizer ->
            val warnEn = WarningRenderer.render(warning, enLocalizer)
            assertFalse(warnEn.isBlank())
            assertTrue(warnEn.lowercase().contains("warning") || warnEn.lowercase().contains("serious") || warnEn.lowercase().contains("streak"))
        }
    }

    @Test
    fun testFriendlyError_mapping() {
        val lockedEx = Exception("Error: account_locked")
        withLocale("vi") { viLocalizer ->
            val msgVi = friendlyError(lockedEx, viLocalizer)
            assertTrue(msgVi.contains("khóa"))
        }
        withLocale("en") { enLocalizer ->
            val msgEn = friendlyError(lockedEx, enLocalizer)
            assertTrue(msgEn.lowercase().contains("locked"))
        }

        val creditsEx = Exception("Error: insufficient_credits")
        withLocale("vi") { viLocalizer ->
            val creditMsgVi = friendlyError(creditsEx, viLocalizer)
            assertTrue(creditMsgVi.contains("Credit") || creditMsgVi.contains("không đủ"))
        }
        withLocale("en") { enLocalizer ->
            val creditMsgEn = friendlyError(creditsEx, enLocalizer)
            assertTrue(creditMsgEn.lowercase().contains("credit") || creditMsgEn.lowercase().contains("insufficient"))
        }

        val technicalEx = Exception("FATAL_POSTGRESQL_CONNECTION_TIMEOUT at line 492 in db_pool.c")
        withLocale("vi") { viLocalizer ->
            val safeMsgVi = friendlyError(technicalEx, viLocalizer)
            assertFalse(safeMsgVi.contains("FATAL_POSTGRESQL_CONNECTION_TIMEOUT"))
        }
        withLocale("en") { enLocalizer ->
            val safeMsgEn = friendlyError(technicalEx, enLocalizer)
            assertFalse(safeMsgEn.contains("FATAL_POSTGRESQL_CONNECTION_TIMEOUT"))
        }
    }

    @Test
    fun testAppConfig_bilingualResolution() {
        val configWithBoth = AppConfig(
            id = "android_client",
            minVersionCode = 1,
            latestVersionCode = 10,
            latestVersionName = "1.0.0",
            title = "Cập nhật ứng dụng",
            message = "Vui lòng cập nhật",
            titleVi = "Cập nhật tiếng Việt",
            titleEn = "English Update Available",
            messageVi = "Nội dung cập nhật tiếng Việt",
            messageEn = "English update details"
        )

        assertEquals("Cập nhật tiếng Việt", configWithBoth.localizedTitle(isVietnamese = true))
        assertEquals("English Update Available", configWithBoth.localizedTitle(isVietnamese = false))
        assertEquals("Nội dung cập nhật tiếng Việt", configWithBoth.localizedMessage(isVietnamese = true))
        assertEquals("English update details", configWithBoth.localizedMessage(isVietnamese = false))

        val legacyConfig = AppConfig(
            id = "android_client",
            minVersionCode = 1,
            latestVersionCode = 10,
            latestVersionName = "1.0.0",
            title = "Đã có bản cập nhật mới",
            message = "Phiên bản mới với nhiều tính năng nâng cấp và sửa lỗi đã sẵn sàng. Vui lòng cập nhật để tiếp tục sử dụng ứng dụng mượt mà nhất."
        )

        assertEquals(null, legacyConfig.localizedTitle(isVietnamese = true))
        assertEquals(null, legacyConfig.localizedTitle(isVietnamese = false))
        assertEquals(null, legacyConfig.localizedMessage(isVietnamese = true))
        assertEquals(null, legacyConfig.localizedMessage(isVietnamese = false))
    }

    @Test
    fun testUiText_resolution() {
        val rawUiText = UiText.DynamicString("Hello World")
        withLocale("vi") { viLocalizer ->
            assertEquals("Hello World", rawUiText.asString(viLocalizer))
            val resUiText = UiText.StringResource(R.string.cancel)
            assertFalse(resUiText.asString(viLocalizer).isBlank())
        }
    }

    @Test
    fun testXmlResourceParityAndFormatIntegrity() {
        val baseFile = java.io.File("src/main/res/values/strings.xml")
        val enFile = java.io.File("src/main/res/values-en/strings.xml")
        val viFile = java.io.File("src/main/res/values-vi/strings.xml")

        assertTrue("Base strings.xml must exist", baseFile.exists())
        assertTrue("EN strings.xml must exist", enFile.exists())
        assertTrue("VI strings.xml must exist", viFile.exists())

        fun extractKeyToPlaceholders(file: java.io.File): Map<String, List<String>> {
            val keyRegex = Regex("""<string\s+name="([^"]+)">(.*?)</string>""", setOf(RegexOption.DOT_MATCHES_ALL))
            val placeholderRegex = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""")
            val map = mutableMapOf<String, List<String>>()
            val allKeys = mutableListOf<String>()
            for (match in keyRegex.findAll(file.readText())) {
                val key = match.groupValues[1]
                val content = match.groupValues[2]
                allKeys.add(key)
                val placeholders = placeholderRegex.findAll(content).map { it.value }.toList()
                map[key] = placeholders
            }
            val duplicates = allKeys.groupBy { it }.filter { it.value.size > 1 }.keys
            assertTrue("Duplicate keys found in ${file.name}: $duplicates", duplicates.isEmpty())
            return map
        }

        val baseMap = extractKeyToPlaceholders(baseFile)
        val enMap = extractKeyToPlaceholders(enFile)
        val viMap = extractKeyToPlaceholders(viFile)

        val baseKeys = baseMap.keys
        val enKeys = enMap.keys
        val viKeys = viMap.keys

        val missingInEn = baseKeys - enKeys
        val missingInVi = baseKeys - viKeys
        val extraInEn = enKeys - baseKeys
        val extraInVi = viKeys - baseKeys

        assertTrue("All base keys must be in EN: missing $missingInEn", missingInEn.isEmpty())
        assertTrue("All base keys must be in VI: missing $missingInVi", missingInVi.isEmpty())
        assertTrue("No extra keys in EN: extra $extraInEn", extraInEn.isEmpty())
        assertTrue("No extra keys in VI: extra $extraInVi", extraInVi.isEmpty())
        assertEquals("EN key count must match base", baseKeys.size, enKeys.size)
        assertEquals("VI key count must match base", baseKeys.size, viKeys.size)

        // Verify format placeholder count parity for parameterized strings
        for (key in baseKeys) {
            val basePlaceholders = baseMap[key].orEmpty().filter { it != "%%" }
            val enPlaceholders = enMap[key].orEmpty().filter { it != "%%" }
            val viPlaceholders = viMap[key].orEmpty().filter { it != "%%" }
            assertEquals("Placeholder count in EN for key $key must match base", basePlaceholders.size, enPlaceholders.size)
            assertEquals("Placeholder count in VI for key $key must match base", basePlaceholders.size, viPlaceholders.size)
        }
    }
}
