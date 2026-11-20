package com.ntt.crosstest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.AdminCarryoverPolicyItem
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.ui.viewmodels.CarryoverPolicyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarryoverPolicyScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TestCheoApp
    val viewModel: CarryoverPolicyViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                CarryoverPolicyViewModel(
                    adminManagementRepository = app.adminManagementRepository,
                    authRepository = app.authRepository,
                    localizer = Localizer(app),
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var cancellingPolicyDate by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(uiState.actionMessage) {
        uiState.actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_carryover_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadPolicies(preserveDraft = true) }, enabled = !uiState.isLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.admin_refresh_cd))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading,
            onRefresh = { viewModel.loadPolicies(preserveDraft = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Active Policy Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.admin_carryover_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            uiState.revision?.let {
                                Text(stringResource(R.string.admin_carryover_revision, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        val active = uiState.activePolicy
                        val statusText = if (active?.isEnabled == true) stringResource(R.string.admin_carryover_status_enabled) else stringResource(R.string.admin_carryover_status_disabled)
                        val fromDate = active?.effectiveFrom ?: "—"

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (active?.isEnabled == true) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.errorContainer
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (active?.isEnabled == true) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                            Text(stringResource(R.string.admin_carryover_active_status, statusText, fromDate), style = MaterialTheme.typography.bodyMedium)
                        }

                        active?.operatorNote?.let {
                            Text(stringResource(R.string.admin_carryover_note_format, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // Schedule New Future Policy Form
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.admin_carryover_schedule_btn), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = uiState.draftIsEnabled,
                                onClick = { viewModel.setDraft(isEnabled = true) },
                                label = { Text(stringResource(R.string.admin_carryover_status_enabled)) },
                            )
                            FilterChip(
                                selected = !uiState.draftIsEnabled,
                                onClick = { viewModel.setDraft(isEnabled = false) },
                                label = { Text(stringResource(R.string.admin_carryover_status_disabled)) },
                            )
                        }

                        OutlinedTextField(
                            value = uiState.draftEffectiveFrom,
                            onValueChange = { viewModel.setDraft(effectiveFrom = it) },
                            label = { Text(stringResource(R.string.admin_carryover_effective_from)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = uiState.draftNote,
                            onValueChange = { viewModel.setDraft(note = it) },
                            label = { Text(stringResource(R.string.admin_carryover_note)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Button(
                            onClick = {
                                viewModel.schedulePolicy(
                                    effectiveFrom = uiState.draftEffectiveFrom,
                                    isEnabled = uiState.draftIsEnabled,
                                    note = uiState.draftNote,
                                    confirmingAdminId = uiState.currentAdminId,
                                    confirmingSessionGen = uiState.sessionGeneration,
                                )
                            },
                            enabled = !uiState.isSaving && uiState.draftEffectiveFrom.isNotBlank() && uiState.draftNote.isNotBlank(),
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            if (uiState.isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(stringResource(R.string.admin_carryover_schedule_btn))
                        }
                    }
                }

                // Future Policies List
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.admin_carryover_future_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

                        if (uiState.futurePolicies.isEmpty()) {
                            Text(stringResource(R.string.admin_carryover_empty_future), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            uiState.futurePolicies.forEach { policy ->
                                FuturePolicyItemRow(
                                    policy = policy,
                                    isCancelling = uiState.isCancelling,
                                    onCancel = { cancellingPolicyDate = policy.effectiveFrom }
                                )
                            }

                            if (uiState.futureHasMore) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (uiState.isFutureLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    } else {
                                        OutlinedButton(onClick = viewModel::loadMoreFuturePolicies) {
                                            Text(stringResource(R.string.admin_load_more))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // History List
                if (uiState.history.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.admin_carryover_history_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            uiState.history.forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            stringResource(
                                                R.string.admin_carryover_history_item_format,
                                                item.effectiveFrom.orEmpty(),
                                                if (item.isEnabled) stringResource(R.string.admin_carryover_status_enabled) else stringResource(R.string.admin_carryover_status_disabled)
                                            ),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        item.operatorNote?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    cancellingPolicyDate?.let { date ->
        CancelFuturePolicyDialog(
            effectiveDate = date,
            currentAdminId = uiState.currentAdminId,
            sessionGeneration = uiState.sessionGeneration,
            isLoading = uiState.isCancelling,
            onDismiss = { cancellingPolicyDate = null },
            onConfirm = { reason ->
                viewModel.cancelFuturePolicy(
                    date,
                    reason,
                    confirmingAdminId = uiState.currentAdminId,
                    confirmingSessionGen = uiState.sessionGeneration,
                )
                cancellingPolicyDate = null
            }
        )
    }
}

@Composable
private fun FuturePolicyItemRow(
    policy: AdminCarryoverPolicyItem,
    isCancelling: Boolean,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    R.string.admin_carryover_item_format,
                    policy.effectiveFrom.orEmpty(),
                    if (policy.isEnabled) stringResource(R.string.admin_carryover_status_enabled) else stringResource(R.string.admin_carryover_status_disabled)
                ),
                fontWeight = FontWeight.SemiBold,
            )
            policy.operatorNote?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton(
            onClick = onCancel,
            enabled = !isCancelling,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.admin_carryover_cancel_btn))
        }
    }
}

@Composable
private fun CancelFuturePolicyDialog(
    effectiveDate: String,
    currentAdminId: String?,
    sessionGeneration: Long,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (reason: String) -> Unit,
) {
    var reason by rememberSaveable(effectiveDate, currentAdminId, sessionGeneration) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.admin_carryover_cancel_btn)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.admin_carryover_cancel_confirm, effectiveDate))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.admin_carryover_cancel_reason)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(reason) },
                enabled = !isLoading && reason.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                if (isLoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
