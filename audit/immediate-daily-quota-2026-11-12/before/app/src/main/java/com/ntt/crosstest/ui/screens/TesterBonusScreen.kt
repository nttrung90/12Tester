package com.ntt.crosstest.ui.screens

import android.widget.Toast
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
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.GetTesterBonusResult
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.ui.components.AppIcon
import com.ntt.crosstest.ui.theme.statusColors
import com.ntt.crosstest.ui.viewmodels.TesterBonusUiState
import com.ntt.crosstest.ui.viewmodels.TesterBonusViewModel

@Composable
fun TesterBonusScreen(
    campaignId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TesterBonusViewModel = run {
        val app = LocalContext.current.applicationContext as TestCheoApp
        viewModel(
            key = "tester_bonus_$campaignId",
            factory = viewModelFactory {
                initializer {
                    TesterBonusViewModel(
                        campaignId = campaignId,
                        repository = app.campaignRepository,
                        localizer = Localizer(app),
                        context = app,
                    )
                }
            }
        )
    },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.message) {
        uiState.message?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { err ->
            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    TesterBonusScreenContent(
        uiState = uiState,
        onBack = onBack,
        onRewardCreditsChanged = viewModel::onRewardCreditsChanged,
        onMaxRecipientsChanged = viewModel::onMaxRecipientsChanged,
        onAgree = viewModel::submitCreateBonus,
        onRetryUnresolved = viewModel::retryUnresolvedIntent,
        modifier = modifier,
    )
}

/**
 * Pure screen content composable separated from network ViewModel.
 * Enables deterministic Robolectric / native graphics screenshot rendering without network/login fixtures.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TesterBonusScreenContent(
    uiState: TesterBonusUiState,
    onBack: () -> Unit,
    onRewardCreditsChanged: (String) -> Unit = {},
    onMaxRecipientsChanged: (String) -> Unit = {},
    onAgree: () -> Unit = {},
    onRetryUnresolved: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val statusColors = MaterialTheme.statusColors

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.tester_bonus_screen_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.tester_bonus_btn_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 1. App Header Info Card
                uiState.campaign?.let { campaign ->
                    AppHeaderCard(campaign = campaign)
                }

                // 2. Current Wallet Balance Card
                WalletBalanceCard(balance = uiState.currentBalance)

                // 3. Main Content: Existing Round View OR Create Form
                val roundToDisplay = uiState.existingRound ?: uiState.confirmedResult?.let { res ->
                    GetTesterBonusResult(
                        hasRound = true,
                        isOwner = true,
                        roundId = res.roundId,
                        campaignId = res.campaignId,
                        rewardCredits = res.rewardCredits,
                        maxRecipients = res.maxRecipients,
                        paidCount = res.paidCount,
                        remainingSlots = res.remainingSlots,
                        status = res.status ?: "active",
                        reservedCredits = res.reservedCredits,
                        remainingCredits = res.remainingCredits,
                        spentCredits = res.spentCredits,
                        refundedCredits = res.refundedCredits,
                    )
                }

                if (uiState.hasActiveOrClosingRound && roundToDisplay != null) {
                    ExistingRoundProgressCard(
                        round = roundToDisplay,
                        onBack = onBack,
                    )
                } else {
                    if ((uiState.hasTerminalRound || uiState.campaign == null) && roundToDisplay != null) {
                        ExistingRoundProgressCard(
                            round = roundToDisplay,
                            onBack = if (uiState.campaign == null) onBack else null,
                        )
                        if (uiState.campaign != null) {
                            Text(
                                text = stringResource(R.string.tester_bonus_terminal_round_note),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    if (uiState.campaign != null && !uiState.isCampaignNotFound) {
                        CreateBonusFormCard(
                            uiState = uiState,
                            onRewardCreditsChanged = onRewardCreditsChanged,
                            onMaxRecipientsChanged = onMaxRecipientsChanged,
                            onAgree = onAgree,
                            onCancel = onBack,
                            onRetryUnresolved = onRetryUnresolved,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeaderCard(campaign: Campaign) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                iconUrl = campaign.safeIconUrl,
                packageName = campaign.packageName,
                appName = campaign.appName,
                size = 50.dp,
                shapeRadius = 12.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = campaign.appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = campaign.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WalletBalanceCard(balance: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.AccountBalanceWallet,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = stringResource(R.string.tester_bonus_wallet_balance_format, balance),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ExistingRoundProgressCard(
    round: GetTesterBonusResult,
    onBack: (() -> Unit)? = null,
) {
    val statusColors = MaterialTheme.statusColors
    val totalSlots = round.maxRecipients
    val paidCount = round.paidCount
    val progress = if (totalSlots > 0) (paidCount.toFloat() / totalSlots.toFloat()).coerceIn(0f, 1f) else 0f

    val surface = MaterialTheme.colorScheme.surface
    val isDark = (0.299f * surface.red + 0.587f * surface.green + 0.114f * surface.blue) < 0.5f
    val orangeAccent = if (isDark) Color(0xFFFFB74D) else Color(0xFFB54700)
    val onOrangeAccent = if (isDark) Color(0xFF211100) else Color.White

    val (statusLabel, statusBg, statusFg) = when (round.status) {
        "active" -> Triple(stringResource(R.string.tester_bonus_status_active), statusColors.successContainer, statusColors.onSuccessContainer)
        "closing" -> Triple(stringResource(R.string.tester_bonus_status_closing), statusColors.warningContainer, statusColors.onWarningContainer)
        "exhausted" -> Triple(stringResource(R.string.tester_bonus_status_exhausted), statusColors.infoContainer, statusColors.onInfoContainer)
        else -> Triple(stringResource(R.string.tester_bonus_status_closed), MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header with status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CardGiftcard,
                        contentDescription = null,
                        tint = orangeAccent,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.tester_bonus_active_round_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusBg)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusFg,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Reward rate & progress numbers
            Text(
                text = stringResource(
                    R.string.tester_bonus_progress_format,
                    paidCount,
                    totalSlots,
                    round.remainingSlots,
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )

            // Progress Bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = orangeAccent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Budget Breakdown
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.tester_bonus_reserved_budget_format, round.reservedCredits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.tester_bonus_spent_budget_format, round.spentCredits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.tester_bonus_remaining_budget_format, round.remainingCredits),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (round.refundedCredits > 0L) {
                    Text(
                        text = stringResource(R.string.tester_bonus_refunded_budget_format, round.refundedCredits),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColors.success,
                    )
                }
            }

            // Terms Note
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.tester_bonus_terms_condition),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (onBack != null) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(text = stringResource(R.string.tester_bonus_btn_back))
                }
            }
        }
    }
}

@Composable
private fun CreateBonusFormCard(
    uiState: TesterBonusUiState,
    onRewardCreditsChanged: (String) -> Unit,
    onMaxRecipientsChanged: (String) -> Unit,
    onAgree: () -> Unit,
    onCancel: () -> Unit,
    onRetryUnresolved: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = stringResource(R.string.tester_bonus_screen_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            // Warning for server update required (missing RPC)
            if (uiState.isNotSupported) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(imageVector = Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            text = stringResource(R.string.tester_bonus_err_not_supported),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            // Warning for corrupted quarantine
            if (uiState.isStoreCorrupted) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(imageVector = Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                text = uiState.error ?: stringResource(R.string.tester_bonus_err_store_corrupted),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            // Unresolved pending banner
            if (uiState.hasUnresolvedIntent && uiState.pendingIntent != null) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.statusColors.warningContainer),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(imageVector = Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.statusColors.warning)
                            Text(
                                text = stringResource(R.string.tester_bonus_unresolved_prompt),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.statusColors.onWarningContainer,
                            )
                        }
                        Button(
                            onClick = onRetryUnresolved,
                            enabled = !uiState.isActionLoading && !uiState.isReconciling,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.statusColors.warning),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (uiState.isActionLoading || uiState.isReconciling) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                } else {
                                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Text(stringResource(R.string.tester_bonus_retry_create))
                            }
                        }
                    }
                }
            }

            // Input 1: Credit per tester
            OutlinedTextField(
                value = uiState.rewardCreditsInput,
                onValueChange = onRewardCreditsChanged,
                label = { Text(stringResource(R.string.tester_bonus_reward_input_label)) },
                placeholder = { Text(stringResource(R.string.tester_bonus_reward_input_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.hasUnresolvedIntent && !uiState.isActionLoading,
                shape = RoundedCornerShape(10.dp),
            )

            // Input 2: Maximum people
            OutlinedTextField(
                value = uiState.maxRecipientsInput,
                onValueChange = onMaxRecipientsChanged,
                label = { Text(stringResource(R.string.tester_bonus_max_recipients_label)) },
                placeholder = { Text(stringResource(R.string.tester_bonus_max_recipients_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.hasUnresolvedIntent && !uiState.isActionLoading,
                shape = RoundedCornerShape(10.dp),
            )

            // Validation Error if any
            if (uiState.inputValidationError != null) {
                Text(
                    text = uiState.inputValidationError,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }

            // Live Budget Summary Card
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val reward = uiState.rewardCreditsInput.trim().toLongOrNull() ?: 0L
                    val recipients = uiState.maxRecipientsInput.trim().toLongOrNull() ?: 0L

                    val surface = MaterialTheme.colorScheme.surface
    val isDark = (0.299f * surface.red + 0.587f * surface.green + 0.114f * surface.blue) < 0.5f
                    val orangeAccent = if (isDark) Color(0xFFFFB74D) else Color(0xFFB54700)
                    val onOrangeAccent = if (isDark) Color(0xFF211100) else Color.White

                    Text(
                        text = stringResource(
                            R.string.tester_bonus_budget_calc_format,
                            reward,
                            recipients,
                            uiState.calculatedBudget,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = orangeAccent,
                    )

                    Text(
                        text = stringResource(R.string.tester_bonus_expected_balance_format, uiState.expectedBalance),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (uiState.expectedBalance < 0L) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Terms and conditions
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.tester_bonus_terms_condition),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Action Buttons: Hủy (Cancel) and Đồng ý (Agree)
            val surface = MaterialTheme.colorScheme.surface
    val isDark = (0.299f * surface.red + 0.587f * surface.green + 0.114f * surface.blue) < 0.5f
            val orangeAccent = if (isDark) Color(0xFFFFB74D) else Color(0xFFB54700)
            val onOrangeAccent = if (isDark) Color(0xFF211100) else Color.White

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    enabled = !uiState.isActionLoading && !uiState.isReconciling,
                ) {
                    Text(text = stringResource(R.string.tester_bonus_btn_cancel))
                }

                Button(
                    onClick = onAgree,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    enabled = uiState.isFormValid && !uiState.isActionLoading && !uiState.isReconciling && !uiState.hasUnresolvedIntent,
                    colors = ButtonDefaults.buttonColors(containerColor = orangeAccent, contentColor = onOrangeAccent),
                ) {
                    if (uiState.isActionLoading || uiState.isReconciling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.tester_bonus_btn_agree),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
