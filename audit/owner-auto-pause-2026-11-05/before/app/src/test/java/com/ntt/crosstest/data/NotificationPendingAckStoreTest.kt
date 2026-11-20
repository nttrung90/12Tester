package com.ntt.crosstest.data

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class NotificationPendingAckStoreTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun records_and_retrieves_pending_ack_durable_across_store_instances() {
        val store1 = NotificationPendingAckStore(context)
        val uid = "user-123"
        val notifId = "notif-abc"

        assertFalse(store1.isPendingAck(uid, notifId))

        val saved = store1.recordPendingAck(uid, notifId)
        assertTrue(saved)
        assertTrue(store1.isPendingAck(uid, notifId))
        assertEquals(setOf(notifId), store1.getPendingAcksForUser(uid))

        // New instance reading from same SharedPreferences
        val store2 = NotificationPendingAckStore(context)
        assertTrue(store2.isPendingAck(uid, notifId))
        assertEquals(setOf(notifId), store2.getPendingAcksForUser(uid))

        // Remove
        val removed = store2.removePendingAck(uid, notifId)
        assertTrue(removed)
        assertFalse(store2.isPendingAck(uid, notifId))
        assertTrue(store2.getPendingAcksForUser(uid).isEmpty())
    }

    @Test
    fun isolates_pending_acks_between_different_users() {
        val store = NotificationPendingAckStore(context)
        val userA = "user-A"
        val userB = "user-B"
        val notifId = "notif-shared-id"

        store.recordPendingAck(userA, notifId)
        assertTrue(store.isPendingAck(userA, notifId))
        assertFalse(store.isPendingAck(userB, notifId))

        assertEquals(setOf(notifId), store.getPendingAcksForUser(userA))
        assertTrue(store.getPendingAcksForUser(userB).isEmpty())

        store.clearUser(userA)
        assertFalse(store.isPendingAck(userA, notifId))
    }
}
