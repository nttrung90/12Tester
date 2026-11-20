package com.ntt.crosstest.data

import com.ntt.crosstest.R
import com.ntt.crosstest.util.VnTime

/**
 * Hành động của chủ app cần bảo vệ trước chỉ tiêu nhiệm vụ ngày:
 * - Xóa app (delete)
 * - Lên production (confirm_production)
 * - Mở lại nhận tester (resume)
 * - Tạm dừng thủ công khi app đang active (manual pause làm giảm nghĩa vụ ngày)
 */
enum class DailyTaskAction {
    DELETE,
    PRODUCTION,
    RESUME,
    MANUAL_PAUSE
}

sealed interface ActionGateBlockReason {
    data object UnmetDailyTask : ActionGateBlockReason
    data class UnresolvedCarryoverDebt(val debt: Int) : ActionGateBlockReason
    data object DataPendingOrStale : ActionGateBlockReason
    data object AccountLocked : ActionGateBlockReason
}

sealed class DailyTaskActionGateResult {
    data object Allowed : DailyTaskActionGateResult()
    data class Blocked(
        val reason: ActionGateBlockReason,
        val messageRes: Int,
        val subtitleRes: Int,
        val formatArg: Int? = null,
    ) : DailyTaskActionGateResult() {
        val isBlocked: Boolean get() = true
    }

    val isAllowed: Boolean get() = this is Allowed
}

/**
 * Logic đánh giá tập trung cho Daily Task Action Gate theo đúng kế hoạch:
 * 1. history_ready = true & carryover_ready = true (không null, không false)
 * 2. met = true (không null, không false)
 * 3. carryover_remaining_tests = 0 (không null, không âm, không > 0)
 * 4. Ngày kinh doanh khớp ngày hiện tại theo giờ Việt Nam
 * 5. Tài khoản không bị khóa (is_locked != true)
 * 6. Admin thao tác bỏ qua gate
 */
object DailyTaskActionGate {

    fun evaluate(
        action: DailyTaskAction,
        campaign: Campaign?,
        dailyTask: DailyTaskStatus?,
        isAccountLocked: Boolean = false,
        isAdmin: Boolean = false,
        todayVn: String = VnTime.today().toString()
    ): DailyTaskActionGateResult {
        // 1. Admin bypass: admin operating on app is not blocked by owner's daily task obligations
        if (isAdmin) {
            return DailyTaskActionGateResult.Allowed
        }

        // 2. Account lock check
        if (isAccountLocked) {
            return DailyTaskActionGateResult.Blocked(
                reason = ActionGateBlockReason.AccountLocked,
                messageRes = R.string.oad_gate_error_account_locked,
                subtitleRes = R.string.oad_gate_error_account_locked
            )
        }

        // 3. For MANUAL_PAUSE, only enforce gate if the app was active (open or auto_paused)
        // because pausing an already-paused app does not reduce daily task quota obligations.
        if (action == DailyTaskAction.MANUAL_PAUSE) {
            val wasActive = campaign != null && (campaign.isOpen || campaign.isAutoPaused)
            if (!wasActive) {
                return DailyTaskActionGateResult.Allowed
            }
        }

        // 4. Missing daily task status: cannot verify readiness -> block
        if (dailyTask == null) {
            return DailyTaskActionGateResult.Blocked(
                reason = ActionGateBlockReason.DataPendingOrStale,
                messageRes = getMessageRes(action, ActionGateBlockReason.DataPendingOrStale),
                subtitleRes = getPendingSubtitle(action)
            )
        }

        // 5. Business date validation: must match Vietnam server business date
        if (dailyTask.statusDate.isNullOrBlank() || dailyTask.statusDate != todayVn) {
            return DailyTaskActionGateResult.Blocked(
                reason = ActionGateBlockReason.DataPendingOrStale,
                messageRes = getMessageRes(action, ActionGateBlockReason.DataPendingOrStale),
                subtitleRes = getPendingSubtitle(action)
            )
        }

        // 6. Readiness check: history_ready and carryover_ready must be explicitly true from server
        // Using raw fields ensures missing/null fields in JSON are recognized as not ready
        val historyReady = dailyTask.rawHistoryReady
        val carryoverReady = dailyTask.rawCarryoverReady
        if (historyReady != true || carryoverReady != true) {
            return DailyTaskActionGateResult.Blocked(
                reason = ActionGateBlockReason.DataPendingOrStale,
                messageRes = getMessageRes(action, ActionGateBlockReason.DataPendingOrStale),
                subtitleRes = getPendingSubtitle(action)
            )
        }

        // 7. Carryover debt check: must be explicitly present, non-negative, and exactly 0
        val remainingDebt = dailyTask.rawCarryoverRemainingTests
        if (remainingDebt == null || remainingDebt < 0) {
            return DailyTaskActionGateResult.Blocked(
                reason = ActionGateBlockReason.DataPendingOrStale,
                messageRes = getMessageRes(action, ActionGateBlockReason.DataPendingOrStale),
                subtitleRes = getPendingSubtitle(action)
            )
        }
        if (remainingDebt > 0) {
            return DailyTaskActionGateResult.Blocked(
                reason = ActionGateBlockReason.UnresolvedCarryoverDebt(remainingDebt),
                messageRes = getMessageRes(action, ActionGateBlockReason.UnresolvedCarryoverDebt(remainingDebt)),
                subtitleRes = getCarryoverSubtitle(action),
                formatArg = remainingDebt
            )
        }

        // 8. Daily task completion: met must be explicitly true
        val met = dailyTask.rawMet
        if (met == null) {
            return DailyTaskActionGateResult.Blocked(
                reason = ActionGateBlockReason.DataPendingOrStale,
                messageRes = getMessageRes(action, ActionGateBlockReason.DataPendingOrStale),
                subtitleRes = getPendingSubtitle(action)
            )
        }
        if (!met) {
            val remaining = ((dailyTask.effectiveRequiredTests ?: 0) - (dailyTask.completedTests ?: 0)).coerceAtLeast(1)
            return DailyTaskActionGateResult.Blocked(
                reason = ActionGateBlockReason.UnmetDailyTask,
                messageRes = getMessageRes(action, ActionGateBlockReason.UnmetDailyTask),
                subtitleRes = getUnmetSubtitle(action),
                formatArg = remaining
            )
        }

        return DailyTaskActionGateResult.Allowed
    }

    fun getMessageRes(action: DailyTaskAction, reason: ActionGateBlockReason): Int = when (reason) {
        ActionGateBlockReason.AccountLocked -> R.string.oad_gate_error_account_locked
        ActionGateBlockReason.DataPendingOrStale -> when (action) {
            DailyTaskAction.RESUME -> R.string.oad_resume_error_pending
            else -> R.string.oad_gate_error_pending_or_stale
        }
        is ActionGateBlockReason.UnresolvedCarryoverDebt -> when (action) {
            DailyTaskAction.RESUME -> R.string.oad_resume_error_carryover_debt_format
            else -> R.string.oad_gate_error_carryover_debt_format
        }
        ActionGateBlockReason.UnmetDailyTask -> R.string.oad_gate_error_unmet_daily_task_format
    }

    fun getPendingSubtitle(action: DailyTaskAction): Int = when (action) {
        DailyTaskAction.DELETE -> R.string.oad_gate_delete_sub_pending
        DailyTaskAction.PRODUCTION -> R.string.oad_gate_production_sub_pending
        DailyTaskAction.RESUME -> R.string.oad_resume_sub_pending
        DailyTaskAction.MANUAL_PAUSE -> R.string.oad_gate_pause_sub_pending
    }

    fun getCarryoverSubtitle(action: DailyTaskAction): Int = when (action) {
        DailyTaskAction.DELETE -> R.string.oad_gate_delete_sub_carryover_format
        DailyTaskAction.PRODUCTION -> R.string.oad_gate_production_sub_carryover_format
        DailyTaskAction.RESUME -> R.string.oad_resume_sub_carryover_debt_format
        DailyTaskAction.MANUAL_PAUSE -> R.string.oad_gate_pause_sub_carryover_format
    }

    fun getUnmetSubtitle(action: DailyTaskAction): Int = when (action) {
        DailyTaskAction.DELETE -> R.string.oad_gate_delete_sub_unmet
        DailyTaskAction.PRODUCTION -> R.string.oad_gate_production_sub_unmet
        DailyTaskAction.RESUME -> R.string.oad_resume_sub_auto
        DailyTaskAction.MANUAL_PAUSE -> R.string.oad_gate_pause_sub_unmet
    }
}
