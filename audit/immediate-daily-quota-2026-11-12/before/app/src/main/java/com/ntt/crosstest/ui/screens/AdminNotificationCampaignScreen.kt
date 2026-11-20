package com.ntt.crosstest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.AdminNotificationCampaign
import com.ntt.crosstest.data.AdminNotificationCampaignRepository
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.SupabaseHolder
import com.ntt.crosstest.ui.viewmodels.AdminNotificationCampaignUiState
import com.ntt.crosstest.ui.viewmodels.AdminNotificationCampaignViewModel
import com.ntt.crosstest.ui.viewmodels.NotificationActionDialog
import com.ntt.crosstest.ui.viewmodels.NotificationAdminTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminNotificationCampaignScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TestCheoApp

    val viewModel: AdminNotificationCampaignViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val supabase = SupabaseHolder.get()
                val repository = AdminNotificationCampaignRepository(supabase)
                val localizer = Localizer(app)
                val handle = createSavedStateHandle()
                AdminNotificationCampaignViewModel(
                    repository = repository,
                    authRepository = app.authRepository,
                    localizer = localizer,
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
            viewModel.dismissMessage()
        }
    }

    LaunchedEffect(uiState.infoMessage) {
        uiState.infoMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.admin_notif_title),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.admin_notif_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            when (uiState.activeTab) {
                                NotificationAdminTab.HISTORY -> viewModel.loadHistory()
                                NotificationAdminTab.COMPOSER -> viewModel.triggerPreview()
                                NotificationAdminTab.MONITOR -> viewModel.loadSchedulerHealth()
                            }
                        },
                        enabled = !uiState.isActionInProgress,
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.admin_notif_refresh),
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(
                selectedTabIndex = uiState.activeTab.ordinal,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = uiState.activeTab == NotificationAdminTab.HISTORY,
                    onClick = { viewModel.selectTab(NotificationAdminTab.HISTORY) },
                    text = { Text(stringResource(R.string.admin_notif_tab_history)) },
                    icon = { Icon(Icons.Filled.Notifications, contentDescription = null) }
                )
                Tab(
                    selected = uiState.activeTab == NotificationAdminTab.COMPOSER,
                    onClick = { viewModel.selectTab(NotificationAdminTab.COMPOSER) },
                    text = { Text(stringResource(R.string.admin_notif_tab_composer)) },
                    icon = { Icon(Icons.Filled.Campaign, contentDescription = null) }
                )
                Tab(
                    selected = uiState.activeTab == NotificationAdminTab.MONITOR,
                    onClick = { viewModel.selectTab(NotificationAdminTab.MONITOR) },
                    text = { Text(stringResource(R.string.admin_notif_tab_monitor)) },
                    icon = { Icon(Icons.Filled.Schedule, contentDescription = null) }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (uiState.activeTab) {
                    NotificationAdminTab.HISTORY -> NotificationHistoryTabContent(
                        uiState = uiState,
                        viewModel = viewModel,
                    )
                    NotificationAdminTab.COMPOSER -> NotificationComposerTabContent(
                        uiState = uiState,
                        viewModel = viewModel,
                    )
                    NotificationAdminTab.MONITOR -> NotificationMonitorTabContent(
                        uiState = uiState,
                        viewModel = viewModel,
                    )
                }
            }
        }
    }

    // Confirmation & Action Dialogs
    NotificationDialogHost(uiState = uiState, viewModel = viewModel)

    // Campaign Detail Dialog / Sheet
    uiState.selectedDetailCampaign?.let { detail ->
        CampaignDetailDialog(
            detail = detail,
            uiState = uiState,
            viewModel = viewModel,
            onDismiss = viewModel::closeDetail,
        )
    }
}

@Composable
private fun NotificationHistoryTabContent(
    uiState: AdminNotificationCampaignUiState,
    viewModel: AdminNotificationCampaignViewModel,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Filters Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = uiState.filterStatus == null,
                onClick = { viewModel.setHistoryFilters(null, uiState.filterAudience) },
                label = { Text(stringResource(R.string.admin_notif_filter_all)) }
            )
            FilterChip(
                selected = uiState.filterStatus == "draft",
                onClick = { viewModel.setHistoryFilters("draft", uiState.filterAudience) },
                label = { Text(stringResource(R.string.admin_notif_status_draft)) }
            )
            FilterChip(
                selected = uiState.filterStatus == "queued",
                onClick = { viewModel.setHistoryFilters("queued", uiState.filterAudience) },
                label = { Text(stringResource(R.string.admin_notif_status_queued)) }
            )
            FilterChip(
                selected = uiState.filterStatus == "sending",
                onClick = { viewModel.setHistoryFilters("sending", uiState.filterAudience) },
                label = { Text(stringResource(R.string.admin_notif_status_sending)) }
            )
            FilterChip(
                selected = uiState.filterStatus == "completed",
                onClick = { viewModel.setHistoryFilters("completed", uiState.filterAudience) },
                label = { Text(stringResource(R.string.admin_notif_status_completed)) }
            )
            FilterChip(
                selected = uiState.filterStatus == "cancelled",
                onClick = { viewModel.setHistoryFilters("cancelled", uiState.filterAudience) },
                label = { Text(stringResource(R.string.admin_notif_status_cancelled)) }
            )
        }

        if (uiState.isHistoryLoading && uiState.campaigns.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (uiState.campaigns.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Campaign,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.admin_notif_empty_history),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(uiState.campaigns, key = { it.id }) { campaign ->
                    CampaignHistoryCard(
                        campaign = campaign,
                        onViewDetail = { viewModel.openDetail(campaign.id) },
                        onEdit = { viewModel.editDraft(campaign) },
                        onSchedule = { viewModel.openScheduleDialog(campaign) },
                        onSendNow = { viewModel.openSendNowDialog(campaign) },
                        onCancel = { viewModel.openCancelDialog(campaign) },
                    )
                }

                if (uiState.historyHasMore) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (uiState.isHistoryLoadingMore) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                OutlinedButton(onClick = viewModel::loadMoreHistory) {
                                    Text(stringResource(R.string.admin_notif_load_more))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CampaignHistoryCard(
    campaign: AdminNotificationCampaign,
    onViewDetail: () -> Unit,
    onEdit: () -> Unit,
    onSchedule: () -> Unit,
    onSendNow: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onViewDetail),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(status = campaign.status)
                Text(
                    text = "rev." + campaign.revision,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "${campaign.titleVi} / ${campaign.titleEn}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = campaign.bodyVi,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Metrics Summary
            val totalRecipientsText = when {
                campaign.recipientSnapshotTaken || campaign.totalRecipients > 0 ->
                    stringResource(R.string.admin_notif_stat_total, campaign.totalRecipients)
                campaign.audienceType == "selected_users" && campaign.selectedUserIds.isNotEmpty() ->
                    stringResource(R.string.admin_notif_stat_total, campaign.selectedUserIds.size)
                else ->
                    stringResource(R.string.admin_notif_stat_total_draft)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column {
                    Text(
                        text = totalRecipientsText,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        text = stringResource(R.string.admin_notif_stat_sent, campaign.sentCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.admin_notif_stat_read, campaign.readCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = stringResource(
                            R.string.admin_notif_field_audience_label,
                            "${campaign.audienceType}${if (campaign.targetRole != null) " (${campaign.targetRole})" else ""}"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (campaign.status == "draft") {
                    TextButton(onClick = onViewDetail) {
                        Text(stringResource(R.string.admin_notif_action_view_detail))
                    }
                    TextButton(onClick = onEdit) {
                        Text(stringResource(R.string.admin_notif_edit_heading))
                    }
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.admin_notif_action_cancel))
                    }
                    TextButton(onClick = onSchedule) {
                        Text(stringResource(R.string.admin_notif_action_schedule))
                    }
                    Button(onClick = onSendNow) {
                        Text(stringResource(R.string.admin_notif_action_send_now))
                    }
                } else if (campaign.status == "queued") {
                    TextButton(onClick = onViewDetail) {
                        Text(stringResource(R.string.admin_notif_action_view_detail))
                    }
                    TextButton(onClick = onEdit) {
                        Text(stringResource(R.string.admin_notif_edit_heading))
                    }
                    TextButton(onClick = onSchedule) {
                        Text(stringResource(R.string.admin_notif_action_schedule))
                    }
                    OutlinedButton(onClick = onCancel) {
                        Text(stringResource(R.string.admin_notif_action_cancel))
                    }
                    Button(onClick = onSendNow) {
                        Text(stringResource(R.string.admin_notif_action_send_now))
                    }
                } else {
                    OutlinedButton(onClick = onViewDetail) {
                        Text(stringResource(R.string.admin_notif_action_view_detail))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationComposerTabContent(
    uiState: AdminNotificationCampaignUiState,
    viewModel: AdminNotificationCampaignViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (uiState.editingCampaignId != null) {
                    stringResource(R.string.admin_notif_edit_heading)
                } else {
                    stringResource(R.string.admin_notif_composer_heading)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            if (uiState.editingCampaignId != null) {
                OutlinedButton(onClick = viewModel::resetComposer) {
                    Text(stringResource(R.string.admin_notif_action_reset_composer))
                }
            }
        }

        OutlinedTextField(
            value = uiState.draftTitleVi,
            onValueChange = viewModel::onTitleViChange,
            label = { Text(stringResource(R.string.admin_notif_field_title_vi)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = uiState.draftTitleEn,
            onValueChange = viewModel::onTitleEnChange,
            label = { Text(stringResource(R.string.admin_notif_field_title_en)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        OutlinedTextField(
            value = uiState.draftBodyVi,
            onValueChange = viewModel::onBodyViChange,
            label = { Text(stringResource(R.string.admin_notif_field_body_vi)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
        )

        OutlinedTextField(
            value = uiState.draftBodyEn,
            onValueChange = viewModel::onBodyEnChange,
            label = { Text(stringResource(R.string.admin_notif_field_body_en)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
        )

        // Delivery Locale Selector
        Text(
            text = stringResource(R.string.admin_notif_field_default_locale),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = uiState.draftLocale == "vi",
                onClick = { viewModel.onLocaleChange("vi") },
                label = { Text(stringResource(R.string.admin_notif_locale_vi)) }
            )
            FilterChip(
                selected = uiState.draftLocale == "en",
                onClick = { viewModel.onLocaleChange("en") },
                label = { Text(stringResource(R.string.admin_notif_locale_en)) }
            )
            FilterChip(
                selected = uiState.draftLocale == "bilingual",
                onClick = { viewModel.onLocaleChange("bilingual") },
                label = { Text(stringResource(R.string.admin_notif_locale_bilingual)) }
            )
        }

        // Audience Selector
        Text(
            text = stringResource(R.string.admin_notif_field_audience),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = uiState.draftAudienceType == "all",
                onClick = { viewModel.onAudienceTypeChange("all") },
                label = { Text(stringResource(R.string.admin_notif_aud_all)) }
            )
            FilterChip(
                selected = uiState.draftAudienceType == "role",
                onClick = { viewModel.onAudienceTypeChange("role") },
                label = { Text(stringResource(R.string.admin_notif_aud_role)) }
            )
            FilterChip(
                selected = uiState.draftAudienceType == "selected_users",
                onClick = { viewModel.onAudienceTypeChange("selected_users") },
                label = { Text(stringResource(R.string.admin_notif_aud_selected)) }
            )
        }

        // Role Selector if audience == role
        if (uiState.draftAudienceType == "role") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${stringResource(R.string.admin_notif_field_role)}:",
                    style = MaterialTheme.typography.bodyMedium
                )
                FilterChip(
                    selected = uiState.draftTargetRole == "member",
                    onClick = { viewModel.onTargetRoleChange("member") },
                    label = { Text(stringResource(R.string.admin_notif_role_member)) }
                )
                FilterChip(
                    selected = uiState.draftTargetRole == "moderator",
                    onClick = { viewModel.onTargetRoleChange("moderator") },
                    label = { Text(stringResource(R.string.admin_notif_role_moderator)) }
                )
                FilterChip(
                    selected = uiState.draftTargetRole == "admin",
                    onClick = { viewModel.onTargetRoleChange("admin") },
                    label = { Text(stringResource(R.string.admin_notif_role_admin)) }
                )
            }
        }

        // Selected Users Search & Chips if audience == selected_users
        if (uiState.draftAudienceType == "selected_users") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.admin_notif_selected_users_title, uiState.selectedUsers.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    // Chips of selected users
                    if (uiState.selectedUsers.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            uiState.selectedUsers.forEach { user ->
                                FilterChip(
                                    selected = true,
                                    onClick = { viewModel.removeSelectedUser(user.id) },
                                    label = { Text(user.displayName ?: user.email ?: user.id.take(8)) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.admin_notif_remove_user),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // Search input
                    OutlinedTextField(
                        value = uiState.userSearchQuery,
                        onValueChange = viewModel::onUserSearchQueryChange,
                        placeholder = { Text(stringResource(R.string.admin_notif_search_users_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    if (uiState.isUserSearching) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    } else if (uiState.userSearchResults.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            uiState.userSearchResults.forEach { user ->
                                val isSelected = uiState.selectedUsers.any { it.id == user.id }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
                                        .clickable {
                                            if (isSelected) viewModel.removeSelectedUser(user.id)
                                            else viewModel.addSelectedUser(user)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = user.displayName ?: stringResource(R.string.admin_notif_placeholder_user_unnamed),
                                            fontWeight = FontWeight.Medium,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = user.email ?: user.id,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            if (isSelected) viewModel.removeSelectedUser(user.id)
                                            else viewModel.addSelectedUser(user)
                                        }
                                    ) {
                                        Icon(
                                            if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.Add,
                                            contentDescription = null,
                                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (uiState.userSearchHasMore) {
                                TextButton(
                                    onClick = viewModel::loadMoreUserSearch,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.admin_notif_search_users_load_more))
                                }
                            }
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = uiState.draftReason,
            onValueChange = viewModel::onReasonChange,
            label = { Text(stringResource(R.string.admin_notif_field_reason)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        // Live Preview Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.admin_notif_preview_section),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.admin_notif_preview_recipients_count, uiState.previewRecipientCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                var previewTab by remember { mutableIntStateOf(0) }
                TabRow(selectedTabIndex = previewTab) {
                    Tab(
                        selected = previewTab == 0,
                        onClick = { previewTab = 0 },
                        text = { Text(stringResource(R.string.admin_notif_preview_effective_tab)) }
                    )
                    Tab(
                        selected = previewTab == 1,
                        onClick = { previewTab = 1 },
                        text = { Text(stringResource(R.string.admin_notif_preview_vi_tab)) }
                    )
                    Tab(
                        selected = previewTab == 2,
                        onClick = { previewTab = 2 },
                        text = { Text(stringResource(R.string.admin_notif_preview_en_tab)) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                val (pTitle, pBody) = when (previewTab) {
                    1 -> uiState.previewViTitle to uiState.previewViBody
                    2 -> uiState.previewEnTitle to uiState.previewEnBody
                    else -> uiState.previewEffectiveTitle to uiState.previewEffectiveBody
                }

                Text(
                    text = pTitle.ifBlank { stringResource(R.string.admin_notif_placeholder_title_empty) },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pBody.ifBlank { stringResource(R.string.admin_notif_placeholder_body_empty) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = viewModel::saveDraft,
                enabled = !uiState.isActionInProgress,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isDraftSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(
                        if (uiState.editingCampaignId != null) {
                            stringResource(R.string.admin_notif_action_update_draft)
                        } else {
                            stringResource(R.string.admin_notif_action_save_draft)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationMonitorTabContent(
    uiState: AdminNotificationCampaignUiState,
    viewModel: AdminNotificationCampaignViewModel,
) {
    val health = uiState.schedulerHealth

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.admin_notif_monitor_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        // Scheduler Readiness Banner
        val isReady = health?.schedulerReady ?: false
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isReady) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                } else {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                }
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    if (isReady) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (isReady) {
                        stringResource(R.string.admin_notif_scheduler_ready)
                    } else {
                        stringResource(R.string.admin_notif_scheduler_unready)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Metrics Breakdown Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(
                        R.string.admin_notif_cron_status,
                        if (health?.pgCronAvailable == true && health.pgCronJobActive) {
                            stringResource(R.string.admin_notif_cron_active)
                        } else {
                            stringResource(R.string.admin_notif_cron_unavailable)
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    text = stringResource(R.string.admin_notif_pending_due_count, health?.pendingDueCampaignsCount ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    text = stringResource(R.string.admin_notif_inflight_count, health?.inFlightCampaignsCount ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    text = stringResource(
                        R.string.admin_notif_last_worker_run,
                        health?.lastWorkerRunAt ?: stringResource(R.string.admin_notif_label_worker_never)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    text = stringResource(R.string.admin_notif_lifetime_processed, health?.lifetimeCampaignsProcessed ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Text(
                    text = stringResource(R.string.admin_notif_lifetime_sent, health?.lifetimeNotificationsSent ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Manual Process Due Trigger
        Button(
            onClick = viewModel::openProcessDueDialog,
            enabled = !uiState.isActionInProgress && !uiState.isProcessingDue,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isProcessingDue) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.admin_notif_action_process_due))
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (bgColor, textColor, textRes) = when (status) {
        "draft" -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, R.string.admin_notif_status_draft)
        "queued" -> Triple(MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer, R.string.admin_notif_status_queued)
        "sending" -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, R.string.admin_notif_status_sending)
        "completed" -> Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), R.string.admin_notif_status_completed)
        "cancelled" -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, R.string.admin_notif_status_cancelled)
        else -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer, R.string.admin_notif_status_failed)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

@Composable
private fun NotificationDialogHost(
    uiState: AdminNotificationCampaignUiState,
    viewModel: AdminNotificationCampaignViewModel,
) {
    when (val dialog = uiState.activeDialog) {
        is NotificationActionDialog.Schedule -> {
            var dateInput by remember { mutableStateOf(dialog.scheduledDate) }
            var timeInput by remember { mutableStateOf(dialog.scheduledTime) }
            var reasonInput by remember { mutableStateOf(dialog.reason) }

            AlertDialog(
                onDismissRequest = viewModel::dismissDialog,
                title = { Text(stringResource(R.string.admin_notif_schedule_dialog_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "${dialog.campaign.titleVi} (${dialog.campaign.totalRecipients} ${stringResource(R.string.admin_notif_aud_selected).lowercase()})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedTextField(
                            value = dateInput,
                            onValueChange = {
                                dateInput = it
                                viewModel.onScheduleFieldsChanged(dateInput, timeInput, reasonInput)
                            },
                            label = { Text(stringResource(R.string.admin_notif_schedule_date)) },
                            placeholder = { Text("2026-11-02") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = timeInput,
                            onValueChange = {
                                timeInput = it
                                viewModel.onScheduleFieldsChanged(dateInput, timeInput, reasonInput)
                            },
                            label = { Text(stringResource(R.string.admin_notif_schedule_time)) },
                            placeholder = { Text("14:30") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = reasonInput,
                            onValueChange = {
                                reasonInput = it
                                viewModel.onScheduleFieldsChanged(dateInput, timeInput, reasonInput)
                            },
                            label = { Text(stringResource(R.string.admin_notif_field_reason)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.executeSchedule(
                                campaign = dialog.campaign,
                                dateStr = dateInput,
                                timeStr = timeInput,
                                reason = reasonInput,
                                requestId = dialog.requestId,
                                dialogOwner = dialog.ownerUid,
                            )
                        },
                        enabled = dateInput.isNotBlank() && timeInput.isNotBlank()
                    ) {
                        Text(
                            if (dialog.isRetry) stringResource(R.string.admin_notif_btn_retry)
                            else stringResource(R.string.admin_notif_btn_confirm)
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissDialog) {
                        Text(stringResource(R.string.admin_notif_btn_dismiss))
                    }
                }
            )
        }

        is NotificationActionDialog.SendNow -> {
            var reasonInput by remember { mutableStateOf(dialog.reason) }
            AlertDialog(
                onDismissRequest = viewModel::dismissDialog,
                title = { Text(stringResource(R.string.admin_notif_send_now_confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.admin_notif_send_now_confirm_body, dialog.campaign.totalRecipients))
                        OutlinedTextField(
                            value = reasonInput,
                            onValueChange = {
                                reasonInput = it
                                viewModel.onSendNowReasonChanged(it)
                            },
                            label = { Text(stringResource(R.string.admin_notif_field_reason)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.executeSendNow(
                                campaign = dialog.campaign,
                                reason = reasonInput,
                                requestId = dialog.requestId,
                                dialogOwner = dialog.ownerUid,
                            )
                        }
                    ) {
                        Text(
                            if (dialog.isRetry) stringResource(R.string.admin_notif_btn_retry)
                            else stringResource(R.string.admin_notif_btn_confirm)
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissDialog) {
                        Text(stringResource(R.string.admin_notif_btn_dismiss))
                    }
                }
            )
        }

        is NotificationActionDialog.Cancel -> {
            var reasonInput by remember { mutableStateOf(dialog.reason) }
            AlertDialog(
                onDismissRequest = viewModel::dismissDialog,
                title = { Text(stringResource(R.string.admin_notif_cancel_confirm_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.admin_notif_cancel_confirm_body))
                        OutlinedTextField(
                            value = reasonInput,
                            onValueChange = {
                                reasonInput = it
                                viewModel.onCancelReasonChanged(it)
                            },
                            label = { Text(stringResource(R.string.admin_notif_cancel_reason_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            isError = reasonInput.isBlank()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.executeCancel(
                                campaign = dialog.campaign,
                                reason = reasonInput,
                                requestId = dialog.requestId,
                                dialogOwner = dialog.ownerUid,
                            )
                        },
                        enabled = reasonInput.isNotBlank()
                    ) {
                        Text(
                            if (dialog.isRetry) stringResource(R.string.admin_notif_btn_retry)
                            else stringResource(R.string.admin_notif_btn_confirm)
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissDialog) {
                        Text(stringResource(R.string.admin_notif_btn_dismiss))
                    }
                }
            )
        }

        is NotificationActionDialog.ProcessDue -> {
            AlertDialog(
                onDismissRequest = viewModel::dismissDialog,
                title = { Text(stringResource(R.string.admin_notif_action_process_due)) },
                text = { Text(stringResource(R.string.admin_notif_confirm_process_due)) },
                confirmButton = {
                    Button(onClick = viewModel::processDueBatches) {
                        Text(stringResource(R.string.admin_notif_btn_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissDialog) {
                        Text(stringResource(R.string.admin_notif_btn_dismiss))
                    }
                }
            )
        }

        null -> Unit
    }
}

@Composable
private fun CampaignDetailDialog(
    detail: com.ntt.crosstest.data.AdminNotificationCampaignDetailResponse,
    uiState: AdminNotificationCampaignUiState,
    viewModel: AdminNotificationCampaignViewModel,
    onDismiss: () -> Unit,
) {
    val camp = detail.campaign

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(camp.titleVi, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusBadge(status = camp.status)

                Text(
                    text = stringResource(R.string.admin_notif_label_english_version, camp.titleEn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Counts
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.admin_notif_stat_total, camp.totalRecipients))
                    Text(stringResource(R.string.admin_notif_stat_sent, camp.sentCount))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(stringResource(R.string.admin_notif_stat_read, camp.readCount))
                        Text(
                            text = stringResource(R.string.admin_notif_stat_read_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(stringResource(R.string.admin_notif_stat_failed, camp.failedCount))
                }

                // Recipients Section
                Text(
                    text = stringResource(R.string.admin_notif_recipients_list_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                if (uiState.detailRecipients.isEmpty()) {
                    Text(
                        text = stringResource(R.string.admin_notif_empty_recipients),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        uiState.detailRecipients.forEach { recip ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = recip.userDisplayName ?: recip.userEmail ?: recip.userId.take(8),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Status: ${recip.status}${if (recip.isRead) " " + stringResource(R.string.admin_notif_status_read_badge) else ""}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (recip.isRead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (uiState.detailRecipientsHasMore) {
                            TextButton(
                                onClick = viewModel::loadMoreRecipients,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.admin_notif_load_more))
                            }
                        }
                    }
                }

                // Audit Events Section
                if (detail.events.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.admin_notif_events_list_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        detail.events.forEach { ev ->
                            Text(
                                text = "• [${ev.action}] ${ev.createdAt} ${if (ev.reason != null) "- ${ev.reason}" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.admin_notif_btn_close))
            }
        }
    )
}
