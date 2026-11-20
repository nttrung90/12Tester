package com.ntt.crosstest.data

import android.content.Context
import android.content.SharedPreferences

interface PendingAckStore {
    fun recordPendingAck(userId: String, notificationId: String): Boolean
    fun isPendingAck(userId: String, notificationId: String): Boolean
    fun getPendingAcksForUser(userId: String): Set<String>
    fun removePendingAck(userId: String, notificationId: String): Boolean
    fun clearUser(userId: String)
}

class NotificationPendingAckStore(
    context: Context? = null,
    private val prefs: SharedPreferences? = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
) : PendingAckStore {

    private val inMemoryFallback = mutableMapOf<String, MutableSet<String>>()
    private val lock = Any()

    override fun recordPendingAck(userId: String, notificationId: String): Boolean {
        if (userId.isBlank() || notificationId.isBlank()) return false
        synchronized(lock) {
            val key = makeKey(userId, notificationId)
            val success = prefs?.edit()?.putBoolean(key, true)?.commit() ?: true
            if (success) {
                inMemoryFallback.getOrPut(userId) { mutableSetOf() }.add(notificationId)
            }
            return success
        }
    }

    override fun isPendingAck(userId: String, notificationId: String): Boolean {
        if (userId.isBlank() || notificationId.isBlank()) return false
        synchronized(lock) {
            if (prefs != null) {
                val key = makeKey(userId, notificationId)
                return prefs.getBoolean(key, false)
            }
            return inMemoryFallback[userId]?.contains(notificationId) == true
        }
    }

    override fun getPendingAcksForUser(userId: String): Set<String> {
        if (userId.isBlank()) return emptySet()
        synchronized(lock) {
            if (prefs != null) {
                val prefix = "$userId|"
                val result = mutableSetOf<String>()
                val all = prefs.all
                for ((k, v) in all) {
                    if (k.startsWith(prefix) && v == true) {
                        result.add(k.substring(prefix.length))
                    }
                }
                return result
            }
            return inMemoryFallback[userId]?.toSet() ?: emptySet()
        }
    }

    override fun removePendingAck(userId: String, notificationId: String): Boolean {
        if (userId.isBlank() || notificationId.isBlank()) return false
        synchronized(lock) {
            val key = makeKey(userId, notificationId)
            val success = prefs?.edit()?.remove(key)?.commit() ?: true
            if (success) {
                inMemoryFallback[userId]?.remove(notificationId)
            }
            return success
        }
    }

    override fun clearUser(userId: String) {
        if (userId.isBlank()) return
        synchronized(lock) {
            if (prefs != null) {
                val prefix = "$userId|"
                val editor = prefs.edit()
                for (k in prefs.all.keys) {
                    if (k.startsWith(prefix)) {
                        editor.remove(k)
                    }
                }
                editor.commit()
            }
            inMemoryFallback.remove(userId)
        }
    }

    private fun makeKey(userId: String, notificationId: String): String = "$userId|$notificationId"

    companion object {
        private const val PREFS_NAME = "admin_notification_pending_acks"
    }
}
