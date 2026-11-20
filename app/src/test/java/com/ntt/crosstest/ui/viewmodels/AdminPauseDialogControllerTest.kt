package com.ntt.crosstest.ui.viewmodels

import com.ntt.crosstest.data.AdminPauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class AdminPauseDialogControllerTest {

    private val testGeneration = AtomicLong(1L)
    private lateinit var controller: AdminPauseDialogController

    @Before
    fun setup() {
        testGeneration.set(1L)
        controller = AdminPauseDialogController { testGeneration.get() }
    }

    @Test
    fun openDialog_withAdminIdentity_initializesCleanStateAndInstanceId() {
        controller.open(userId = "admin-A", generation = 1L, isAdmin = true)

        val state = controller.state.value
        assertTrue("Dialog must be open for active admin", state.isOpen)
        assertEquals(AdminPauseReason.CUSTOM.code, state.reasonCode)
        assertEquals("", state.customReason)
        assertNull(state.error)
        assertEquals("admin-A", state.sessionUserId)
        assertEquals(1L, state.sessionGeneration)
        assertTrue("Instance ID must be positive", state.dialogInstanceId > 0L)
    }

    @Test
    fun openDialog_withNonAdminOrNullUid_isRejected() {
        controller.open(userId = null, generation = 1L, isAdmin = true)
        assertFalse(controller.state.value.isOpen)

        controller.open(userId = "user-1", generation = 1L, isAdmin = false)
        assertFalse(controller.state.value.isOpen)
    }

    @Test
    fun adminSwitch_fromAdminAToAdminB_resetsDialogAndDraftAndError() {
        controller.open(userId = "admin-A", generation = 1L, isAdmin = true)
        controller.setReasonCode(AdminPauseReason.MISSING_GOOGLE_GROUP.code)
        controller.setCustomReason("Admin A draft note")
        val instanceId = controller.currentInstanceId()
        controller.onActionError(instanceId, "admin-A", 1L, "Some previous error")

        // Precondition
        assertTrue(controller.state.value.isOpen)
        assertEquals(AdminPauseReason.MISSING_GOOGLE_GROUP.code, controller.state.value.reasonCode)
        assertEquals("Admin A draft note", controller.state.value.customReason)
        assertEquals("Some previous error", controller.state.value.error)

        // Switch to Admin B
        controller.syncIdentity(userId = "admin-B", generation = 1L, isAdmin = true)

        val state = controller.state.value
        assertFalse("Open dialog must reset when switching from Admin A to Admin B", state.isOpen)
        assertEquals("Draft preset must reset to CUSTOM", AdminPauseReason.CUSTOM.code, state.reasonCode)
        assertEquals("Draft note must be cleared", "", state.customReason)
        assertNull("In-modal error must be cleared", state.error)
        assertEquals("admin-B", state.sessionUserId)
    }

    @Test
    fun sessionGenerationChange_sameUid_resetsDialogAndDraftAndError() {
        controller.open(userId = "admin-A", generation = 1L, isAdmin = true)
        controller.setReasonCode(AdminPauseReason.POLICY_VIOLATION.code)
        controller.setCustomReason("Admin A draft")

        // Same UID but new session generation (e.g. relogin / session refresh)
        testGeneration.set(2L)
        controller.syncIdentity(userId = "admin-A", generation = 2L, isAdmin = true)

        val state = controller.state.value
        assertFalse("Dialog must close on session generation change", state.isOpen)
        assertEquals(AdminPauseReason.CUSTOM.code, state.reasonCode)
        assertEquals("", state.customReason)
        assertNull(state.error)
        assertEquals(2L, state.sessionGeneration)
    }

    @Test
    fun logout_resetsDialog() {
        controller.open(userId = "admin-A", generation = 1L, isAdmin = true)
        controller.setCustomReason("Unfinished draft")

        controller.syncIdentity(userId = null, generation = 2L, isAdmin = false)

        val state = controller.state.value
        assertFalse(state.isOpen)
        assertEquals("", state.customReason)
        assertNull(state.sessionUserId)
    }

    @Test
    fun actionError_keepsDialogVisible_preservesSelectionAndDraft_andSetsInModalError() {
        controller.open(userId = "admin-A", generation = 1L, isAdmin = true)
        controller.setReasonCode(AdminPauseReason.MISSING_GOOGLE_GROUP.code)
        controller.setCustomReason("Please add test group")
        val instanceId = controller.currentInstanceId()

        val handled = controller.onActionError(
            dialogInstanceId = instanceId,
            initiatingUid = "admin-A",
            initiatingGen = 1L,
            errorMessage = "Máy chủ (backend) chưa được cập nhật tính năng này.",
        )

        assertTrue("Error should be accepted by controller for matching instance", handled)
        val state = controller.state.value
        assertTrue("Dialog must remain open on action error for retry", state.isOpen)
        assertEquals("Preset must be preserved for retry", AdminPauseReason.MISSING_GOOGLE_GROUP.code, state.reasonCode)
        assertEquals("Draft note must be preserved for retry", "Please add test group", state.customReason)
        assertEquals("Máy chủ (backend) chưa được cập nhật tính năng này.", state.error)

        // User edits selection -> error is cleared
        controller.setReasonCode(AdminPauseReason.POLICY_VIOLATION.code)
        assertNull("Changing preset should clear in-modal error", controller.state.value.error)
    }

    @Test
    fun actionSuccess_closesDialogAndClearsDraftAndError() {
        controller.open(userId = "admin-A", generation = 1L, isAdmin = true)
        controller.setReasonCode(AdminPauseReason.POLICY_VIOLATION.code)
        controller.setCustomReason("Policy draft")
        val instanceId = controller.currentInstanceId()

        val handled = controller.onActionSuccess(
            dialogInstanceId = instanceId,
            initiatingUid = "admin-A",
            initiatingGen = 1L,
        )

        assertTrue(handled)
        val state = controller.state.value
        assertFalse("Dialog must close on success", state.isOpen)
        assertEquals(AdminPauseReason.CUSTOM.code, state.reasonCode)
        assertEquals("", state.customReason)
        assertNull(state.error)
    }

    @Test
    fun staleActionCallback_fromOldDialogInstance_cannotCloseOrAffectNewerDialog() {
        // Open dialog instance 1
        controller.open(userId = "admin-A", generation = 1L, isAdmin = true)
        val oldInstanceId = controller.currentInstanceId()

        // User closes dialog 1 and opens dialog 2
        controller.dismiss()
        controller.open(userId = "admin-A", generation = 1L, isAdmin = true)
        val newInstanceId = controller.currentInstanceId()
        assertNotEquals(oldInstanceId, newInstanceId)

        controller.setReasonCode(AdminPauseReason.POLICY_VIOLATION.code)
        controller.setCustomReason("New reason for dialog 2")

        // Old action callback from instance 1 arrives late
        val successHandled = controller.onActionSuccess(
            dialogInstanceId = oldInstanceId,
            initiatingUid = "admin-A",
            initiatingGen = 1L,
        )
        assertFalse("Old instance callback must be rejected", successHandled)
        assertTrue("New dialog must remain open", controller.state.value.isOpen)
        assertEquals(AdminPauseReason.POLICY_VIOLATION.code, controller.state.value.reasonCode)
        assertEquals("New reason for dialog 2", controller.state.value.customReason)

        val errorHandled = controller.onActionError(
            dialogInstanceId = oldInstanceId,
            initiatingUid = "admin-A",
            initiatingGen = 1L,
            errorMessage = "Old error",
        )
        assertFalse("Old error callback must be rejected", errorHandled)
        assertNull("New dialog error must not be overwritten by old error", controller.state.value.error)
    }

    @Test
    fun staleActionCallback_fromOldSession_cannotAffectNewAdmin() {
        // Admin A opens dialog and initiates action
        controller.open(userId = "admin-A", generation = 1L, isAdmin = true)
        val adminAInstanceId = controller.currentInstanceId()

        // Auth switches to Admin B
        controller.syncIdentity(userId = "admin-B", generation = 1L, isAdmin = true)

        // Admin B opens dialog
        controller.open(userId = "admin-B", generation = 1L, isAdmin = true)
        controller.setReasonCode(AdminPauseReason.POLICY_VIOLATION.code)

        // Old callback for Admin A returns
        val handled = controller.onActionSuccess(
            dialogInstanceId = adminAInstanceId,
            initiatingUid = "admin-A",
            initiatingGen = 1L,
        )
        assertFalse("Callback with mismatched UID must be rejected", handled)
        assertTrue("Admin B dialog must remain open", controller.state.value.isOpen)
        assertEquals("admin-B", controller.state.value.sessionUserId)
        assertEquals(AdminPauseReason.POLICY_VIOLATION.code, controller.state.value.reasonCode)
    }

    @Test
    fun stateSurvivesRecompositionAndRotation() {
        controller.open(userId = "admin-A", generation = 1L, isAdmin = true)
        controller.setReasonCode(AdminPauseReason.MISSING_GOOGLE_GROUP.code)
        controller.setCustomReason("Please verify group membership")
        val instanceId = controller.currentInstanceId()
        controller.onActionError(instanceId, "admin-A", 1L, "Connection timeout")

        // Read 1 (before rotation)
        val state1 = controller.state.value

        // Simulate identical identity sync during recomposition / Activity recreation
        controller.syncIdentity(userId = "admin-A", generation = 1L, isAdmin = true)

        // Read 2 (after rotation)
        val state2 = controller.state.value

        assertEquals(state1.isOpen, state2.isOpen)
        assertEquals(state1.reasonCode, state2.reasonCode)
        assertEquals(state1.customReason, state2.customReason)
        assertEquals(state1.error, state2.error)
        assertEquals(state1.dialogInstanceId, state2.dialogInstanceId)
    }
}
