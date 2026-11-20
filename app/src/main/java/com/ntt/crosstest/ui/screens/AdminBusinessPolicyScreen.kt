package com.ntt.crosstest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.ntt.crosstest.data.BusinessPolicy
import com.ntt.crosstest.data.BusinessPolicyHistoryItem
import com.ntt.crosstest.data.BusinessPolicyRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SupabaseHolder
import com.ntt.crosstest.ui.viewmodels.AdminBusinessPolicyViewModel
import com.ntt.crosstest.ui.viewmodels.BusinessPolicyAdminTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminBusinessPolicyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    customViewModel: AdminBusinessPolicyViewModel? = null,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TestCheoApp

    val viewModel: AdminBusinessPolicyViewModel = customViewModel ?: viewModel(
        factory = viewModelFactory {
            initializer {
                val supabase = SupabaseHolder.get()
                val repository = BusinessPolicyRepository(supabase)
                val handle = createSavedStateHandle()
                AdminBusinessPolicyViewModel(
                    repository = repository,
                    authRepository = app.authRepository,
                    localizer = Localizer(context),
                    savedStateHandle = handle,
                )
            }
        }
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSuccess()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_policy_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.admin_policy_dismiss_btn),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.loadConfig(keepDraft = true)
                            if (uiState.activeTab == BusinessPolicyAdminTab.HISTORY || uiState.historyItems.isNotEmpty()) {
                                viewModel.loadHistory(forceRefresh = true)
                            }
                        },
                        enabled = !uiState.isLoading && !uiState.isActionInProgress,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.admin_policy_title),
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = uiState.activeTab == BusinessPolicyAdminTab.ACTIVE,
                    onClick = { viewModel.selectTab(BusinessPolicyAdminTab.ACTIVE) },
                    label = { Text(stringResource(R.string.admin_policy_active_tab)) }
                )
                FilterChip(
                    selected = uiState.activeTab == BusinessPolicyAdminTab.SCHEDULED,
                    onClick = { viewModel.selectTab(BusinessPolicyAdminTab.SCHEDULED) },
                    label = { Text("${stringResource(R.string.admin_policy_future_tab)} (${uiState.futurePolicies.size})") }
                )
                FilterChip(
                    selected = uiState.activeTab == BusinessPolicyAdminTab.EDITOR,
                    onClick = { viewModel.selectTab(BusinessPolicyAdminTab.EDITOR) },
                    label = { Text(stringResource(R.string.admin_policy_editor_tab)) }
                )
                FilterChip(
                    selected = uiState.activeTab == BusinessPolicyAdminTab.HISTORY,
                    onClick = { viewModel.selectTab(BusinessPolicyAdminTab.HISTORY) },
                    label = { Text(stringResource(R.string.admin_policy_history_tab)) }
                )
            }

            // Pending Intent Recovery Banners
            uiState.pendingScheduleIntent?.let { pendingSched ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.admin_policy_pending_schedule_banner,
                                pendingSched.effectiveFrom
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        Button(
                            onClick = viewModel::schedulePolicy,
                            enabled = !uiState.isActionInProgress,
                        ) {
                            Text(stringResource(R.string.admin_policy_retry_pending_btn))
                        }
                    }
                }
            }

            uiState.pendingCancellationIntent?.let { pendingCancel ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.admin_policy_pending_cancel_banner,
                                pendingCancel.effectiveFrom
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        Button(
                            onClick = viewModel::confirmCancelPolicy,
                            enabled = !uiState.isActionInProgress,
                        ) {
                            Text(stringResource(R.string.admin_policy_retry_pending_btn))
                        }
                    }
                }
            }

            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }

            // Tab Content
            when (uiState.activeTab) {
                BusinessPolicyAdminTab.ACTIVE -> {
                    ActivePolicyTabContent(
                        policy = uiState.activePolicy,
                        revision = uiState.currentRevision,
                        todayDate = uiState.todayDate,
                        onCopyToDraft = { policy ->
                            viewModel.populateDraftFromPolicy(policy)
                        }
                    )
                }
                BusinessPolicyAdminTab.SCHEDULED -> {
                    ScheduledPoliciesTabContent(
                        policies = uiState.futurePolicies,
                        hasMore = uiState.futureHasMore,
                        isLoadingMore = uiState.isFutureLoadingMore,
                        onLoadMore = viewModel::loadMoreFuturePolicies,
                        onEdit = { policy ->
                            viewModel.populateDraftFromPolicy(policy, policy.effectiveFrom)
                        },
                        onCancel = { policy ->
                            viewModel.openCancelDialog(policy)
                        }
                    )
                }
                BusinessPolicyAdminTab.EDITOR -> {
                    PolicyEditorTabContent(
                        uiState = uiState,
                        onEffectiveDateChange = viewModel::updateDraftEffectiveDate,
                        onWelcomeCreditsChange = viewModel::updateDraftWelcomeCredits,
                        onMinCreditsToPostChange = viewModel::updateDraftMinCreditsToPost,
                        onSlotFee1Change = viewModel::updateDraftSlotFee1,
                        onTarget15FeeChange = viewModel::updateDraftTarget15Fee,
                        onTarget20FeeChange = viewModel::updateDraftTarget20Fee,
                        onPenaltyTier1Change = viewModel::updateDraftPenaltyTier1,
                        onPenaltyTier2Change = viewModel::updateDraftPenaltyTier2,
                        onPenaltyTier3Change = viewModel::updateDraftPenaltyTier3,
                        onPenaltyTier4Change = viewModel::updateDraftPenaltyTier4,
                        onPushFeeChange = viewModel::updateDraftPushFee,
                        onSlotFee2Change = viewModel::updateDraftSlotFee2,
                        onSlotFee3Change = viewModel::updateDraftSlotFee3,
                        onSlotFee4PlusChange = viewModel::updateDraftSlotFee4Plus,
                        onSlotRenewalDaysChange = viewModel::updateDraftSlotRenewalDays,
                        onReportProvisionalChange = viewModel::updateDraftReportProvisional,
                        onReportTesterBonusChange = viewModel::updateDraftReportReviewTesterBonus,
                        onReportOwnerBonusChange = viewModel::updateDraftReportReviewOwnerBonus,
                        onAppUpdateRewardPerClaimChange = viewModel::updateDraftAppUpdateRewardPerClaim,
                        onAppUpdateMaxClaimsChange = viewModel::updateDraftAppUpdateMaxClaims,
                        onOperatorNoteChange = viewModel::updateDraftOperatorNote,
                        onSubmit = viewModel::schedulePolicy,
                    )
                }
                BusinessPolicyAdminTab.HISTORY -> {
                    PolicyHistoryTabContent(
                        items = uiState.historyItems,
                        isLoading = uiState.isHistoryLoading,
                        isLoadingMore = uiState.isHistoryLoadingMore,
                        hasMore = uiState.historyHasMore,
                        onLoadMore = viewModel::loadMoreHistory,
                    )
                }
            }
        }
    }

    // Cancellation Dialog
    uiState.cancelDialogTarget?.let { target ->
        AlertDialog(
            onDismissRequest = viewModel::dismissCancelDialog,
            title = { Text(stringResource(R.string.admin_policy_cancel_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.admin_policy_cancel_confirm,
                            target.effectiveFrom ?: ""
                        )
                    )
                    OutlinedTextField(
                        value = uiState.cancelReason,
                        onValueChange = viewModel::updateCancelReason,
                        readOnly = uiState.pendingCancellationIntent != null,
                        label = { Text(stringResource(R.string.admin_policy_operator_note)) },
                        placeholder = { Text(stringResource(R.string.admin_policy_cancel_reason_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmCancelPolicy,
                    enabled = !uiState.isActionInProgress && uiState.cancelReason.isNotBlank(),
                ) {
                    if (uiState.isActionInProgress) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(stringResource(R.string.admin_policy_confirm_btn))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissCancelDialog,
                    enabled = !uiState.isActionInProgress,
                ) {
                    Text(stringResource(R.string.admin_policy_dismiss_btn))
                }
            }
        )
    }
}

@Composable
private fun ActivePolicyTabContent(
    policy: BusinessPolicy?,
    revision: String,
    todayDate: String,
    onCopyToDraft: (BusinessPolicy) -> Unit,
) {
    if (policy == null) {
        Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.error_business_policy_not_found))
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Revision & Today Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.admin_policy_revision_format, revision.take(12)),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (todayDate.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.admin_policy_today_format, "Asia/Ho_Chi_Minh", todayDate),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // Policy Details Card
        PolicySummaryCard(policy = policy)

        OutlinedButton(
            onClick = { onCopyToDraft(policy) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.admin_policy_load_active_into_editor))
        }
    }
}

@Composable
private fun ScheduledPoliciesTabContent(
    policies: List<BusinessPolicy>,
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    onEdit: (BusinessPolicy) -> Unit,
    onCancel: (BusinessPolicy) -> Unit,
) {
    if (policies.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.admin_policy_no_future),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(policies, key = { it.id }) { policy ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = policy.effectiveFrom ?: "",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    PolicySummaryCard(policy = policy)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onEdit(policy) }) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.admin_policy_edit_draft_btn))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = { onCancel(policy) },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.admin_policy_cancel_btn))
                        }
                    }
                }
            }
        }

        if (hasMore) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        OutlinedButton(onClick = onLoadMore) {
                            Text(stringResource(R.string.admin_policy_future_load_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PolicySummaryCard(policy: BusinessPolicy) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (policy.isBaseline) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.admin_policy_baseline_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Text(
                text = stringResource(
                    R.string.admin_policy_summary_welcome_format,
                    stringResource(R.string.admin_policy_section_welcome),
                    policy.welcomeCredits
                ),
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "${stringResource(R.string.admin_policy_section_posting)}: " +
                        stringResource(
                            R.string.admin_policy_summary_posting_format,
                            policy.minCreditsToPostApp,
                            policy.slotFees.slot1,
                            policy.targetFees.target15,
                            policy.targetFees.target20
                        ),
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "${stringResource(R.string.admin_policy_section_penalty)}: " +
                        "${policy.dailyPenaltyTiers.tier1Pct}% / " +
                        "${policy.dailyPenaltyTiers.tier2Pct}% / " +
                        "${policy.dailyPenaltyTiers.tier3Pct}% / " +
                        "${policy.dailyPenaltyTiers.tier4Pct}%",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = stringResource(
                    R.string.admin_policy_summary_push_format,
                    stringResource(R.string.admin_policy_section_push),
                    policy.pushFee
                ),
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "${stringResource(R.string.admin_policy_section_slots)}: " +
                        stringResource(
                            R.string.admin_policy_summary_slot_with_s1_format,
                            policy.slotFees.slot1,
                            policy.slotFees.slot2,
                            policy.slotFees.slot3,
                            policy.slotFees.slot4Plus,
                            policy.slotFees.renewalDays
                        ),
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "${stringResource(R.string.admin_policy_section_reports)}: " +
                        stringResource(
                            R.string.admin_policy_summary_report_format,
                            policy.reportRewards.provisionalReward,
                            policy.reportRewards.reviewTesterBonus,
                            policy.reportRewards.reviewOwnerBonus
                        ),
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(
                text = "${stringResource(R.string.admin_policy_section_app_update)}: " +
                        stringResource(
                            R.string.admin_policy_summary_app_update_format,
                            policy.appUpdateRewards.rewardPerClaim,
                            policy.appUpdateRewards.maxClaims,
                            policy.appUpdateRewards.reservedBudget
                        ),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (policy.operatorNote.isNotBlank()) {
                Text(
                    text = "${stringResource(R.string.admin_policy_operator_note)}: ${policy.operatorNote}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PolicyEditorTabContent(
    uiState: com.ntt.crosstest.ui.viewmodels.AdminBusinessPolicyUiState,
    onEffectiveDateChange: (String) -> Unit,
    onWelcomeCreditsChange: (String) -> Unit,
    onMinCreditsToPostChange: (String) -> Unit,
    onSlotFee1Change: (String) -> Unit,
    onTarget15FeeChange: (String) -> Unit,
    onTarget20FeeChange: (String) -> Unit,
    onPenaltyTier1Change: (String) -> Unit,
    onPenaltyTier2Change: (String) -> Unit,
    onPenaltyTier3Change: (String) -> Unit,
    onPenaltyTier4Change: (String) -> Unit,
    onPushFeeChange: (String) -> Unit,
    onSlotFee2Change: (String) -> Unit,
    onSlotFee3Change: (String) -> Unit,
    onSlotFee4PlusChange: (String) -> Unit,
    onSlotRenewalDaysChange: (String) -> Unit,
    onReportProvisionalChange: (String) -> Unit,
    onReportTesterBonusChange: (String) -> Unit,
    onReportOwnerBonusChange: (String) -> Unit,
    onAppUpdateRewardPerClaimChange: (String) -> Unit,
    onAppUpdateMaxClaimsChange: (String) -> Unit,
    onOperatorNoteChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Effective Date
        OutlinedTextField(
            value = uiState.draftEffectiveDate,
            onValueChange = onEffectiveDateChange,
            label = { Text(stringResource(R.string.admin_policy_effective_date)) },
            placeholder = { Text(stringResource(R.string.admin_policy_select_date)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // Welcome Credits
        Text(stringResource(R.string.admin_policy_section_welcome), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = uiState.draftWelcomeCredits,
            onValueChange = onWelcomeCreditsChange,
            label = { Text(stringResource(R.string.admin_policy_welcome_credits)) },
            supportingText = { Text(stringResource(R.string.admin_policy_welcome_credits_helper)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // App Publishing & Test Targets
        Text(stringResource(R.string.admin_policy_section_posting), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = uiState.draftMinCreditsToPostApp,
            onValueChange = onMinCreditsToPostChange,
            label = { Text(stringResource(R.string.admin_policy_min_credits_to_post)) },
            supportingText = { Text(stringResource(R.string.admin_policy_min_credits_to_post_helper)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = uiState.draftTarget15Fee,
                onValueChange = onTarget15FeeChange,
                label = { Text(stringResource(R.string.admin_policy_target_15_fee)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.draftTarget20Fee,
                onValueChange = onTarget20FeeChange,
                label = { Text(stringResource(R.string.admin_policy_target_20_fee)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Text(
            stringResource(R.string.admin_policy_target_fees_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Penalty Tiers
        Text(stringResource(R.string.admin_policy_section_penalty), style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = uiState.draftPenaltyTier1,
                onValueChange = onPenaltyTier1Change,
                label = { Text(stringResource(R.string.admin_policy_penalty_tier1)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.draftPenaltyTier2,
                onValueChange = onPenaltyTier2Change,
                label = { Text(stringResource(R.string.admin_policy_penalty_tier2)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = uiState.draftPenaltyTier3,
                onValueChange = onPenaltyTier3Change,
                label = { Text(stringResource(R.string.admin_policy_penalty_tier3)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.draftPenaltyTier4,
                onValueChange = onPenaltyTier4Change,
                label = { Text(stringResource(R.string.admin_policy_penalty_tier4)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Text(
            stringResource(R.string.admin_policy_penalty_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Campaign Push Fee
        Text(stringResource(R.string.admin_policy_section_push), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = uiState.draftPushFee,
            onValueChange = onPushFeeChange,
            label = { Text(stringResource(R.string.admin_policy_push_fee)) },
            supportingText = { Text(stringResource(R.string.admin_policy_push_fee_helper)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // Slot Fees & Duration
        Text(stringResource(R.string.admin_policy_section_slots), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = uiState.draftSlotFee1,
            onValueChange = onSlotFee1Change,
            label = { Text(stringResource(R.string.admin_policy_slot_fee_1)) },
            supportingText = { Text(stringResource(R.string.admin_policy_slot_fee_1_helper)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = uiState.draftSlotFee2,
                onValueChange = onSlotFee2Change,
                label = { Text(stringResource(R.string.admin_policy_slot_fee_2)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.draftSlotFee3,
                onValueChange = onSlotFee3Change,
                label = { Text(stringResource(R.string.admin_policy_slot_fee_3)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        OutlinedTextField(
            value = uiState.draftSlotFee4Plus,
            onValueChange = onSlotFee4PlusChange,
            label = { Text(stringResource(R.string.admin_policy_slot_fee_4_plus)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = uiState.draftSlotRenewalDays,
            onValueChange = onSlotRenewalDaysChange,
            label = { Text(stringResource(R.string.admin_policy_slot_renewal_days)) },
            supportingText = { Text(stringResource(R.string.admin_policy_slot_helper)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // Tester Report Rewards
        Text(stringResource(R.string.admin_policy_section_reports), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = uiState.draftReportProvisional,
            onValueChange = onReportProvisionalChange,
            label = { Text(stringResource(R.string.admin_policy_report_provisional)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = uiState.draftReportReviewTesterBonus,
                onValueChange = onReportTesterBonusChange,
                label = { Text(stringResource(R.string.admin_policy_report_tester_bonus)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.draftReportReviewOwnerBonus,
                onValueChange = onReportOwnerBonusChange,
                label = { Text(stringResource(R.string.admin_policy_report_owner_bonus)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }

        // App Update Release Rewards
        Text(stringResource(R.string.admin_policy_section_app_update), style = MaterialTheme.typography.titleSmall)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = uiState.draftAppUpdateRewardPerClaim,
                onValueChange = onAppUpdateRewardPerClaimChange,
                label = { Text(stringResource(R.string.admin_policy_app_update_reward_per_claim)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.draftAppUpdateMaxClaims,
                onValueChange = onAppUpdateMaxClaimsChange,
                label = { Text(stringResource(R.string.admin_policy_app_update_max_claims)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text(
                text = stringResource(R.string.admin_policy_app_update_reserved_budget, uiState.derivedUpdateBudget),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(12.dp),
            )
        }

        // Mandatory Operator Note
        OutlinedTextField(
            value = uiState.draftOperatorNote,
            onValueChange = onOperatorNoteChange,
            label = { Text(stringResource(R.string.admin_policy_operator_note)) },
            placeholder = { Text(stringResource(R.string.admin_policy_operator_note_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
        )

        // Submit Button
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.isActionInProgress,
        ) {
            if (uiState.isActionInProgress) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(stringResource(R.string.admin_policy_schedule_btn))
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun PolicyHistoryTabContent(
    items: List<BusinessPolicyHistoryItem>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
) {
    if (items.isEmpty() && !isLoading) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.admin_policy_no_history),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id }) { item ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = when (item.operation) {
                                "INSERT" -> MaterialTheme.colorScheme.primaryContainer
                                "UPDATE" -> MaterialTheme.colorScheme.secondaryContainer
                                else -> MaterialTheme.colorScheme.errorContainer
                            }
                        ) {
                            Text(
                                text = item.operation,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }

                        Text(
                            text = item.changedAt.take(19).replace('T', ' '),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val row = (item.newRow as? JsonObject) ?: (item.oldRow as? JsonObject)
                    val effectiveFrom = row?.get("effective_from")?.jsonPrimitive?.contentOrNull
                    if (!effectiveFrom.isNullOrBlank()) {
                        Text(
                            text = "${stringResource(R.string.admin_policy_effective_date)}: $effectiveFrom",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    val minCredits = row?.get("min_credits_to_post_app")?.jsonPrimitive?.intOrNull
                    val slotFee1 = row?.get("slot_fee_1")?.jsonPrimitive?.intOrNull
                    val target15 = row?.get("target_15_fee")?.jsonPrimitive?.intOrNull
                    val target20 = row?.get("target_20_fee")?.jsonPrimitive?.intOrNull

                    if (minCredits != null && slotFee1 != null && target15 != null && target20 != null) {
                        Text(
                            text = stringResource(
                                R.string.admin_policy_summary_posting_format,
                                minCredits,
                                slotFee1,
                                target15,
                                target20,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (item.operatorNote.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.admin_policy_history_note_format, item.operatorNote),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    item.actorId?.let { actor ->
                        Text(
                            text = stringResource(R.string.admin_audit_actor, "${actor.take(8)}..."),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }

        if (hasMore) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isLoadingMore) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        OutlinedButton(onClick = onLoadMore) {
                            Text(stringResource(R.string.admin_load_more))
                        }
                    }
                }
            }
        }
    }
}
