package com.ntt.crosstest.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FinancialIntentStoreTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        FinancialIntentStore.clearAllSlotIntentsForUser(context, "user-A")
        FinancialIntentStore.clearAllSlotIntentsForUser(context, "user-B")
        FinancialIntentStore.clearAllPublishIntentsForUser(context, "user-A")
        FinancialIntentStore.clearAllPublishIntentsForUser(context, "user-B")
    }

    @Test
    fun slotIntent_persistsAndClearsCorrectly() {
        val intent = PendingSlotIntent(
            userId = "user-A",
            slotNumber = 3,
            expectedCost = 120,
            expectedRevision = "slot-rev-token-45",
            renewalDays = 45,
            clientRequestId = "req-slot-uuid-123",
        )

        FinancialIntentStore.saveSlotIntent(context, intent)

        val retrieved = FinancialIntentStore.peekSlotIntent(context, "user-A", 3)
        assertNotNull(retrieved)
        assertEquals("user-A", retrieved!!.userId)
        assertEquals(3, retrieved.slotNumber)
        assertEquals(120, retrieved.expectedCost)
        assertEquals("slot-rev-token-45", retrieved.expectedRevision)
        assertEquals(45, retrieved.renewalDays)
        assertEquals("req-slot-uuid-123", retrieved.clientRequestId)

        // Clear removes from store
        FinancialIntentStore.clearSlotIntent(context, "user-A", 3)
        assertNull(FinancialIntentStore.peekSlotIntent(context, "user-A", 3))
    }

    @Test
    fun publishIntent_preservesExactNullCurrentReleaseId() {
        val intent = PendingPublishUpdateIntent(
            userId = "user-A",
            campaignId = "camp-1",
            versionName = "2.0.0",
            targetVersionCode = 20L,
            releaseNotes = "Notes",
            expectedCurrentReleaseId = null, // Exact null
            expectedBudget = 200,
            expectedRevision = "rev-update-200",
            clientOperationId = "op-uuid-456",
        )

        FinancialIntentStore.savePublishIntent(context, intent)

        val retrieved = FinancialIntentStore.peekPublishIntent(context, "user-A", "camp-1")
        assertNotNull(retrieved)
        assertEquals("user-A", retrieved!!.userId)
        assertEquals("camp-1", retrieved.campaignId)
        assertEquals("2.0.0", retrieved.versionName)
        assertEquals(20L, retrieved.targetVersionCode)
        assertNull(retrieved.expectedCurrentReleaseId)
        assertEquals(200, retrieved.expectedBudget)
        assertEquals("rev-update-200", retrieved.expectedRevision)
        assertEquals("op-uuid-456", retrieved.clientOperationId)

        FinancialIntentStore.clearPublishIntent(context, "user-A", "camp-1")
        assertNull(FinancialIntentStore.peekPublishIntent(context, "user-A", "camp-1"))
    }

    @Test
    fun userIsolation_guaranteesUserBCannotSeeUserAIntents() {
        val intentA = PendingSlotIntent(
            userId = "user-A",
            slotNumber = 2,
            expectedCost = 50,
            expectedRevision = "rev-A",
            renewalDays = 30,
            clientRequestId = "uuid-A",
        )
        FinancialIntentStore.saveSlotIntent(context, intentA)

        val intentPubA = PendingPublishUpdateIntent(
            userId = "user-A",
            campaignId = "camp-A",
            versionName = "1.0.0",
            targetVersionCode = 10L,
            releaseNotes = "Notes",
            expectedCurrentReleaseId = null,
            expectedBudget = 120,
            expectedRevision = "rev-pub-A",
            clientOperationId = "pub-uuid-A",
        )
        FinancialIntentStore.savePublishIntent(context, intentPubA)

        // User B queries must return null
        assertNull(FinancialIntentStore.peekSlotIntent(context, "user-B", 2))
        assertNull(FinancialIntentStore.peekPublishIntent(context, "user-B", "camp-A"))
    }
}
