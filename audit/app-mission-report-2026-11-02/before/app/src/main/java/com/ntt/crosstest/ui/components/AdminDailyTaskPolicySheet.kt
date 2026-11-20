package com.ntt.crosstest.ui.components

import android.app.DatePickerDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntt.crosstest.R
import com.ntt.crosstest.data.AdminDailyTaskConfig
import com.ntt.crosstest.data.AdminDailyTaskPolicyHistoryItem
import com.ntt.crosstest.data.DailyTaskPolicyItem
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDailyTaskPolicySheet(
    config: AdminDailyTaskConfig?,
    isLoading: Boolean,
    isSaving: Boolean,
    isCancelling: Boolean,
    draftRatio: Int?,
    draftEffectiveFrom: String?,
    draftNote: String,
    futurePolicies: List<DailyTaskPolicyItem> = config?.futurePolicies.orEmpty(),
    isFutureLoading: Boolean = false,
    futureHasMore: Boolean = false,
    history: List<AdminDailyTaskPolicyHistoryItem>,
    isHistoryLoading: Boolean,
    historyHasMore: Boolean,
    errorMessage: String?,
    actionMessage: String?,
    saveSignal: Int = 0,
    onDismiss: () -> Unit,
    onDraftChange: (ratio: Int?, effectiveFrom: String?, note: String?) -> Unit,
    onSaveSchedule: (ratio: Int, effectiveFrom: String, note: String) -> Unit,
    onCancelSchedule: (effectiveFrom: String, note: String) -> Unit,
    onLoadMoreFuture: () -> Unit = {},
    onLoadHistory: (reset: Boolean) -> Unit,
    onRetryLoad: () -> Unit,
    onClearMessages: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    // Tính toán ngày mai mặc định theo server today
    val defaultTomorrow = remember(config?.today) {
        config?.today?.let {
            try {
                LocalDate.parse(it).plusDays(1).toString()
            } catch (_: Exception) {
                null
            }
        } ?: LocalDate.now().plusDays(1).toString()
    }

    var selectedRatio by rememberSaveable(draftRatio, config) {
        mutableIntStateOf(draftRatio ?: config?.activePolicy?.testsPerActiveApp ?: 3)
    }
    var selectedDate by rememberSaveable(draftEffectiveFrom, defaultTomorrow) {
        mutableStateOf(draftEffectiveFrom?.ifBlank { defaultTomorrow } ?: defaultTomorrow)
    }
    var noteText by rememberSaveable(draftNote) {
        mutableStateOf(draftNote)
    }

    // Đồng bộ ngược lại khi draft bên ViewModel thay đổi
    LaunchedEffect(draftRatio) {
        if (draftRatio != null && draftRatio != selectedRatio) {
            selectedRatio = draftRatio
        }
    }
    LaunchedEffect(draftEffectiveFrom) {
        if (!draftEffectiveFrom.isNullOrBlank() && draftEffectiveFrom != selectedDate) {
            selectedDate = draftEffectiveFrom
        }
    }
    LaunchedEffect(draftNote) {
        if (draftNote != noteText) {
            noteText = draftNote
        }
    }

    var isHistoryExpanded by rememberSaveable { mutableStateOf(false) }
    var policyToCancel by remember { mutableStateOf<DailyTaskPolicyItem?>(null) }
    var cancelReason by rememberSaveable { mutableStateOf("") }
    var editingPolicyId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Đóng hộp thoại hủy khi thao tác thành công (saveSignal tăng)
    LaunchedEffect(saveSignal) {
        if (saveSignal > 0) {
            policyToCancel = null
            cancelReason = ""
        }
    }

    val effectiveFutureList = if (futurePolicies.isNotEmpty()) futurePolicies else config?.futurePolicies.orEmpty()

    // Kiểm tra xem ngày đang chọn có trùng với một lịch tương lai đã có không
    val matchingFuturePolicy = remember(effectiveFutureList, selectedDate) {
        effectiveFutureList.find { it.effectiveFrom == selectedDate }
    }
    val isUpdatingExisting = matchingFuturePolicy != null || editingPolicyId != null

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.admin_daily_task_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.admin_daily_task_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                }
            }

            // Banner lỗi nếu có
            if (!errorMessage.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onRetryLoad, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Banner thành công nếu có
            if (!actionMessage.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = actionMessage,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onClearMessages, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (config == null) {
                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = errorMessage ?: stringResource(R.string.admin_daily_task_load_failed_generic),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            Button(onClick = onRetryLoad) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.admin_daily_task_retry))
                            }
                        }
                    }
                }
            } else {
                // Thẻ: Tỷ lệ đang áp dụng
                val activePolicy = config.activePolicy
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.admin_daily_task_active_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        Text(
                            text = stringResource(
                                R.string.admin_daily_task_active_format,
                                activePolicy?.testsPerActiveApp ?: 3
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )

                        val effDateText = if (activePolicy?.isBaseline == true || activePolicy?.effectiveFrom == null) {
                            stringResource(R.string.admin_daily_task_baseline_note)
                        } else {
                            stringResource(R.string.admin_daily_task_effective_from_format, activePolicy.effectiveFrom)
                        }

                        Text(
                            text = effDateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (!activePolicy?.operatorNote.isNullOrBlank()) {
                            Text(
                                text = "“${activePolicy?.operatorNote}”",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Form: Thay đổi tỷ lệ
                Text(
                    text = stringResource(R.string.admin_daily_task_new_ratio_label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                // Bộ chọn số nguyên 1 - 30
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconButton(
                        onClick = {
                            if (selectedRatio > 1) {
                                selectedRatio--
                                onDraftChange(selectedRatio, selectedDate, noteText)
                            }
                        },
                        enabled = selectedRatio > 1 && !isSaving,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = null)
                    }

                    OutlinedTextField(
                        value = selectedRatio.toString(),
                        onValueChange = { str ->
                            val num = str.toIntOrNull()
                            if (num != null && num in 1..30) {
                                selectedRatio = num
                                onDraftChange(selectedRatio, selectedDate, noteText)
                            } else if (str.isEmpty()) {
                                selectedRatio = 1
                                onDraftChange(selectedRatio, selectedDate, noteText)
                            }
                        },
                        modifier = Modifier.width(72.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !isSaving,
                    )

                    IconButton(
                        onClick = {
                            if (selectedRatio < 30) {
                                selectedRatio++
                                onDraftChange(selectedRatio, selectedDate, noteText)
                            }
                        },
                        enabled = selectedRatio < 30 && !isSaving,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }

                    Text(
                        text = stringResource(R.string.admin_daily_task_unit),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Text(
                    text = stringResource(R.string.admin_daily_task_ratio_range_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Chọn ngày hiệu lực
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.admin_daily_task_effective_date_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    OutlinedButton(
                        onClick = {
                            val curDate = try {
                                LocalDate.parse(selectedDate)
                            } catch (_: Exception) {
                                LocalDate.now().plusDays(1)
                            }
                            val minDate = try {
                                LocalDate.parse(defaultTomorrow)
                            } catch (_: Exception) {
                                LocalDate.now().plusDays(1)
                            }
                            val minEpoch = minDate.atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant().toEpochMilli()

                            val datePicker = DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    val newDate = LocalDate.of(year, month + 1, dayOfMonth).toString()
                                    selectedDate = newDate
                                    onDraftChange(selectedRatio, newDate, noteText)
                                },
                                curDate.year,
                                curDate.monthValue - 1,
                                curDate.dayOfMonth
                            )
                            datePicker.datePicker.minDate = minEpoch
                            datePicker.show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSaving,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = selectedDate,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }

                    Text(
                        text = stringResource(R.string.admin_daily_task_effective_date_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Lý do thay đổi
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.admin_daily_task_note_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OutlinedTextField(
                        value = noteText,
                        onValueChange = {
                            if (it.length <= 1000) {
                                noteText = it
                                onDraftChange(selectedRatio, selectedDate, it)
                            }
                        },
                        placeholder = { Text(stringResource(R.string.admin_daily_task_note_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        enabled = !isSaving,
                        supportingText = {
                            Text(
                                text = "${noteText.trim().length}/1000",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        },
                    )
                }

                // Minh họa chỉ tiêu cơ bản
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.admin_daily_task_preview_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(R.string.admin_daily_task_preview_item_1, selectedRatio),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.admin_daily_task_preview_item_2, selectedRatio * 2),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.admin_daily_task_preview_item_3, selectedRatio * 3),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = stringResource(R.string.admin_daily_task_preview_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                }

                // Nút lưu / cập nhật
                val isDateValid = remember(selectedDate, config.today) {
                    try {
                        val chosen = LocalDate.parse(selectedDate)
                        val srvToday = LocalDate.parse(config.today)
                        chosen.isAfter(srvToday)
                    } catch (_: Exception) {
                        false
                    }
                }

                val isFormValid = config.revision.isNotBlank() &&
                        selectedRatio in 1..30 &&
                        isDateValid &&
                        noteText.trim().length in 1..1000 &&
                        !isSaving &&
                        !isCancelling &&
                        !isLoading

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (editingPolicyId != null) {
                        OutlinedButton(
                            onClick = {
                                editingPolicyId = null
                                selectedDate = defaultTomorrow
                                selectedRatio = config.activePolicy?.testsPerActiveApp ?: 3
                                noteText = ""
                                onDraftChange(selectedRatio, selectedDate, noteText)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isSaving && !isCancelling,
                        ) {
                            Text(stringResource(R.string.admin_daily_task_btn_cancel_edit))
                        }
                    }

                    Button(
                        onClick = {
                            onSaveSchedule(selectedRatio, selectedDate, noteText.trim())
                            editingPolicyId = null
                        },
                        modifier = Modifier.weight(1f),
                        enabled = isFormValid,
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isUpdatingExisting) {
                                stringResource(R.string.admin_daily_task_btn_update)
                            } else {
                                stringResource(R.string.admin_daily_task_btn_save)
                            }
                        )
                    }
                }

                HorizontalDivider()

                // Danh sách Lịch sắp áp dụng
                Text(
                    text = stringResource(R.string.admin_daily_task_future_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                if (effectiveFutureList.isEmpty()) {
                    Text(
                        text = stringResource(R.string.admin_daily_task_no_future),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        effectiveFutureList.forEach { item ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${item.effectiveFrom} • ${item.testsPerActiveApp}:1",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        if (item.operatorNote.isNotBlank()) {
                                            Text(
                                                text = item.operatorNote,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(
                                            onClick = {
                                                editingPolicyId = item.id
                                                item.effectiveFrom?.let { selectedDate = it }
                                                selectedRatio = item.testsPerActiveApp
                                                noteText = item.operatorNote
                                                onDraftChange(selectedRatio, selectedDate, noteText)
                                            },
                                            modifier = Modifier.size(36.dp),
                                            enabled = !isSaving && !isCancelling,
                                        ) {
                                            Icon(
                                                Icons.Filled.Edit,
                                                contentDescription = stringResource(R.string.admin_daily_task_edit),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                policyToCancel = item
                                                cancelReason = ""
                                            },
                                            modifier = Modifier.size(36.dp),
                                            enabled = !isSaving && !isCancelling,
                                        ) {
                                            Icon(
                                                Icons.Filled.Delete,
                                                contentDescription = stringResource(R.string.admin_daily_task_cancel_schedule),
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (futureHasMore) {
                            OutlinedButton(
                                onClick = onLoadMoreFuture,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isFutureLoading,
                            ) {
                                if (isFutureLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(R.string.admin_daily_task_future_load_more))
                            }
                        }
                    }
                }
                }

                HorizontalDivider()

                // Lịch sử thay đổi (Accordion có phân trang)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            isHistoryExpanded = !isHistoryExpanded
                            if (isHistoryExpanded && history.isEmpty() && !isHistoryLoading) {
                                onLoadHistory(true)
                            }
                        }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = stringResource(R.string.admin_daily_task_history_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Icon(
                        if (isHistoryExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }

                AnimatedVisibility(visible = isHistoryExpanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isHistoryLoading && history.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(80.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        } else if (history.isEmpty()) {
                            Text(
                                text = stringResource(R.string.admin_daily_task_history_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            history.forEach { log ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    ),
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            val opLabel = when (log.operation.uppercase()) {
                                                "INSERT" -> stringResource(R.string.admin_daily_task_history_op_insert)
                                                "UPDATE" -> stringResource(R.string.admin_daily_task_history_op_update)
                                                "DELETE" -> stringResource(R.string.admin_daily_task_history_op_delete)
                                                else -> log.operation
                                            }
                                            Text(
                                                text = opLabel,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = if (log.operation == "DELETE") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            )
                                            val formattedTime = try {
                                                val instant = java.time.Instant.parse(log.changedAt)
                                                val zdt = instant.atZone(ZoneId.of("Asia/Ho_Chi_Minh"))
                                                zdt.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) + " (VN)"
                                            } catch (_: Exception) {
                                                log.changedAt.take(19).replace('T', ' ')
                                            }
                                            Text(
                                                text = formattedTime,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }

                                        val oldRatio = log.oldRow?.get("tests_per_active_app")?.let {
                                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                                        }
                                        val newRatio = log.newRow?.get("tests_per_active_app")?.let {
                                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
                                        }
                                        val oldDate = log.oldRow?.get("effective_from")?.let {
                                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                                        }
                                        val newDate = log.newRow?.get("effective_from")?.let {
                                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                                        }
                                        val effDate = newDate ?: oldDate ?: ""

                                        val detailText = when (log.operation.uppercase()) {
                                            "INSERT" -> {
                                                if (newRatio != null) {
                                                    stringResource(R.string.admin_daily_task_history_detail_insert, effDate, newRatio)
                                                } else effDate
                                            }
                                            "UPDATE" -> {
                                                if (oldRatio != null && newRatio != null && oldRatio != newRatio) {
                                                    stringResource(R.string.admin_daily_task_history_detail_update_ratio, effDate, oldRatio, newRatio)
                                                } else if (newRatio != null || oldRatio != null) {
                                                    stringResource(R.string.admin_daily_task_history_detail_update_note, effDate, (newRatio ?: oldRatio)!!)
                                                } else effDate
                                            }
                                            "DELETE" -> {
                                                if (oldRatio != null) {
                                                    stringResource(R.string.admin_daily_task_history_detail_delete, effDate, oldRatio)
                                                } else effDate
                                            }
                                            else -> effDate
                                        }

                                        if (detailText.isNotBlank()) {
                                            Text(
                                                text = detailText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }

                                        val actorDisplay = log.actorName?.ifBlank { log.actorEmail }
                                            ?: log.actorEmail
                                            ?: (log.actorId?.let { "Admin ($it)" } ?: stringResource(R.string.admin_daily_task_history_actor_system))

                                        Text(
                                            text = actorDisplay,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                        )

                                        if (log.operatorNote.isNotBlank()) {
                                            Text(
                                                text = "“${log.operatorNote}”",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }

                            if (historyHasMore) {
                                OutlinedButton(
                                    onClick = { onLoadHistory(false) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isHistoryLoading,
                                ) {
                                    if (isHistoryLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(stringResource(R.string.admin_daily_task_history_load_more))
                                }
                            }
                        }
                    }
                }
            }
        }

    // Dialog xác nhận hủy lịch
    policyToCancel?.let { target ->
        AlertDialog(
            onDismissRequest = {
                if (!isCancelling) policyToCancel = null
            },
            title = {
                Text(
                    text = stringResource(R.string.admin_daily_task_cancel_dialog_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            R.string.admin_daily_task_cancel_dialog_msg,
                            target.effectiveFrom ?: "",
                            target.testsPerActiveApp
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = cancelReason,
                        onValueChange = { if (it.length <= 1000) cancelReason = it },
                        label = { Text(stringResource(R.string.admin_daily_task_cancel_note_label)) },
                        placeholder = { Text(stringResource(R.string.admin_daily_task_cancel_note_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 3,
                        enabled = !isCancelling,
                        supportingText = {
                            Text(
                                text = "${cancelReason.trim().length}/1000",
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                        },
                    )
                    if (!errorMessage.isNullOrBlank()) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        target.effectiveFrom?.let { date ->
                            onCancelSchedule(date, cancelReason.trim())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = cancelReason.trim().length in 1..1000 && !isCancelling,
                ) {
                    if (isCancelling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.onError,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.admin_daily_task_btn_confirm_cancel))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { policyToCancel = null },
                    enabled = !isCancelling,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
