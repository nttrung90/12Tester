package com.ntt.crosstest.util

import android.app.Application
import android.content.Context
import com.ntt.crosstest.data.AdminCampaignItem
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SessionCacheManager
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PlayStoreHelperProductionDetectionTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val json = Json { ignoreUnknownKeys = true }

    @After
    fun tearDown() {
        SessionCacheManager.clear(context)
    }

    private fun buildPlayHtml(
        title: String = "Test App",
        description: String = "Helpful app description",
        price: String? = "0",
        availability: String? = "https://schema.org/InStock",
        includeOffers: Boolean = true,
        availCode: Int? = 2,
        released: String? = "Oct 31, 2026",
        includeDs5: Boolean = true,
        extraBodyHtml: String = "",
        includeRating: Boolean = false,
    ): String {
        val offersJson = if (includeOffers) {
            val availPart = availability?.let { ""","availability":"$it"""" }.orEmpty()
            val pricePart = price?.let { ""","price":"$it","priceCurrency":"USD"""" }.orEmpty()
            ""","offers":{"@type":"Offer"$pricePart$availPart}"""
        } else {
            ""
        }
        val ratingJson = if (includeRating) {
            ""","aggregateRating":{"@type":"AggregateRating","ratingValue":"4.6","ratingCount":"120"}"""
        } else {
            ""
        }
        val jsonLd = """
            <script type="application/ld+json">
            {
              "@context": "https://schema.org",
              "@type": "SoftwareApplication",
              "name": "$title",
              "description": "$description",
              "image": "https://play-lh.googleusercontent.com/icon-128"
              $offersJson
              $ratingJson
            }
            </script>
        """.trimIndent()

        val ds5Script = if (includeDs5) {
            val releasedArray = if (released != null) """["$released", [1793404800]]""" else "null"
            val availArray = if (availCode != null) "[$availCode]" else "null"
            // ds5[1][2][10][0] = released, ds5[1][2][18][0] = availCode
            """
                <script>
                AF_initDataCallback({
                  key: 'ds:5',
                  hash: '4',
                  data: [
                    null,
                    [
                      null,
                      null,
                      [
                        "$title", null, null, null, null,
                        null, null, null, null, null,
                        $releasedArray,
                        null, null, null, null,
                        null, null, null,
                        $availArray
                      ]
                    ]
                  ],
                  sideChannel: {}
                });
                </script>
            """.trimIndent()
        } else {
            ""
        }

        return """
            <!doctype html>
            <html>
              <head>
                <title>$title - Apps on Google Play</title>
                <meta property="og:title" content="$title - Apps on Google Play" />
                <meta property="og:image" content="https://play-lh.googleusercontent.com/icon-og" />
                <meta name="description" content="$description" />
                $jsonLd
                $ds5Script
              </head>
              <body>
                <h1>$title</h1>
                <div data-g-id="description">$description</div>
                $extraBodyHtml
              </body>
            </html>
        """.trimIndent()
    }

    // ========================================================================
    // 1. parsePlayStoreHtml CASES
    // ========================================================================

    @Test
    fun parsePlayStoreHtml_productionFreeZeroRating_detectedAsProduction() {
        val html = buildPlayHtml(
            title = "Zero Rating Prod",
            price = "0",
            availability = "https://schema.org/InStock",
            availCode = 2,
            released = "Oct 31, 2026",
            includeRating = false,
        )
        val parsed = PlayStoreHelper.parsePlayStoreHtml(html)
        assertEquals(PlayStoreReleaseState.PRODUCTION, parsed.releaseState)
        assertEquals("Oct 31, 2026", parsed.releasedDate)
        assertEquals("Zero Rating Prod", parsed.appName)
        assertEquals("https://play-lh.googleusercontent.com/icon-og", parsed.iconUrl)
    }

    @Test
    fun parsePlayStoreHtml_productionPaid_detectedAsProduction() {
        val html = buildPlayHtml(
            title = "Pro Paid Utility",
            price = "4.99",
            availability = "https://schema.org/InStock",
            availCode = 2,
            released = "Jan 15, 2025",
        )
        val parsed = PlayStoreHelper.parsePlayStoreHtml(html)
        assertEquals(PlayStoreReleaseState.PRODUCTION, parsed.releaseState)
        assertEquals("Jan 15, 2025", parsed.releasedDate)
    }

    @Test
    fun parsePlayStoreHtml_productionVnGeoRestricted_usPageNotPromoted_vnPageDetected() {
        // US store page when app is restricted to VN: availCode != 2 and no released date
        val usGeoRestrictedHtml = buildPlayHtml(
            title = "VN Local Bank App",
            includeOffers = true,
            availability = "https://schema.org/InStock",
            availCode = 3,
            released = null,
        )
        val usParsed = PlayStoreHelper.parsePlayStoreHtml(usGeoRestrictedHtml)
        assertEquals(PlayStoreReleaseState.UNKNOWN, usParsed.releaseState)

        // VN store page returns availCode = 2 and released date
        val vnHtml = buildPlayHtml(
            title = "VN Local Bank App",
            includeOffers = true,
            availability = "https://schema.org/InStock",
            availCode = 2,
            released = "Nov 1, 2026",
        )
        val vnParsed = PlayStoreHelper.parsePlayStoreHtml(vnHtml)
        assertEquals(PlayStoreReleaseState.PRODUCTION, vnParsed.releaseState)
        assertEquals("Nov 1, 2026", vnParsed.releasedDate)
    }

    @Test
    fun parsePlayStoreHtml_preRegistration_detectedViaMissingOffersOrAvailCode1() {
        val noOffersHtml = buildPlayHtml(
            title = "Upcoming RPG",
            includeOffers = false,
            availCode = null,
            released = null,
        )
        assertEquals(PlayStoreReleaseState.PRE_REGISTRATION, PlayStoreHelper.parsePlayStoreHtml(noOffersHtml).releaseState)

        val availCode1Html = buildPlayHtml(
            title = "Upcoming RPG",
            includeOffers = true,
            availability = "https://schema.org/PreOrder",
            availCode = 1,
            released = null,
        )
        assertEquals(PlayStoreReleaseState.PRE_REGISTRATION, PlayStoreHelper.parsePlayStoreHtml(availCode1Html).releaseState)
    }

    @Test
    fun parsePlayStoreHtml_openTesting_detectedViaSystemMarkersOrNullReleased() {
        val systemBannerHtml = buildPlayHtml(
            title = "Beta Utility",
            includeOffers = true,
            availability = "https://schema.org/InStock",
            availCode = 2,
            released = null,
            extraBodyHtml = "<div>This app is in development. Private feedback to developer.</div>",
        )
        val parsedBanner = PlayStoreHelper.parsePlayStoreHtml(systemBannerHtml)
        assertEquals(PlayStoreReleaseState.OPEN_TESTING, parsedBanner.releaseState)
        assertNull(parsedBanner.releasedDate)

        val nullReleasedDs5Html = buildPlayHtml(
            title = "Open Track App",
            includeOffers = true,
            availability = "https://schema.org/InStock",
            availCode = 2,
            released = null,
        )
        assertEquals(PlayStoreReleaseState.OPEN_TESTING, PlayStoreHelper.parsePlayStoreHtml(nullReleasedDs5Html).releaseState)
    }

    @Test
    fun parsePlayStoreHtml_productionWithEarlyAccessInTitleAndDescription_notMisclassified() {
        val html = buildPlayHtml(
            title = "Focus Timer - Early access",
            description = "Get early access to all productivity widgets in our live release!",
            includeOffers = true,
            availability = "https://schema.org/InStock",
            availCode = 2,
            released = "Aug 10, 2026",
        )
        val parsed = PlayStoreHelper.parsePlayStoreHtml(html)
        assertEquals(
            "Dev title/description containing 'Early access' must NOT trigger OPEN_TESTING",
            PlayStoreReleaseState.PRODUCTION,
            parsed.releaseState,
        )
        assertEquals("Aug 10, 2026", parsed.releasedDate)
    }

    @Test
    fun parsePlayStoreHtml_productionWithEarlyAccessInReviewOrFullDesc_vsSystemEarlyAccessBanner() {
        val reviewAndLongDescHtml = buildPlayHtml(
            title = "Habit Tracker",
            description = "Track your daily habits easily.",
            includeOffers = true,
            availability = "https://schema.org/InStock",
            availCode = 2,
            released = "Sep 12, 2026",
            extraBodyHtml = """
                <div data-g-id="description">Track your daily habits easily. Previously tested in early access, now live!</div>
                <section aria-label="User reviews"><div>Loved this app since early access!</div></section>
            """.trimIndent(),
        )
        val parsedProd = PlayStoreHelper.parsePlayStoreHtml(reviewAndLongDescHtml)
        assertEquals(
            "User review or full description mentioning 'early access' must NOT misclassify Production app",
            PlayStoreReleaseState.PRODUCTION,
            parsedProd.releaseState,
        )
        assertEquals("Sep 12, 2026", parsedProd.releasedDate)

        val systemEarlyAccessHtml = buildPlayHtml(
            title = "Experimental Launcher",
            description = "Fast Android launcher.",
            includeOffers = true,
            availability = "https://schema.org/InStock",
            availCode = 2,
            released = "Sep 12, 2026",
            extraBodyHtml = "<div>App is currently in early access. It may be unstable.</div>",
        )
        val parsedOpenTesting = PlayStoreHelper.parsePlayStoreHtml(systemEarlyAccessHtml)
        assertEquals(PlayStoreReleaseState.OPEN_TESTING, parsedOpenTesting.releaseState)
        assertNull(parsedOpenTesting.releasedDate)
    }

    @Test
    fun parsePlayStoreHtml_malformedOrCaptchaHtml_returnsUnknown() {
        val captchaHtml = """
            <html>
              <head><title>Sorry... Google</title></head>
              <body>Our systems have detected unusual traffic from your computer network.</body>
            </html>
        """.trimIndent()
        assertEquals(PlayStoreReleaseState.UNKNOWN, PlayStoreHelper.parsePlayStoreHtml(captchaHtml).releaseState)

        val brokenJsonLd = """
            <html><head><script type="application/ld+json">{ broken json !!!</script></head><body></body></html>
        """.trimIndent()
        assertEquals(PlayStoreReleaseState.UNKNOWN, PlayStoreHelper.parsePlayStoreHtml(brokenJsonLd).releaseState)
    }

    // ========================================================================
    // 2. BACKWARD-COMPATIBLE MODEL DESERIALIZATION
    // ========================================================================

    @Test
    fun campaignDeserialization_legacyPayloadDefaultsSafely_andRespectsSingleSourceOfTruth() {
        val legacyJson = """
            {
              "id": "camp-legacy",
              "app_name": "Legacy App",
              "package_name": "com.example.legacy",
              "status": "open"
            }
        """.trimIndent()
        val legacy = json.decodeFromString<Campaign>(legacyJson)
        assertEquals("unknown", legacy.playStoreState)
        assertFalse(legacy.playProductionDetected)
        assertFalse(legacy.isPlayProductionDetected)
        assertNull(legacy.playReleasedDate)
        assertNull(legacy.playCheckedAt)
        assertFalse(legacy.playClassificationLocked)

        // play_store_state == "production" while play_production_detected == false (hysteresis / admin override)
        val unconfirmedJson = """
            {
              "id": "camp-hysteresis",
              "app_name": "Hysteresis App",
              "package_name": "com.example.hysteresis",
              "status": "open",
              "play_store_state": "production",
              "play_production_detected": false,
              "play_released_date": "Oct 31, 2026",
              "play_classification_locked": true
            }
        """.trimIndent()
        val unconfirmed = json.decodeFromString<Campaign>(unconfirmedJson)
        assertEquals("production", unconfirmed.playStoreState)
        assertFalse("isPlayProductionDetected must rely ONLY on playProductionDetected", unconfirmed.isPlayProductionDetected)
        assertTrue(unconfirmed.playClassificationLocked)

        val adminLegacy = json.decodeFromString<AdminCampaignItem>(
            """{"id":"c1","app_name":"A","package_name":"com.a","status":"open"}"""
        )
        assertEquals("unknown", adminLegacy.playStoreState)
        assertFalse(adminLegacy.playProductionDetected)
        assertFalse(adminLegacy.playClassificationLocked)
    }

    // ========================================================================
    // 3. CampaignRepository.queryCampaignsSafe MISSING COLUMN FALLBACK
    // ========================================================================

    @Test
    fun queryCampaignsSafe_missingPlayColumns_fallsBackToBonusThenLegacy() = runBlocking {
        val requestedSelects = mutableListOf<String>()
        val client = createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                enableLifecycleCallbacks = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine { request ->
                val selectParam = request.url.parameters["select"].orEmpty()
                requestedSelects += selectParam
                when {
                    selectParam.contains("play_store_state") -> respond(
                        content = """{"code":"PGRST204","message":"Could not find the 'play_store_state' column of 'campaigns' in the schema cache"}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    selectParam.contains("tester_bonus_round_id") -> respond(
                        content = """{"code":"42703","message":"column campaigns.tester_bonus_round_id does not exist"}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    else -> respond(
                        content = """[{"id":"c-fallback","app_name":"Fallback App","package_name":"com.test.fallback","status":"open"}]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }

        val repo = CampaignRepository(client, Localizer(context))
        val campaigns = repo.listOpenCampaigns(forceRefresh = true)

        assertEquals(1, campaigns.size)
        assertEquals("c-fallback", campaigns.first().id)
        assertFalse(campaigns.first().isPlayProductionDetected)
        assertFalse(repo.playColumnsSupported)
        assertFalse(repo.bonusColumnsSupported)
        assertEquals(3, requestedSelects.size)
        assertTrue(requestedSelects[0].contains("play_store_state"))
        assertFalse(requestedSelects[1].contains("play_store_state"))
        assertTrue(requestedSelects[1].contains("tester_bonus_round_id"))
        assertFalse(requestedSelects[2].contains("tester_bonus_round_id"))

        // Next call immediately uses LEGACY_CAMPAIGN_COLUMNS without hitting 400 again
        val callCountBefore = requestedSelects.size
        val campaignsSecond = repo.listOpenCampaigns(forceRefresh = true)
        assertEquals(1, campaignsSecond.size)
        assertEquals(callCountBefore + 1, requestedSelects.size)
        assertFalse(requestedSelects.last().contains("play_store_state"))
        assertFalse(requestedSelects.last().contains("tester_bonus_round_id"))
    }

    @Test
    fun queryCampaignsSafe_pre102WithBonusSupported_stopsAtBonusColumns() = runBlocking {
        val attempt = AtomicInteger(0)
        val requestedSelects = mutableListOf<String>()
        val client = createSupabaseClient("https://fixture.invalid", "dummy-key") {
            install(Auth) {
                enableLifecycleCallbacks = false
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                autoSaveToStorage = false
            }
            install(Postgrest)
            httpEngine = MockEngine { request ->
                attempt.incrementAndGet()
                val selectParam = request.url.parameters["select"].orEmpty()
                requestedSelects += selectParam
                if (selectParam.contains("play_production_detected")) {
                    respond(
                        content = """{"code":"42703","message":"column campaigns.play_production_detected does not exist"}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content = """[{"id":"c-bonus","app_name":"Bonus App","package_name":"com.test.bonus","status":"open","tester_bonus_credits":5}]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }

        val repo = CampaignRepository(client, Localizer(context))
        val campaigns = repo.listOpenCampaigns(forceRefresh = true)
        assertNotNull(campaigns.firstOrNull())
        assertEquals(5, campaigns.first().testerBonusCredits)
        assertFalse(repo.playColumnsSupported)
        assertTrue(repo.bonusColumnsSupported)
        assertEquals(2, attempt.get())
        assertTrue(requestedSelects[1].contains("tester_bonus_round_id"))
    }
}
