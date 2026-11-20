package com.ntt.crosstest.data

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class CampaignTesterBonusStoreTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val userId = "user-test-123"
    private val otherUserId = "user-test-456"
    private val campaignId = "camp-abc-789"
    private val otherCampaignId = "camp-xyz-999"

    @Before
    fun setUp() {
        val prefs = context.getSharedPreferences("crosstest_campaign_tester_bonus_requests", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun `peekPendingIntent returns null when store is empty`() {
        val state = CampaignTesterBonusStore.peekState(context, userId, campaignId)
        assertTrue(state is TesterBonusStoreState.None)

        val intent = CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId)
        assertNull(intent)
    }

    @Test
    fun `getOrCreatePendingIntent creates and persists new intent when empty`() {
        val intent = CampaignTesterBonusStore.getOrCreatePendingIntent(
            context = context,
            userId = userId,
            campaignId = campaignId,
            rewardCredits = 5,
            maxRecipients = 10,
        )

        assertNotNull(intent.clientRequestId)
        assertEquals(campaignId, intent.campaignId)
        assertEquals(userId, intent.userId)
        assertEquals(5, intent.rewardCredits)
        assertEquals(10, intent.maxRecipients)
        assertTrue(intent.createdAtMs > 0)

        // Verify state is Valid
        val state = CampaignTesterBonusStore.peekState(context, userId, campaignId)
        assertTrue(state is TesterBonusStoreState.Valid)
        assertEquals(intent.clientRequestId, (state as TesterBonusStoreState.Valid).intent.clientRequestId)
    }

    @Test
    fun `getOrCreatePendingIntent reuses existing intent and retains same UUID and payload`() {
        val first = CampaignTesterBonusStore.getOrCreatePendingIntent(
            context = context,
            userId = userId,
            campaignId = campaignId,
            rewardCredits = 5,
            maxRecipients = 10,
        )

        // Attempt to create again (e.g. timeout / process recreation / retry)
        val second = CampaignTesterBonusStore.getOrCreatePendingIntent(
            context = context,
            userId = userId,
            campaignId = campaignId,
            rewardCredits = 99, // Should NOT change payload of unresolved intent
            maxRecipients = 99,
        )

        assertEquals(first.clientRequestId, second.clientRequestId)
        assertEquals(first.userId, second.userId)
        assertEquals(first.rewardCredits, second.rewardCredits)
        assertEquals(first.maxRecipients, second.maxRecipients)
        assertEquals(first.createdAtMs, second.createdAtMs)
    }

    @Test
    fun `corrupted JSON throws TesterBonusStoreCorruptedStateException and preserves record on disk`() {
        val prefs = context.getSharedPreferences("crosstest_campaign_tester_bonus_requests", Context.MODE_PRIVATE)
        val key = "${userId}_$campaignId"
        prefs.edit().putString(key, "{ malformed: invalid_json ").commit()

        val state = CampaignTesterBonusStore.peekState(context, userId, campaignId)
        assertTrue(state is TesterBonusStoreState.Corrupted)

        try {
            CampaignTesterBonusStore.getOrCreatePendingIntent(
                context = context,
                userId = userId,
                campaignId = campaignId,
                rewardCredits = 5,
                maxRecipients = 10,
            )
            fail("Expected TesterBonusStoreCorruptedStateException on corrupted JSON")
        } catch (e: TesterBonusStoreCorruptedStateException) {
            assertTrue(e.raw.contains("malformed"))
        }

        try {
            CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId)
            fail("Expected TesterBonusStoreCorruptedStateException on peek")
        } catch (e: TesterBonusStoreCorruptedStateException) {
            assertTrue(e.raw.contains("malformed"))
        }

        // Must preserve raw content on disk and NOT overwrite with fresh UUID
        assertEquals("{ malformed: invalid_json ", prefs.getString(key, null))
    }

    @Test
    fun `clearPendingIntentIfMatching rejects stale or wrong clientRequestId`() {
        val intent = CampaignTesterBonusStore.getOrCreatePendingIntent(
            context = context,
            userId = userId,
            campaignId = campaignId,
            rewardCredits = 5,
            maxRecipients = 10,
        )

        // Attempt clear with a stale / wrong UUID
        val wrongId = "wrong-uuid-999"
        val cleared = CampaignTesterBonusStore.clearPendingIntentIfMatching(
            context = context,
            userId = userId,
            campaignId = campaignId,
            expectedClientRequestId = wrongId,
        )
        assertFalse(cleared)

        // Intent must still be intact
        val remaining = CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId)
        assertNotNull(remaining)
        assertEquals(intent.clientRequestId, remaining?.clientRequestId)

        // Clear with matching UUID succeeds
        val matchingCleared = CampaignTesterBonusStore.clearPendingIntentIfMatching(
            context = context,
            userId = userId,
            campaignId = campaignId,
            expectedClientRequestId = intent.clientRequestId,
        )
        assertTrue(matchingCleared)
        assertNull(CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId))
    }

    @Test
    fun `different users and campaigns are completely isolated`() {
        val intentUser1Camp1 = CampaignTesterBonusStore.getOrCreatePendingIntent(
            context, userId, campaignId, rewardCredits = 2, maxRecipients = 5,
        )
        val intentUser1Camp2 = CampaignTesterBonusStore.getOrCreatePendingIntent(
            context, userId, otherCampaignId, rewardCredits = 4, maxRecipients = 8,
        )
        val intentUser2Camp1 = CampaignTesterBonusStore.getOrCreatePendingIntent(
            context, otherUserId, campaignId, rewardCredits = 6, maxRecipients = 12,
        )

        // Check uniqueness and isolation
        assertEquals(2, intentUser1Camp1.rewardCredits)
        assertEquals(4, intentUser1Camp2.rewardCredits)
        assertEquals(6, intentUser2Camp1.rewardCredits)

        // Clearing user 1's camp 1 does not affect user 1's camp 2 or user 2's camp 1
        CampaignTesterBonusStore.clearPendingIntentIfMatching(context, userId, campaignId, intentUser1Camp1.clientRequestId)
        assertNull(CampaignTesterBonusStore.peekPendingIntent(context, userId, campaignId))
        assertNotNull(CampaignTesterBonusStore.peekPendingIntent(context, userId, otherCampaignId))
        assertNotNull(CampaignTesterBonusStore.peekPendingIntent(context, otherUserId, campaignId))
    }
}
