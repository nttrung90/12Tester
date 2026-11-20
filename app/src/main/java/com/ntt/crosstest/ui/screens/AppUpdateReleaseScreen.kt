package com.ntt.crosstest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.AppUpdateRelease
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.ui.theme.statusColors
import com.ntt.crosstest.ui.viewmodels.AppUpdateReleaseViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppUpdateReleaseScreen(
    campaignId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppUpdateReleaseViewModel = run {
        val app = LocalContext.current.applicationContext as TestCheoApp
        viewModel(
            key = "app_update_release_$campaignId",
            factory = viewModelFactory {
                initializer {
                    AppUpdateReleaseViewModel(
                        campaignId = campaignId,
                        repository = app.campaignRepository,
                        localizer = Localizer(app),
                    )
                }
            }
        )
    },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCloseConfirmDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_update_screen_title),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.oad_refresh_cd),
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { paddingValues ->
        if (uiState.isLoading && uiState.campaign == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header: App Info
                uiState.campaign?.let { camp ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!camp.iconUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = camp.iconUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Surface(
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Filled.SystemUpdate,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = camp.appName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = camp.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                // Card: Đợt cập nhật hiện tại (nếu có)
                uiState.currentRelease?.let { rel ->
                    CurrentReleaseCard(
                        release = rel,
                        isClosing = uiState.isClosing,
                        onCloseRoundClick = { showCloseConfirmDialog = true },
                    )
                }

                // Card: Form công bố phiên bản mới
                PublishReleaseFormCard(
                    uiState = uiState,
                    onVersionNameChange = viewModel::onVersionNameChange,
                    onVersionCodeChange = viewModel::onVersionCodeChange,
                    onReleaseNotesChange = viewModel::onReleaseNotesChange,
                    onApplyClick = viewModel::publishUpdate,
                    onRetryPendingClick = viewModel::retryPendingPublish,
                    onDiscardPendingClick = viewModel::discardPendingPublish,
                )

                // Client Verification Notice
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(top = 2.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.app_update_client_limit_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Card: Lịch sử cập nhật
                if (uiState.releaseHistory.isNotEmpty()) {
                    ReleaseHistoryCard(history = uiState.releaseHistory)
                }
            }
        }
    }

    // Confirmation dialog for closing current round early
    if (showCloseConfirmDialog && uiState.currentRelease != null) {
        val rel = uiState.currentRelease!!
        AlertDialog(
            onDismissRequest = { if (!uiState.isClosing) showCloseConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.app_update_close_dialog_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.app_update_close_dialog_desc_format,
                        rel.remainingCredits,
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCloseConfirmDialog = false
                        viewModel.closeCurrentRelease()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !uiState.isClosing,
                ) {
                    if (uiState.isClosing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCloseConfirmDialog = false },
                    enabled = !uiState.isClosing,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun CurrentReleaseCard(
    release: AppUpdateRelease,
    isClosing: Boolean,
    onCloseRoundClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (release.status == "active") {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
        ),
        border = BorderStroke(
            1.dp,
            if (release.status == "active") MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.NewReleases,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.app_update_current_round),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (release.status) {
                        "active" -> MaterialTheme.statusColors.successContainer
                        "exhausted" -> MaterialTheme.statusColors.infoContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = when (release.status) {
                            "active" -> stringResource(R.string.app_update_status_active)
                            "exhausted" -> stringResource(R.string.app_update_status_exhausted)
                            "superseded" -> stringResource(R.string.app_update_status_superseded)
                            else -> stringResource(R.string.app_update_status_closed)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when (release.status) {
                            "active" -> MaterialTheme.statusColors.onSuccessContainer
                            "exhausted" -> MaterialTheme.statusColors.onInfoContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                text = stringResource(
                    R.string.app_update_version_code_format,
                    release.versionName,
                    release.targetVersionCode
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (release.releaseNotes.isNotBlank()) {
                Text(
                    text = release.releaseNotes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Progress bar
            val releaseMaxClaims = maxOf(1, release.maxClaims)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${release.paidCount}/$releaseMaxClaims đã nhận thưởng",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${(release.paidCount * 100) / releaseMaxClaims}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                LinearProgressIndicator(
                    progress = { (release.paidCount.toFloat() / releaseMaxClaims.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }

            // Budget info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.app_update_held_format, release.remainingCredits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.statusColors.warning,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.app_update_spent_format, release.paidCount * 6),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (release.status == "active") {
                OutlinedButton(
                    onClick = onCloseRoundClick,
                    enabled = !isClosing,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isClosing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = stringResource(R.string.app_update_close_round_btn),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PublishReleaseFormCard(
    uiState: com.ntt.crosstest.ui.viewmodels.AppUpdateReleaseUiState,
    onVersionNameChange: (String) -> Unit,
    onVersionCodeChange: (String) -> Unit,
    onReleaseNotesChange: (String) -> Unit,
    onApplyClick: () -> Unit,
    onRetryPendingClick: (() -> Unit)? = null,
    onDiscardPendingClick: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.app_update_form_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            uiState.pendingPublishIntent?.let { pending ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Yêu cầu đăng ký phiên bản ${pending.versionName} (mã ${pending.targetVersionCode}) đang chờ giải quyết sau sự cố mạng.",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            onDiscardPendingClick?.let {
                                TextButton(onClick = it, enabled = !uiState.isPublishing) {
                                    Text("Hủy bỏ")
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            onRetryPendingClick?.let {
                                Button(onClick = it, enabled = !uiState.isPublishing) {
                                    Text("Thử lại gửi yêu cầu")
                                }
                            }
                        }
                    }
                }
            }

            // Version Name Field
            OutlinedTextField(
                value = uiState.versionName,
                onValueChange = onVersionNameChange,
                label = { Text(stringResource(R.string.app_update_version_name_label)) },
                placeholder = { Text(stringResource(R.string.app_update_version_name_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            // Version Code Field
            val currentHighest = uiState.currentRelease?.targetVersionCode ?: 0L
            OutlinedTextField(
                value = uiState.versionCode,
                onValueChange = onVersionCodeChange,
                label = { Text(stringResource(R.string.app_update_version_code_label)) },
                placeholder = { Text(stringResource(R.string.app_update_version_code_placeholder)) },
                supportingText = {
                    Text(
                        text = if (currentHighest > 0) {
                            stringResource(R.string.app_update_version_code_hint_greater_format, currentHighest)
                        } else {
                            stringResource(R.string.app_update_version_code_hint)
                        }
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            // Release Notes Field
            OutlinedTextField(
                value = uiState.releaseNotes,
                onValueChange = onReleaseNotesChange,
                label = { Text(stringResource(R.string.app_update_features_label)) },
                placeholder = { Text(stringResource(R.string.app_update_features_placeholder)) },
                supportingText = {
                    Text(
                        text = "${uiState.releaseNotes.length}/4000",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )

            // Budget Breakdown Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Giữ ${uiState.reservedBudget} Credit • ${uiState.rewardPerClaim} Credit/người • Tối đa ${uiState.maxClaims} người",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(2.dp))
                    BudgetRow(
                        label = stringResource(R.string.app_update_available_balance),
                        value = "${uiState.availableCredits} Credit",
                    )
                    if (uiState.refundFromCurrent > 0) {
                        BudgetRow(
                            label = stringResource(R.string.app_update_refund_from_old),
                            value = "+${uiState.refundFromCurrent} Credit",
                            valueColor = MaterialTheme.statusColors.success,
                        )
                    }
                    BudgetRow(
                        label = stringResource(R.string.app_update_reserve_cost),
                        value = "-${uiState.reservedBudget} Credit",
                        valueColor = MaterialTheme.colorScheme.error,
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    BudgetRow(
                        label = stringResource(R.string.app_update_projected_balance),
                        value = "${uiState.projectedRemaining} Credit",
                        valueColor = if (uiState.projectedRemaining >= 0) MaterialTheme.statusColors.success else MaterialTheme.colorScheme.error,
                        isBold = true,
                    )

                    if (!uiState.hasEnoughCredits) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Số dư không đủ để giữ ${uiState.reservedBudget} Credit (cần thêm ${uiState.reservedBudget - uiState.effectiveBudget} Credit).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    if (uiState.quoteError != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = uiState.quoteError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // Apply Button
            Button(
                onClick = onApplyClick,
                enabled = uiState.canPublish,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (uiState.isPublishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = stringResource(R.string.app_update_apply_btn),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun BudgetRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,
    isBold: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isBold) FontWeight.Bold else FontWeight.Medium,
            color = valueColor,
        )
    }
}

@Composable
private fun ReleaseHistoryCard(
    history: List<AppUpdateRelease>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.app_update_history_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            history.forEachIndexed { index, rel ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "v${rel.versionName} (${rel.targetVersionCode})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = when (rel.status) {
                                "active" -> stringResource(R.string.app_update_status_active)
                                "exhausted" -> stringResource(R.string.app_update_status_exhausted)
                                "superseded" -> stringResource(R.string.app_update_status_superseded)
                                else -> stringResource(R.string.app_update_status_closed)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = when (rel.status) {
                                "active" -> MaterialTheme.statusColors.success
                                "exhausted" -> MaterialTheme.statusColors.info
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    val refundText = if (rel.refundedCredits > 0) {
                        stringResource(R.string.app_update_history_refund_suffix, rel.refundedCredits)
                    } else ""
                    Text(
                        text = stringResource(
                            R.string.app_update_history_item_format,
                            rel.paidCount,
                            rel.paidCount * 6
                        ) + refundText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
