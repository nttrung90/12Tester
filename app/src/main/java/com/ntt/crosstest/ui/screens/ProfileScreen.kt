package com.ntt.crosstest.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.ntt.crosstest.data.SlotInfo
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.SettingsRepository
import com.ntt.crosstest.ui.components.CampaignCard
import com.ntt.crosstest.ui.viewmodels.ProfileViewModel

@Composable
fun ProfileScreen(
    modifier: Modifier = Modifier,
    onCampaignClick: (String) -> Unit,
    onOwnerAppClick: (String) -> Unit = onCampaignClick,
    onAdminPanelClick: () -> Unit = {},
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TestCheoApp
    val viewModel: ProfileViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ProfileViewModel(app.authRepository, app.campaignRepository, app)
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showLogoutDialog by rememberSaveable { mutableStateOf(false) }
    var showCreditHistoryDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showChangePasswordDialog by rememberSaveable { mutableStateOf(false) }
    var showSlotsDialog by rememberSaveable { mutableStateOf(false) }
    var slotToUnlock by remember { mutableStateOf<SlotInfo?>(null) }

    LaunchedEffect(uiState.signedOut) {
        if (uiState.signedOut) onSignedOut()
    }

    LaunchedEffect(uiState.changePasswordSuccessMsg) {
        uiState.changePasswordSuccessMsg?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearChangePasswordSuccess()
            showChangePasswordDialog = false
        }
    }

    LaunchedEffect(uiState.unlockSuccessMsg) {
        uiState.unlockSuccessMsg?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearUnlockSuccess()
            slotToUnlock = null
        }
    }

    if (showSlotsDialog) {
        SlotsManagementDialog(
            slots = uiState.slots,
            userCredits = uiState.profile?.credits ?: 0,
            onUnlockClick = { slot -> slotToUnlock = slot },
            onDismiss = { showSlotsDialog = false },
        )
    }

    slotToUnlock?.let { slot ->
        val cost = slot.cost
        val userCredits = uiState.profile?.credits ?: 0
        val hasEnoughCredits = userCredits >= cost
        val isPrevSlotActive = slot.slotNumber <= 2 ||
                uiState.slots.find { it.slotNumber == slot.slotNumber - 1 }?.isAvailable == true ||
                uiState.slots.find { it.slotNumber == slot.slotNumber - 1 }?.isOccupied == true

        AlertDialog(
            onDismissRequest = { if (!uiState.isUnlockingSlot) slotToUnlock = null },
            title = {
                Text(
                    text = if (slot.isUnlocked && !slot.isExpired)
                        stringResource(R.string.slot_renew_dialog_title, slot.slotNumber)
                    else
                        stringResource(R.string.slot_unlock_dialog_title, slot.slotNumber),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (slot.isUnlocked && !slot.isExpired)
                            stringResource(R.string.slot_renew_dialog_desc, slot.slotNumber, cost)
                        else
                            stringResource(R.string.slot_unlock_dialog_desc, slot.slotNumber, cost),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Thời hạn sử dụng:", style = MaterialTheme.typography.bodySmall)
                                Text("16 ngày", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Chi phí:", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "$cost Credit",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Số dư hiện tại:", style = MaterialTheme.typography.bodySmall)
                                Text("$userCredits Credit", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    if (!hasEnoughCredits) {
                        Text(
                            text = stringResource(R.string.error_insufficient_credits_for_slot, cost, userCredits),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }

                    if (!isPrevSlotActive) {
                        Text(
                            text = stringResource(R.string.error_slot_previous_not_active, slot.slotNumber - 1, slot.slotNumber),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.unlockOrRenewSlot(slot.slotNumber) },
                    enabled = hasEnoughCredits && isPrevSlotActive && !uiState.isUnlockingSlot,
                ) {
                    if (uiState.isUnlockingSlot) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (slot.isUnlocked && !slot.isExpired)
                            stringResource(R.string.slot_renew_confirm_btn, cost)
                        else
                            stringResource(R.string.slot_unlock_confirm_btn, cost)
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { slotToUnlock = null },
                    enabled = !uiState.isUnlockingSlot,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showLanguageDialog) {
        LanguageSelectionDialog(
            currentLanguage = uiState.appLanguage,
            onLanguageSelected = viewModel::setAppLanguage,
            onDismiss = { showLanguageDialog = false },
        )
    }

    if (showCreditHistoryDialog) {
        CreditHistoryDialog(
            logs = uiState.creditLogs,
            currentBalance = uiState.profile?.credits ?: 100,
            onDismiss = { showCreditHistoryDialog = false }
        )
    }

    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            isLoading = uiState.isChangingPassword,
            onConfirm = { newPass, confirmPass ->
                viewModel.changePassword(newPass, confirmPass) {
                    showChangePasswordDialog = false
                }
            },
            onDismiss = { showChangePasswordDialog = false }
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.logout)) },
            text = { Text(stringResource(R.string.logout_confirm)) },
            confirmButton = {
                TextButton(onClick = { showLogoutDialog = false; viewModel.signOut() }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (uiState.isLoading && uiState.profile == null && uiState.isAuthenticated) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (!uiState.isAuthenticated) {
        // Guest mode profile screen with Sign-in card
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.guest_mode_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.guest_mode_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(20.dp))
                        Button(
                            onClick = { viewModel.signIn(context) },
                            enabled = !uiState.isActionLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (uiState.isActionLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.sign_in_google))
                            }
                        }
                        if (uiState.error != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = uiState.error ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            item {
                SettingsSection(
                    currentLanguage = uiState.appLanguage,
                    onLanguageClick = { showLanguageDialog = true },
                    reminderEnabled = uiState.reminderEnabled,
                    onReminderChange = viewModel::setReminderEnabled,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ProfileHeader(
                displayName = uiState.profile?.displayName,
                email = uiState.profile?.email ?: uiState.email,
                credits = uiState.profile?.credits ?: 100,
                missedStreak = uiState.profile?.missedStreak ?: 0,
                isAdmin = uiState.profile?.isAdmin == true,
                onCreditClick = { showCreditHistoryDialog = true },
            )
        }
        if (uiState.profile?.isAdmin == true) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onAdminPanelClick() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AdminPanelSettings,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.admin_panel_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("👑", fontSize = 16.sp)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.admin_panel_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            val unlockedCount = uiState.slots.count { it.isPermanent || (it.isUnlocked && !it.isExpired) }.coerceAtLeast(1)
            val inUseCount = uiState.slots.count { it.isOccupied }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showSlotsDialog = true },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                ),
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Apps,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.slot_manage_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Đang mở: $unlockedCount/6 slot ($inUseCount slot đang có app)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SettingsSection(
                currentLanguage = uiState.appLanguage,
                onLanguageClick = { showLanguageDialog = true },
                reminderEnabled = uiState.reminderEnabled,
                onReminderChange = viewModel::setReminderEnabled,
                onChangePasswordClick = { showChangePasswordDialog = true },
            )
        }
        item { HorizontalDivider() }
        item {
            Text(
                text = stringResource(R.string.created_by_me),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (uiState.myCampaigns.isEmpty()) {
            item { MutedText(stringResource(R.string.empty_list)) }
        } else {
            itemsIndexed(uiState.myCampaigns, key = { index, campaign -> "my_${campaign.id ?: campaign.packageName}_$index" }) { _, campaign ->
                CampaignCard(campaign, onClick = { campaign.id?.let(onOwnerAppClick) })
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.joined_by_me),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (uiState.joinedCampaigns.isEmpty()) {
            item { MutedText(stringResource(R.string.empty_list)) }
        } else {
            itemsIndexed(uiState.joinedCampaigns, key = { index, campaign -> "joined_${campaign.id ?: campaign.packageName}_$index" }) { _, campaign ->
                CampaignCard(campaign, onClick = { campaign.id?.let(onCampaignClick) })
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.logout))
            }
            Spacer(Modifier.height(72.dp))
        }
    }
}

@Composable
private fun ProfileHeader(
    displayName: String?,
    email: String?,
    credits: Int,
    missedStreak: Int,
    isAdmin: Boolean = false,
    onCreditClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val initial = (displayName?.firstOrNull() ?: email?.firstOrNull() ?: '?').uppercaseChar()
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Text(
                        text = displayName ?: stringResource(R.string.tab_profile),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isAdmin) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "👑 ${stringResource(R.string.admin_badge)}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { onCreditClick() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "🪙 ${stringResource(R.string.credit_badge_format, credits)}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            email?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (missedStreak > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.missed_streak_warning, missedStreak),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
internal fun CreditHistoryDialog(
    logs: List<com.ntt.crosstest.data.CreditLog>,
    currentBalance: Int,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.credit_history_title))
                Text(
                    text = "🪙 $currentBalance",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        text = {
            if (logs.isEmpty()) {
                Text(
                    text = stringResource(R.string.credit_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(logs, key = { index, log -> log.id ?: "log_${index}_${log.createdAt}" }) { _, log ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = log.note ?: log.action,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    log.createdAt?.let { dateStr ->
                                        Text(
                                            text = dateStr.take(16).replace("T", " "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    text = if (log.amount > 0) "+${log.amount}" else "${log.amount}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (log.amount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}

@Composable
private fun MutedText(text: String) {
    Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsSection(
    currentLanguage: String,
    onLanguageClick: () -> Unit,
    reminderEnabled: Boolean,
    onReminderChange: (Boolean) -> Unit,
    onChangePasswordClick: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Language Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onLanguageClick() }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_language),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    val langText = when (currentLanguage) {
                        SettingsRepository.LANGUAGE_VI -> stringResource(R.string.language_vietnamese)
                        SettingsRepository.LANGUAGE_EN -> stringResource(R.string.language_english)
                        else -> stringResource(R.string.language_system_default)
                    }
                    Text(
                        text = langText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Change Password Row (if logged in)
            if (onChangePasswordClick != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onChangePasswordClick() }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.change_password_title),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.change_password_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // Reminder Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.reminder_setting),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = reminderEnabled,
                    onCheckedChange = onReminderChange,
                )
            }
        }
    }
}

@Composable
private fun ChangePasswordDialog(
    isLoading: Boolean,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newPassword by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    var newPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmPasswordVisible by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.change_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text(stringResource(R.string.label_new_password)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (newPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        val icon = if (newPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility
                        IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                            Icon(icon, contentDescription = null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text(stringResource(R.string.label_confirm_new_password)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (confirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        val icon = if (confirmPasswordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility
                        IconButton(onClick = { confirmPasswordVisible = !confirmPasswordVisible }) {
                            Icon(icon, contentDescription = null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(newPassword, confirmPassword) },
                enabled = !isLoading && newPassword.isNotBlank(),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading,
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun LanguageSelectionDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        Triple(
            SettingsRepository.LANGUAGE_SYSTEM,
            stringResource(R.string.language_system_default),
            stringResource(R.string.language_system_desc),
        ),
        Triple(
            SettingsRepository.LANGUAGE_VI,
            stringResource(R.string.language_vietnamese),
            "Tiếng Việt",
        ),
        Triple(
            SettingsRepository.LANGUAGE_EN,
            stringResource(R.string.language_english),
            "English",
        ),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.dialog_select_language))
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                options.forEach { (code, title, desc) ->
                    val isSelected = currentLanguage == code
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onLanguageSelected(code)
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                onLanguageSelected(code)
                                onDismiss()
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            if (desc.isNotBlank() && desc != title) {
                                Text(
                                    text = desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SlotsManagementDialog(
    slots: List<SlotInfo>,
    userCredits: Int,
    onUnlockClick: (SlotInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val displaySlots = if (slots.isNotEmpty()) slots else (1..6).map { num ->
        SlotInfo(
            slotNumber = num,
            isPermanent = num == 1,
            isUnlocked = num == 1,
            cost = SlotInfo.costForSlot(num)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.slot_manage_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "🪙 $userCredits",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.slot_manage_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(displaySlots) { slot ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (slot.isOccupied)
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                else if (slot.isAvailable)
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                            ),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "Slot ${slot.slotNumber}",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        when {
                                            slot.isPermanent -> {
                                                Text(
                                                    text = "Mặc định",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF2E7D32),
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                            }
                                            slot.isUnlocked && !slot.isExpired -> {
                                                Text(
                                                    text = "Còn ${slot.remainingDays} ngày",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF2E7D32),
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                            else -> {
                                                Text(
                                                    text = "${slot.cost} Credit",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(2.dp))

                                    if (slot.currentCampaign != null) {
                                        Text(
                                            text = "Đang chạy: ${slot.currentCampaign.appName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    } else if (slot.isAvailable) {
                                        Text(
                                            text = "Trống (Sẵn sàng đăng app)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF2E7D32),
                                        )
                                    } else {
                                        Text(
                                            text = "Chưa mở (hiệu lực 16 ngày)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }

                                if (slot.canUnlock) {
                                    Button(
                                        onClick = { onUnlockClick(slot) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp),
                                    ) {
                                        Text(
                                            text = if (slot.isUnlocked && !slot.isExpired) "Gia hạn" else "Mở khóa",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                } else if (!slot.isPermanent && slot.isUnlocked && !slot.isExpired) {
                                    OutlinedButton(
                                        onClick = { onUnlockClick(slot) },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        modifier = Modifier.height(34.dp),
                                    ) {
                                        Text(
                                            text = "+16 ngày",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.confirm))
            }
        }
    )
}
