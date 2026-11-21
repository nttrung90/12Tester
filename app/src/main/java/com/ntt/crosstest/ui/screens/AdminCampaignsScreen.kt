package com.ntt.crosstest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.AdminCampaignItem
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.ui.theme.statusColors
import com.ntt.crosstest.ui.viewmodels.AdminCampaignsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminCampaignsScreen(
    onBack: () -> Unit,
    onOpenCampaignDetail: (campaignId: String) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TestCheoApp
    val viewModel: AdminCampaignsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                AdminCampaignsViewModel(
                    adminManagementRepository = app.adminManagementRepository,
                    authRepository = app.authRepository,
                    localizer = Localizer(app),
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var overrideTarget by remember { mutableStateOf<AdminCampaignItem?>(null) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.actionMessage) {
        uiState.actionMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionMessage()
        }
    }

    overrideTarget?.let { targetItem ->
        var lockClassification by remember(targetItem.id) { mutableStateOf(true) }
        var noteText by remember(targetItem.id) { mutableStateOf("") }
        val nextIsProduction = !targetItem.playProductionDetected

        AlertDialog(
            onDismissRequest = { overrideTarget = null },
            title = {
                Text(
                    text = stringResource(
                        if (nextIsProduction) R.string.admin_play_override_mark_title
                        else R.string.admin_play_override_clear_title
                    ),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(
                            if (nextIsProduction) R.string.admin_play_override_mark_desc
                            else R.string.admin_play_override_clear_desc,
                            targetItem.appName,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = lockClassification,
                            onCheckedChange = { lockClassification = it },
                        )
                        Text(
                            text = stringResource(R.string.admin_play_override_lock_label),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = { noteText = it },
                        label = { Text(stringResource(R.string.admin_play_override_note_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.overridePlayProduction(
                            campaignId = targetItem.id,
                            isProduction = nextIsProduction,
                            lock = lockClassification,
                            note = noteText,
                        )
                        overrideTarget = null
                    },
                ) {
                    Text(
                        stringResource(
                            if (nextIsProduction) R.string.admin_play_override_mark_btn
                            else R.string.admin_play_override_clear_btn
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { overrideTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.admin_tool_campaigns), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !uiState.isLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.admin_refresh_cd))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Search & Filter header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = uiState.query,
                        onValueChange = viewModel::setQuery,
                        placeholder = { Text(stringResource(R.string.admin_campaign_search_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = uiState.statusFilter == null,
                            onClick = { viewModel.setStatusFilter(null) },
                            label = { Text(stringResource(R.string.admin_campaign_status_all)) },
                        )
                        FilterChip(
                            selected = uiState.statusFilter == "open",
                            onClick = { viewModel.setStatusFilter("open") },
                            label = { Text(stringResource(R.string.admin_status_open)) },
                        )
                        FilterChip(
                            selected = uiState.statusFilter == "paused",
                            onClick = { viewModel.setStatusFilter("paused") },
                            label = { Text(stringResource(R.string.admin_status_paused)) },
                        )
                        FilterChip(
                            selected = uiState.statusFilter == "completed",
                            onClick = { viewModel.setStatusFilter("completed") },
                            label = { Text(stringResource(R.string.admin_status_completed)) },
                        )
                    }
                }

                if (uiState.campaigns.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (uiState.isLoading) CircularProgressIndicator()
                        else Text(stringResource(R.string.admin_campaign_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(uiState.campaigns, key = { it.id }) { item ->
                            AdminCampaignCard(
                                item = item,
                                isOverriding = item.id in uiState.overridingCampaignIds,
                                onOpen = { onOpenCampaignDetail(item.id) },
                                onTogglePlayProduction = { overrideTarget = item },
                            )
                        }

                        if (uiState.hasMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (uiState.isLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    } else {
                                        OutlinedButton(onClick = viewModel::loadMore) {
                                            Text(stringResource(R.string.admin_load_more))
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
}

@Composable
private fun AdminCampaignCard(
    item: AdminCampaignItem,
    isOverriding: Boolean = false,
    onOpen: () -> Unit,
    onTogglePlayProduction: () -> Unit,
) {
    val statusColors = MaterialTheme.statusColors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.appName,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val statusLabel = when (item.status) {
                    "open" -> stringResource(R.string.admin_status_open)
                    "paused" -> stringResource(R.string.admin_status_paused)
                    else -> stringResource(R.string.admin_status_completed)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when (item.status) {
                                "open" -> MaterialTheme.colorScheme.primaryContainer
                                "paused" -> MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.secondaryContainer
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.admin_campaign_owner, item.ownerName ?: item.ownerEmail ?: item.ownerId.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.admin_campaign_testers_count, item.currentTesters, item.targetTesters),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (item.pendingReportsCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.admin_campaign_pending_reports, item.pendingReportsCount),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (item.playProductionDetected) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(statusColors.successContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = statusColors.onSuccessContainer,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = if (!item.playReleasedDate.isNullOrBlank()) {
                                stringResource(R.string.admin_play_state_production_with_date, item.playReleasedDate)
                            } else {
                                stringResource(R.string.admin_play_state_production)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColors.onSuccessContainer,
                        )
                    }
                } else if (item.playStoreState != "unknown") {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.admin_play_state_format, item.playStoreState),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (item.playClassificationLocked) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(statusColors.warningContainer)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = statusColors.onWarningContainer,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = stringResource(R.string.admin_play_classification_locked_badge),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColors.onWarningContainer,
                        )
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (item.status == "open") {
                    OutlinedButton(
                        onClick = onTogglePlayProduction,
                        enabled = !isOverriding,
                    ) {
                        if (isOverriding) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = stringResource(
                                if (item.playProductionDetected) {
                                    R.string.admin_play_override_clear_btn
                                } else {
                                    R.string.admin_play_override_mark_btn
                                }
                            )
                        )
                    }
                }
                Button(onClick = onOpen) {
                    Text(stringResource(R.string.admin_campaign_open_detail))
                }
            }
        }
    }
}
