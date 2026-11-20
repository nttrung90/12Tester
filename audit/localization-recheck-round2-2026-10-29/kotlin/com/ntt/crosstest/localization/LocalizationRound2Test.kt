package com.ntt.crosstest.localization

import android.app.Application
import android.content.Context
import com.ntt.crosstest.R
import com.ntt.crosstest.data.*
import com.ntt.crosstest.ui.viewmodels.AppUpdateReleaseUiState
import com.ntt.crosstest.ui.viewmodels.OwnerAppDetailUiState
import com.ntt.crosstest.util.NotificationRenderer
import com.ntt.crosstest.util.RejectionReasonRenderer
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocalizationRound2Test {
    private fun localizer(lang: String): Localizer {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("testcheo_settings_sync",Context.MODE_PRIVATE).edit().putString("app_language",lang).commit()
        RuntimeEnvironment.setQualifiers(lang)
        return Localizer(app)
    }

    @Test fun actualLegacyPredefinedReasonsMustTranslate() {
        val vi = localizer("vi")
        val raw = vi.get(R.string.oad_reject_reason_1)
        val en = localizer("en")
        assertEquals(en.get(R.string.oad_reject_reason_1), RejectionReasonRenderer.render(raw,null,en))
    }

    @Test fun customReasonWithRecognizedPrefixMustRemainIntact() {
        val raw = "Ảnh không đúng ứng dụng ở bước thanh toán; riêng màn đăng nhập đã đạt yêu cầu, hãy giữ lại."
        assertEquals(raw, RejectionReasonRenderer.render(raw,null,localizer("en")))
    }

    @Test fun oldApprovedReportMustNotTurnIntoNewReport() {
        val notification = UserNotification(id="old-approved",type="report",
            title="Báo cáo kiểm thử được phê duyệt! 🎉",
            message="Báo cáo của bạn cho ứng dụng \"DemoApp\" đã được duyệt thành công! Bạn nhận thêm 2 Credit cho lượt test này. Cảm ơn bạn!",
            data=buildJsonObject { put("status","approved") })
        val en = localizer("en")
        val rendered = NotificationRenderer.renderMessage(notification,en)
        assertTrue("Approved legacy notification mislabeled: $rendered",rendered.lowercase().contains("approved"))
    }

    @Test fun oldApprovedReportTitleMustTranslate() {
        val notification = UserNotification(id="old-approved",type="report",title="Báo cáo kiểm thử được phê duyệt! 🎉",
            data=buildJsonObject { put("status","approved") })
        assertNotEquals(notification.title,NotificationRenderer.renderTitle(notification,localizer("en")))
    }

    @Test fun parameterizedSuccessMustChangeLocale() {
        val en = localizer("en")
        val state = AppUpdateReleaseUiState(successMessage=en.get(R.string.app_update_close_success_format,42))
        val vi = localizer("vi")
        assertEquals(vi.get(R.string.app_update_close_success_format,42),state.successMessage)
    }

    @Test fun unlistedFixedErrorMustChangeLocale() {
        val en = localizer("en")
        val state = AppUpdateReleaseUiState(error=en.get(R.string.app_update_close_failed))
        val vi = localizer("vi")
        assertEquals(vi.get(R.string.app_update_close_failed),state.error)
    }

    @Test fun ownerPersistedMessageMustChangeLocale() {
        val en = localizer("en")
        val state = OwnerAppDetailUiState(error=en.get(R.string.error_network))
        val vi = localizer("vi")
        assertEquals(vi.get(R.string.error_network),state.error)
    }

    /** Reproduces the exact locale source used by MainActivity, without launching its authenticated UI. */
    @Test fun systemChoiceMustNotUsePreviouslyWrappedActivityConfiguration() {
        localizer("en")
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("testcheo_settings_sync",Context.MODE_PRIVATE).edit().putString("app_language","vi").commit()
        val wrapped = SettingsRepository.wrap(app)
        val activityLocale = wrapped.resources.configuration.locales[0]
        val actual = SettingsRepository.resolveLocale("system",activityLocale)
        assertEquals("Device EN and a previously wrapped VI context must resolve EN",Locale.ENGLISH.language,actual.language)
    }

    @Test fun structuredAppNameMustDecodeJsonString() {
        val name = "Demo \"A\""
        val notification = UserNotification(id="escaped",type="report",data=buildJsonObject {
            put("event_code","new_report");put("app_name",name)
        })
        val text = NotificationRenderer.renderMessage(notification,localizer("en"))
        assertTrue("JSON escape sequences leaked into app name: $text",text.contains(name))
    }

    @Test fun permissionErrorMustNotRetryUsingLegacyMutation() = runBlocking {
        var calls=0
        val client=createSupabaseClient("https://fixture.invalid","fixture-key") {
            install(Postgrest)
            httpEngine=MockEngine {
                calls++
                if(calls==1) respond("""{"code":"42501","message":"permission denied","details":null,"hint":null}""",
                    HttpStatusCode.Forbidden,headersOf(HttpHeaders.ContentType,"application/json"))
                else respond("""{"id":"android_client"}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            }
        }
        try {
            val result=runCatching { AppConfigRepository(client).updateAppConfig(1,2,"0.0.2",false,null,null,null,
                titleVi="Tiêu đề VI",titleEn="English title") }
            assertTrue("Permission failure was hidden by a second legacy update; calls=$calls",result.isFailure)
            assertEquals(1,calls)
        } finally { client.close() }
    }

    @Test fun legacyFallbackMustNotReportBilingualSaveSuccess() = runBlocking {
        var calls=0
        val client=createSupabaseClient("https://fixture.invalid","fixture-key") {
            install(Postgrest)
            httpEngine=MockEngine {
                calls++
                if(calls==1) respond("""{"code":"PGRST202","message":"function not found","details":null,"hint":null}""",
                    HttpStatusCode.NotFound,headersOf(HttpHeaders.ContentType,"application/json"))
                else respond("""{"id":"android_client","title":"Tiêu đề VI","message":"Nội dung VI"}""",
                    HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            }
        }
        try {
            val result=runCatching { AppConfigRepository(client).updateAppConfig(1,2,"0.0.2",false,null,null,null,
                titleVi="Tiêu đề VI",titleEn="English title",messageVi="Nội dung VI",messageEn="English content") }
            assertTrue("Cannot report full bilingual save after silently dropping EN",result.isFailure ||
                (result.getOrNull()?.titleEn=="English title" && result.getOrNull()?.messageEn=="English content"))
        } finally { client.close() }
    }
}
