package com.ntt.crosstest.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NotificationPopupBlockerCoordinator {

    private val _isBlocked = MutableStateFlow(false)
    val isBlocked: StateFlow<Boolean> = _isBlocked.asStateFlow()

    private var accountLocked = false
    private var forceUpdate = false
    private var optionalUpdate = false
    private var rewardOverlay = false
    private var systemWarning = false
    private var authScreen = false
    private var modalDialog = false
    private var appLoading = false

    private val lock = Any()

    private fun recompute() {
        _isBlocked.value = accountLocked || forceUpdate || optionalUpdate || rewardOverlay || systemWarning || authScreen || modalDialog || appLoading
    }

    fun setAccountLocked(locked: Boolean) {
        synchronized(lock) {
            accountLocked = locked
            recompute()
        }
    }

    fun setForceUpdate(active: Boolean) {
        synchronized(lock) {
            forceUpdate = active
            recompute()
        }
    }

    fun setOptionalUpdateShowing(showing: Boolean) {
        synchronized(lock) {
            optionalUpdate = showing
            recompute()
        }
    }

    fun setRewardOverlayShowing(showing: Boolean) {
        synchronized(lock) {
            rewardOverlay = showing
            recompute()
        }
    }

    fun setSystemWarningShowing(showing: Boolean) {
        synchronized(lock) {
            systemWarning = showing
            recompute()
        }
    }

    fun setAuthScreenActive(active: Boolean) {
        synchronized(lock) {
            authScreen = active
            recompute()
        }
    }

    fun setModalDialogShowing(showing: Boolean) {
        synchronized(lock) {
            modalDialog = showing
            recompute()
        }
    }

    fun setAppLoading(loading: Boolean) {
        synchronized(lock) {
            appLoading = loading
            recompute()
        }
    }

    fun reset() {
        synchronized(lock) {
            accountLocked = false
            forceUpdate = false
            optionalUpdate = false
            rewardOverlay = false
            systemWarning = false
            authScreen = false
            modalDialog = false
            appLoading = false
            _isBlocked.value = false
        }
    }
}
