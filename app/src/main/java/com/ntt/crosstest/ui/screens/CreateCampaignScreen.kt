package com.ntt.crosstest.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.ntt.crosstest.data.SlotInfo
import com.ntt.crosstest.ui.components.AppIcon
import com.ntt.crosstest.util.PlayStoreHelper
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.ui.viewmodels.CreateCampaignViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCampaignScreen(
    onBack: () -> Unit,
    onCreated: () -> Unit,
    onNavigateToGuide: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val app = context.applicationContext as TestCheoApp
    val viewModel: CreateCampaignViewModel = viewModel(
        factory = viewModelFactory {
            initializer { CreateCampaignViewModel(app.campaignRepository, app.settingsRepository, Localizer(app)) }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showSlotInfoDialog by remember { mutableStateOf(false) }
    var slotToUnlock by remember { mutableStateOf<SlotInfo?>(null) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onIconPicked(context, uri)
        }
    }

    LaunchedEffect(uiState.created) {
        if (uiState.created) onCreated()
    }

    LaunchedEffect(uiState.unlockSuccessMsg) {
        uiState.unlockSuccessMsg?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearUnlockSuccess()
            slotToUnlock = null
        }
    }

    if (showSlotInfoDialog) {
        AlertDialog(
            onDismissRequest = { showSlotInfoDialog = false },
            title = { Text(stringResource(R.string.post_app_slot_dialog_title)) },
            text = { Text(stringResource(R.string.post_app_slot_dialog_msg)) },
            confirmButton = {
                TextButton(onClick = { showSlotInfoDialog = false }) {
                    Text(stringResource(R.string.confirm))
                }
            },
        )
    }

    slotToUnlock?.let { slot ->
        val cost = slot.cost
        val hasEnoughCredits = uiState.userCredits >= cost
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
                                Text(stringResource(R.string.create_slot_duration_label), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(R.string.create_slot_duration_days), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(stringResource(R.string.create_slot_cost_label), style = MaterialTheme.typography.bodySmall)
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
                                Text(stringResource(R.string.create_current_balance_label), style = MaterialTheme.typography.bodySmall)
                                Text("${uiState.userCredits} Credit", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    if (!hasEnoughCredits) {
                        Text(
                            text = stringResource(R.string.error_insufficient_credits_for_slot, cost, uiState.userCredits),
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.post_app_title),
                        fontWeight = FontWeight.SemiBold,
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
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Instructions Banner Card
            val isDark = isSystemInDarkTheme()
            val bannerStatusColor = if (uiState.isGuideConfirmed) {
                if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
            } else {
                if (isDark) Color(0xFFFFB74D) else Color(0xFFE65100)
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onNavigateToGuide() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.isGuideConfirmed) {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    } else {
                        if (isDark) Color(0xFF3E2723).copy(alpha = 0.45f) else Color(0xFFFFF3E0)
                    },
                ),
                border = if (!uiState.isGuideConfirmed) {
                    BorderStroke(1.dp, bannerStatusColor.copy(alpha = 0.6f))
                } else null,
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        tint = bannerStatusColor,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.post_app_guide_banner_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = if (uiState.isGuideConfirmed) {
                                stringResource(R.string.post_app_guide_banner_confirmed)
                            } else {
                                stringResource(R.string.post_app_guide_banner_unconfirmed)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = bannerStatusColor,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // 2. Slot Selector Section (Up to 6 slots, 30 days duration)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ConfirmationNumber,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.slot_select_label),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(R.string.create_slots_used_format, uiState.usedSlots, uiState.maxSlots),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium,
                        )
                        IconButton(
                            onClick = { showSlotInfoDialog = true },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "Info",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // 6 Slots Row / Horizontal Scroll
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val displaySlots = if (uiState.slots.isNotEmpty()) {
                            uiState.slots
                        } else {
                            (1..6).map { num ->
                                SlotInfo(
                                    slotNumber = num,
                                    isPermanent = num == 1,
                                    isUnlocked = num == 1,
                                    cost = SlotInfo.costForSlot(num)
                                )
                            }
                        }

                        displaySlots.forEach { slot ->
                            val isSelected = uiState.selectedSlotNumber == slot.slotNumber
                            val isAvailable = slot.isAvailable
                            val isOccupied = slot.isOccupied

                            val containerColor = when {
                                isSelected -> MaterialTheme.colorScheme.primaryContainer
                                isOccupied -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                isAvailable -> MaterialTheme.colorScheme.surface
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            }

                            val borderColor = when {
                                isSelected -> MaterialTheme.colorScheme.primary
                                isAvailable -> MaterialTheme.colorScheme.outlineVariant
                                else -> Color.Transparent
                            }

                            Card(
                                modifier = Modifier
                                    .width(116.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (isAvailable) {
                                            viewModel.selectSlot(slot.slotNumber)
                                        } else if (slot.canUnlock) {
                                            slotToUnlock = slot
                                        }
                                    },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = containerColor),
                                border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = "Slot ${slot.slotNumber}",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (isSelected) {
                                            Spacer(Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.Filled.CheckCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }

                                    // Status Badge / Detail
                                    when {
                                        slot.isPermanent -> {
                                            Text(
                                                text = if (isOccupied) stringResource(R.string.create_slot_running) else stringResource(R.string.create_slot_ready),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isOccupied) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF2E7D32),
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = stringResource(R.string.create_slot_permanent),
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        slot.isUnlocked && !slot.isExpired -> {
                                            Text(
                                                text = if (isOccupied) stringResource(R.string.create_slot_running) else stringResource(R.string.create_slot_ready),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isOccupied) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF2E7D32),
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            Text(
                                                text = stringResource(R.string.create_slot_remaining_days_format, slot.remainingDays),
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                                color = Color(0xFF2E7D32),
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                        else -> {
                                            Text(
                                                text = "${slot.cost} C",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            Text(
                                                text = stringResource(R.string.create_slot_unlock_short),
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp),
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Form container: Dimmed & interactions disabled when guide is not confirmed
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (uiState.isGuideConfirmed) 1f else 0.38f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 3. Play Store Link Input
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = uiState.playUrl,
                        onValueChange = { viewModel.onPlayUrlChanged(it, context) },
                        enabled = uiState.isGuideConfirmed,
                        label = { Text(stringResource(R.string.label_play_store_link)) },
                        placeholder = { Text(stringResource(R.string.hint_play_store_link)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.ShoppingBag,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    val clipText = clipboardManager.getText()?.text
                                    if (!clipText.isNullOrBlank()) {
                                        viewModel.onPlayUrlChanged(clipText, context)
                                    }
                                },
                                enabled = uiState.isGuideConfirmed,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.ContentPaste,
                                    contentDescription = stringResource(R.string.paste_clipboard),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        text = stringResource(R.string.helper_play_store_link),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                // 3.1 Closed Testing Opt-in Link
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = uiState.testingUrl,
                        onValueChange = { viewModel.onTestingUrlChanged(it) },
                        enabled = uiState.isGuideConfirmed,
                        label = { Text(stringResource(R.string.label_testing_url)) },
                        placeholder = { Text("https://play.google.com/apps/testing/...") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Link,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        text = stringResource(R.string.create_tester_link_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                // 4. Section: App Information
                Text(
                    text = stringResource(R.string.section_app_info),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                // 4.1 Package Name
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = uiState.packageName,
                        onValueChange = { viewModel.onPackageNameChanged(it, context) },
                        enabled = uiState.isGuideConfirmed,
                        label = { Text(stringResource(R.string.label_package_name)) },
                        placeholder = { Text("com.example.myapp") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Tag,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            if (uiState.isLoadingMetadata) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Text(
                        text = stringResource(R.string.helper_package_name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                // 4.2 App Name (Optional)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = uiState.appName,
                        onValueChange = { viewModel.update(appName = it) },
                        enabled = uiState.isGuideConfirmed,
                        label = { Text(stringResource(R.string.label_app_name_optional)) },
                        placeholder = { Text(stringResource(R.string.label_app_name_optional)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Apps,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.helper_app_name_optional),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${uiState.appName.length}/60",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 4.3 App Icon (Preview + Link + Upload)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppIcon(
                                iconUrl = uiState.iconUrl,
                                packageName = uiState.packageName,
                                appName = uiState.appName,
                                size = 56.dp,
                                shapeRadius = 12.dp,
                            )
                            if (uiState.isLoadingMetadata) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.dp,
                                        color = androidx.compose.ui.graphics.Color.White,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        OutlinedTextField(
                            value = uiState.iconUrl,
                            onValueChange = { viewModel.update(iconUrl = it) },
                            enabled = uiState.isGuideConfirmed,
                            label = { Text(stringResource(R.string.label_icon_link_optional)) },
                            placeholder = { Text(stringResource(R.string.label_icon_link_optional)) },
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            ),
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }

                    Text(
                        text = stringResource(R.string.helper_icon_link_optional),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = uiState.isGuideConfirmed) {
                                imagePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FileUpload,
                            contentDescription = null,
                            tint = if (uiState.isGuideConfirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.btn_upload_icon_from_device),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.isGuideConfirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 5. Section: Test Account (Mandatory if login is needed)
                val testAccountTitle = buildAnnotatedString {
                    append(stringResource(R.string.section_test_account_prefix))
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)) {
                        append(stringResource(R.string.section_test_account_required))
                    }
                    append(stringResource(R.string.section_test_account_suffix))
                }
                Text(
                    text = testAccountTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                // 5.1 Test Username
                OutlinedTextField(
                    value = uiState.testUsername,
                    onValueChange = { viewModel.update(testUsername = it) },
                    enabled = uiState.isGuideConfirmed,
                    label = { Text(stringResource(R.string.label_test_username)) },
                    placeholder = { Text(stringResource(R.string.label_test_username)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // 5.2 Test Password
                OutlinedTextField(
                    value = uiState.testPassword,
                    onValueChange = { viewModel.update(testPassword = it) },
                    enabled = uiState.isGuideConfirmed,
                    label = { Text(stringResource(R.string.label_test_password)) },
                    placeholder = { Text(stringResource(R.string.label_test_password)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Key,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                // 6. Section: Mục tiêu Test
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.create_target_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.create_target_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val targets = listOf(
                            Triple(12, stringResource(R.string.create_target_option_format, 12), stringResource(R.string.create_target_default_cost)),
                            Triple(15, stringResource(R.string.create_target_option_format, 15), "+10 Credit"),
                            Triple(20, stringResource(R.string.create_target_option_format, 20), "+20 Credit"),
                        )

                        targets.forEach { (targetValue, title, costLabel) ->
                            val isSelected = uiState.targetTesters == targetValue
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = uiState.isGuideConfirmed) {
                                        viewModel.selectTarget(targetValue)
                                    },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                ),
                                border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                                else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp, horizontal = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center,
                                    )
                                    Text(
                                        text = costLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.targetCost > 0) {
                        val hasEnoughForUpgrade = uiState.userCredits >= uiState.targetCost + 10
                        Text(
                            text = if (hasEnoughForUpgrade) {
                                stringResource(R.string.create_target_fee_format, uiState.targetCost, uiState.userCredits)
                            } else {
                                stringResource(R.string.create_target_insufficient_format, uiState.targetCost + 10, uiState.userCredits)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasEnoughForUpgrade) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }

                // 7. Section: Hướng dẫn thêm cho Tester (Tùy chọn)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.create_guide_section_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    OutlinedTextField(
                        value = uiState.description,
                        onValueChange = { viewModel.update(description = it) },
                        enabled = uiState.isGuideConfirmed,
                        label = { Text(stringResource(R.string.create_guide_label)) },
                        placeholder = { Text(stringResource(R.string.create_guide_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        ),
                    )
                    Text(
                        text = stringResource(R.string.create_guide_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                // Credit warning if user has <= 10 credits or not enough for target upgrade
                val hasEnoughCreditsForSubmission = uiState.userCredits >= (uiState.targetCost + 10)
                if (!uiState.isLoadingCredits && !hasEnoughCreditsForSubmission) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(12.dp))
                            val warningText = if (uiState.targetCost > 0) {
                                stringResource(R.string.create_target_cost_format, uiState.targetCost + 10, uiState.targetTesters, uiState.targetCost, uiState.userCredits)
                            } else {
                                stringResource(R.string.create_campaign_credit_warning, uiState.userCredits)
                            }
                            Text(
                                text = warningText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }

                // Slot warning if all unlocked slots are full
                val hasAvailableSlot = uiState.slots.isEmpty() || uiState.slots.any { it.isAvailable }
                val nextLockedSlot = uiState.slots.firstOrNull { it.canUnlock }

                if (!uiState.isLoadingSlots && !hasAvailableSlot) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.error_slot_full),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (nextLockedSlot != null) {
                                Button(
                                    onClick = { slotToUnlock = nextLockedSlot },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.create_unlock_slot_btn_format, nextLockedSlot.slotNumber, nextLockedSlot.cost))
                                }
                            }
                        }
                    }
                }

                uiState.error?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = viewModel::clearError,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.close),
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                val selectedSlot = uiState.slots.find { it.slotNumber == uiState.selectedSlotNumber }
                val isSelectedSlotReady = selectedSlot != null && selectedSlot.isAvailable
                val canSubmit = !uiState.isSaving &&
                        !uiState.isLoadingCredits &&
                        uiState.userCredits >= (uiState.targetCost + 10) &&
                        !uiState.isLoadingSlots &&
                        isSelectedSlotReady &&
                        hasAvailableSlot &&
                        uiState.packageName.isNotBlank()

                Button(
                    onClick = viewModel::create,
                    enabled = uiState.isGuideConfirmed && canSubmit,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = stringResource(R.string.btn_post_app),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } // End of form container

            Spacer(Modifier.height(24.dp))
        }
    }
}
