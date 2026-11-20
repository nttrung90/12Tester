package com.ntt.crosstest.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class ForegroundSnapshot(
    val isForeground: Boolean = false,
    val sessionId: String? = null,
)

object AppForegroundTracker : Application.ActivityLifecycleCallbacks {

    private var startedCount = 0
    private var configChange = false

    private val _foregroundSnapshot = MutableStateFlow(ForegroundSnapshot(isForeground = false, sessionId = null))
    val foregroundSnapshot: StateFlow<ForegroundSnapshot> = _foregroundSnapshot.asStateFlow()

    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val _foregroundSessionId = MutableStateFlow<String?>(null)
    val foregroundSessionId: StateFlow<String?> = _foregroundSessionId.asStateFlow()

    fun init(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        if (startedCount == 0 && !configChange) {
            // Truly transitioning from background to foreground:
            // ALWAYS update session ID before publishing isForeground=true to prevent race conditions.
            val newSessionId = UUID.randomUUID().toString()
            _foregroundSessionId.value = newSessionId
            _isForeground.value = true
            _foregroundSnapshot.value = ForegroundSnapshot(isForeground = true, sessionId = newSessionId)
        }
        startedCount++
        configChange = false
    }

    override fun onActivityStopped(activity: Activity) {
        configChange = activity.isChangingConfigurations
        startedCount--
        if (startedCount == 0 && !configChange) {
            // Truly entering background
            _isForeground.value = false
            _foregroundSnapshot.value = ForegroundSnapshot(isForeground = false, sessionId = _foregroundSessionId.value)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    /**
     * Testing hooks to simulate lifecycle transitions without Android Activity instrumentation.
     */
    fun simulateForeground(sessionId: String = UUID.randomUUID().toString()) {
        _foregroundSessionId.value = sessionId
        _isForeground.value = true
        _foregroundSnapshot.value = ForegroundSnapshot(isForeground = true, sessionId = sessionId)
        startedCount = 1
        configChange = false
    }

    fun simulateBackground() {
        _isForeground.value = false
        _foregroundSnapshot.value = ForegroundSnapshot(isForeground = false, sessionId = _foregroundSessionId.value)
        startedCount = 0
        configChange = false
    }

    fun simulateConfigurationChange() {
        configChange = true
    }

    fun reset() {
        startedCount = 0
        configChange = false
        _foregroundSessionId.value = null
        _isForeground.value = false
        _foregroundSnapshot.value = ForegroundSnapshot(isForeground = false, sessionId = null)
    }
}
