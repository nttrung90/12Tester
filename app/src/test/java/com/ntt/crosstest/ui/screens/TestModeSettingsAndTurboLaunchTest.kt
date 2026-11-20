package com.ntt.crosstest.ui.screens

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import androidx.lifecycle.ViewModelStore
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.EgressOptimizationConfig
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.PendingReportStore
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.SettingsRepository
import com.ntt.crosstest.ui.navigation.Routes
import com.ntt.crosstest.ui.viewmodels.CampaignDetailViewModel
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TestModeSettingsAndTurboLaunchTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val testDispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val clients = mutableListOf<SupabaseClient>()
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        PendingReportStore.clear(context)
        SessionCacheManager.clear(context)
        EgressOptimizationConfig.resetCapabilityCache()
        context.getSharedPreferences("testcheo_settings_sync", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() = runBlocking {
        store.clear()
        PendingReportStore.clear(context)
        SessionCacheManager.clear(context)
        EgressOptimizationConfig.resetCapabilityCache()
        clients.forEach { it.close() }
        clients.clear()
        Dispatchers.resetMain()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun createMockViewModel(): CampaignDetailViewModel {
        val client = createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                enableLifecycleCallbacks = false
                autoLoadFromStorage = false
                autoSaveToStorage = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
            }
            install(Postgrest)
            httpEngine = MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/get_campaign_detail") || path.endsWith("/get_campaign_detail_sync_v2") -> {
                        respond(
                            """{
                                "campaign":{"id":"camp-next","owner_id":"owner-1","app_name":"Next App","package_name":"com.example.nextapp","status":"open","target_testers":12},
                                "is_member":false,
                                "is_actual_owner":false,
                                "can_manage":false,
                                "my_profile":{"id":"tester-1","role":"member","credits":100},
                                "reports":[],
                                "testers":[],
                                "feedbacks":[],
                                "checked_in_confirmed_today":false
                            }""",
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                    else -> respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        clients += client
        runBlocking {
            withTimeout(5000) { client.auth.awaitInitialization() }
            client.auth.importSession(
                json.decodeFromString<UserSession>(
                    """{"access_token":"fixture-t1","refresh_token":"fixture-ref-t1","expires_in":3600,"token_type":"bearer","user":{"id":"tester-1","aud":"authenticated"}}"""
                )
            )
        }
        val repo = CampaignRepository(client, Localizer(context))
        val vm = CampaignDetailViewModel(
            campaignId = "camp-next",
            repository = repo,
            reportSubmitManager = ReportSubmitManager(context),
            myUserId = "tester-1",
            localizer = Localizer(context),
            appContext = context,
        )
        store.put("vm_" + System.nanoTime(), vm)
        testDispatcher.scheduler.runCurrent()
        return vm
    }

    @Test
    fun settingsRepository_testModeDefaultsToNormalAndPersistsTurbo() = runBlocking {
        val settings = SettingsRepository(context)

        assertEquals(SettingsRepository.TEST_MODE_NORMAL, SettingsRepository.getSavedTestModeSync(context))
        assertEquals(SettingsRepository.TEST_MODE_NORMAL, settings.testMode.first())

        settings.setTestMode(SettingsRepository.TEST_MODE_TURBO)
        assertEquals(SettingsRepository.TEST_MODE_TURBO, SettingsRepository.getSavedTestModeSync(context))
        assertEquals(SettingsRepository.TEST_MODE_TURBO, settings.testMode.first())

        settings.setTestMode("invalid-mode")
        assertEquals(SettingsRepository.TEST_MODE_NORMAL, SettingsRepository.getSavedTestModeSync(context))
        assertEquals(SettingsRepository.TEST_MODE_NORMAL, settings.testMode.first())

        assertEquals(SettingsRepository.TEST_MODE_TURBO, SettingsRepository.normalizeTestMode(" TURBO "))
        assertEquals(SettingsRepository.TEST_MODE_NORMAL, SettingsRepository.normalizeTestMode(null))
    }

    @Test
    fun localizedTestSettingsStrings_matchExpectedVietnameseAndEnglishLabels() {
        context.getSharedPreferences("testcheo_settings_sync", Context.MODE_PRIVATE)
            .edit()
            .putString("app_language", "vi")
            .commit()
        val viLocalizer = Localizer(context)
        assertEquals("Cài đặt Test", viLocalizer.get(R.string.settings_title))
        assertEquals("Cài đặt Test", viLocalizer.get(R.string.dialog_select_test_mode))
        assertEquals("Test bình thường", viLocalizer.get(R.string.test_mode_normal))
        assertEquals("Test siêu tốc", viLocalizer.get(R.string.test_mode_turbo))

        context.getSharedPreferences("testcheo_settings_sync", Context.MODE_PRIVATE)
            .edit()
            .putString("app_language", "en")
            .commit()
        val enLocalizer = Localizer(context)
        assertEquals("Test Settings", enLocalizer.get(R.string.settings_title))
        assertEquals("Normal test", enLocalizer.get(R.string.test_mode_normal))
        assertEquals("Turbo test", enLocalizer.get(R.string.test_mode_turbo))
    }

    @Test
    fun routes_campaignDetail_formatsAutoLaunchParameterCleanly() {
        assertEquals("campaign/camp-1", Routes.campaignDetail("camp-1"))
        assertEquals("campaign/camp-1?autoLaunch=true", Routes.campaignDetail("camp-1", autoLaunch = true))
        assertEquals(
            "campaign/camp-1?missionId=m-2&autoLaunch=true",
            Routes.campaignDetail("camp-1", missionId = "m-2", autoLaunch = true),
        )
    }

    @Test
    fun launchOrInstallCampaignApp_whenInstalled_opensInstalledAppAndRecordsTestStart() {
        val pkg = "com.example.installedapp"
        val shadowPm = shadowOf(context.packageManager)
        val pkgInfo = PackageInfo().apply { packageName = pkg }
        shadowPm.installPackage(pkgInfo)

        val component = ComponentName(pkg, "$pkg.MainActivity")
        val launcherFilter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        shadowPm.addActivityIfNotPresent(component)
        shadowPm.addIntentFilterForActivity(component, launcherFilter)

        val vm = createMockViewModel()
        val campaign = Campaign(
            id = "camp-next",
            ownerId = "owner-1",
            appName = "Installed App",
            packageName = pkg,
            playUrl = "https://play.google.com/store/apps/details?id=$pkg",
            testingUrl = "https://play.google.com/apps/testing/$pkg",
            status = "open",
        )

        launchOrInstallCampaignApp(
            context = context,
            campaign = campaign,
            isOwner = false,
            viewModel = vm,
        )
        testDispatcher.scheduler.runCurrent()

        val started = shadowOf(context).nextStartedActivity
        assertNotNull("Installed app must launch directly", started)
        assertEquals(pkg, started.component?.packageName ?: started.`package`)
        assertNotNull("Test start timestamp must be recorded", vm.uiState.value.testStartedAtMs)
    }

    @Test
    fun launchOrInstallCampaignApp_whenNotInstalled_opensPlayStoreToInstallAndRecordsTestStart() {
        val pkg = "com.example.notinstalledapp"
        val vm = createMockViewModel()
        val campaign = Campaign(
            id = "camp-next",
            ownerId = "owner-1",
            appName = "Uninstalled App",
            packageName = pkg,
            playUrl = "https://play.google.com/store/apps/details?id=$pkg",
            testingUrl = "https://play.google.com/apps/testing/$pkg",
            status = "open",
        )

        launchOrInstallCampaignApp(
            context = context,
            campaign = campaign,
            isOwner = false,
            viewModel = vm,
        )
        testDispatcher.scheduler.runCurrent()

        val started = shadowOf(context).nextStartedActivity
        assertNotNull("Uninstalled app must open download/install flow", started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("market://details?id=$pkg", started.dataString)
        assertEquals("com.android.vending", started.`package`)
        assertTrue((started.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertNotNull("Test start timestamp must be recorded", vm.uiState.value.testStartedAtMs)
    }
}
