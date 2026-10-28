package com.ntt.crosstest.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntt.crosstest.R
import com.ntt.crosstest.data.DailyTaskStatus
import com.ntt.crosstest.ui.theme.statusColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DailyTaskCard(
    todayCheckinCount: Int,
    userCredits: Int,
    missedStreak: Int,
    isAuthenticated: Boolean,
    isPenaltyExempt: Boolean = false,
    dailyTask: DailyTaskStatus? = null,
    onRequireLogin: () -> Unit,
    onTestAppClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPenaltyDialog by rememberSaveable { mutableStateOf(false) }

    val statusColors = MaterialTheme.statusColors
    val accentAmber = statusColors.warning
    val accentGreen = statusColors.success
    val accentRed = MaterialTheme.colorScheme.error

    val exempt = isPenaltyExempt || dailyTask?.isPenaltyExempt == true
    val ownerMode = dailyTask?.isOwner == true
    val memberMode = dailyTask?.isMember == true
    val waived = ownerMode && dailyTask?.isWaived == true
    val target = when {
        exempt || waived -> 0
        ownerMode -> dailyTask?.effectiveRequiredTests ?: 1
        else -> 1
    }
    val completed = when {
        memberMode -> if (dailyTask?.loggedInToday == true) 1 else 0
        else -> dailyTask?.completedTests ?: todayCheckinCount
    }
    val met = when {
        !isAuthenticated -> false
        exempt || waived -> true
        dailyTask != null -> dailyTask.met
        else -> todayCheckinCount >= 1
    }
    val streak = dailyTask?.missedStreak ?: missedStreak
    // Fallback khớp server (migration 036: 1%/2%/5%/10% theo streak 1/2/3/4+).
    val nextPct = dailyTask?.nextPenaltyPercent ?: when (streak) {
        1 -> 1
        2 -> 2
        3 -> 5
        else -> 10
    }
    val progress = when {
        exempt || waived || met -> 1f
        target > 0 -> (completed.toFloat() / target.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    val accentColor = when {
        !isAuthenticated -> MaterialTheme.colorScheme.primary
        exempt -> accentAmber
        waived -> accentGreen
        streak > 0 -> accentRed
        else -> accentAmber
    }
    val accentContainer = when {
        !isAuthenticated -> MaterialTheme.colorScheme.primaryContainer
        exempt -> statusColors.warningContainer
        waived -> statusColors.successContainer
        streak > 0 -> MaterialTheme.colorScheme.errorContainer
        else -> statusColors.warningContainer
    }

    if (showPenaltyDialog && !exempt) {
        AlertDialog(
            onDismissRequest = { showPenaltyDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.daily_penalty_dialog_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.daily_penalty_dialog_content),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp,
                )
            },
            confirmButton = {
                Button(onClick = { showPenaltyDialog = false }) {
                    Text(stringResource(R.string.confirm))
                }
            }
        )
    }

    // Thẻ gọn khi đã đạt chỉ tiêu. Admin/Moderator và trường hợp miễn chỉ tiêu
    // vẫn hiển thị thẻ đầy đủ để thấy rõ trạng thái miễn.
    if (isAuthenticated && met && !exempt && !waived) {
        val completedText = when {
            ownerMode -> stringResource(
                R.string.daily_task_completed_progress,
                completed,
                target,
            )
            memberMode -> stringResource(R.string.daily_login_completed)
            else -> stringResource(R.string.daily_quest_completed_compact, completed)
        }
        Card(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { showPenaltyDialog = true },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = accentGreen,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = completedText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accentGreen,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = stringResource(R.string.daily_penalty_info_btn),
                    tint = accentGreen.copy(alpha = 0.65f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        return
    }

    Card(
        modifier = modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Row 1: [Badge Icon] --------- [Title] --------- [Info Button]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(accentContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when {
                            !isAuthenticated -> Icons.Filled.Security
                            waived -> Icons.Filled.CheckCircle
                            else -> Icons.Filled.Warning
                        },
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(Modifier.width(10.dp))

                Text(
                    text = stringResource(R.string.daily_quest_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (!exempt) {
                    IconButton(
                        onClick = { showPenaltyDialog = true },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = stringResource(R.string.daily_penalty_info_btn),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            val animatedProgress by animateFloatAsState(
                targetValue = progress,
                label = "dailyProgress",
            )
            // Large text gets a separate progress row rather than squeezed labels.
            val inlineProgress = LocalDensity.current.fontScale <= 1.3f

            // Keep the status, progress and count on one compact row by default.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (statusText, statusBg, statusTextColor) = when {
                    !isAuthenticated -> Triple(
                        stringResource(R.string.daily_guest_badge),
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    exempt -> Triple(
                        stringResource(R.string.daily_exempt_badge),
                        statusColors.warningContainer,
                        accentAmber
                    )
                    waived -> Triple(
                        stringResource(R.string.daily_task_waived_badge),
                        statusColors.successContainer,
                        statusColors.onSuccessContainer
                    )
                    streak > 0 -> Triple(
                        stringResource(R.string.daily_missed_badge, streak),
                        MaterialTheme.colorScheme.errorContainer,
                        accentRed
                    )
                    else -> Triple(
                        stringResource(R.string.daily_quest_pending),
                        statusColors.warningContainer,
                        accentAmber
                    )
                }
                Box(
                    modifier = Modifier
                        .then(if (inlineProgress) Modifier else Modifier.weight(1f))
                        .clip(RoundedCornerShape(8.dp))
                        .background(statusBg)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusText,
                        color = statusTextColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }

                if (inlineProgress) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(4.dp)),
                        color = accentColor,
                        trackColor = accentContainer,
                    )
                }

                Text(
                    text = when {
                        !isAuthenticated -> "—"
                        exempt -> stringResource(R.string.daily_exempt_badge)
                        waived -> "—"
                        memberMode -> if (dailyTask?.loggedInToday == true) "1/1" else "0/1"
                        ownerMode -> stringResource(
                            R.string.daily_task_progress_format,
                            completed,
                            target,
                        )
                        else -> stringResource(
                            R.string.daily_quest_progress,
                            if (completed >= 1) 1 else 0,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                )
            }

            if (!inlineProgress) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(4.dp)),
                    color = accentColor,
                    trackColor = accentContainer,
                )
            }

            Spacer(Modifier.height(6.dp))

            // Explanatory Message
            if (!isAuthenticated) {
                Text(
                    text = stringResource(R.string.daily_guest_prompt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onRequireLogin,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(stringResource(R.string.login_now), fontWeight = FontWeight.Bold)
                }
            } else {
                val warningMsg = when {
                    exempt -> stringResource(R.string.daily_exempt_note)
                    waived -> stringResource(R.string.daily_task_waived_note)
                    ownerMode -> {
                        if (streak > 0) {
                            stringResource(
                                R.string.daily_owner_missed_alert,
                                completed,
                                target,
                                streak,
                                nextPct,
                            )
                        } else {
                            stringResource(
                                R.string.daily_owner_goal_format,
                                dailyTask?.activeAppCount ?: 0,
                                target,
                            )
                        }
                    }
                    memberMode -> stringResource(R.string.daily_member_goal)
                    streak > 0 -> stringResource(
                        R.string.daily_missed_streak_alert,
                        streak,
                        nextPct,
                    )
                    else -> stringResource(R.string.daily_quest_warning)
                }

                Text(
                    text = warningMsg,
                    style = MaterialTheme.typography.bodySmall,
                    color = accentColor,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 18.sp,
                )

                Spacer(Modifier.height(4.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    Button(
                        onClick = onTestAppClick,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!exempt && !waived && streak > 0) {
                                accentRed
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            contentColor = if (!exempt && !waived && streak > 0) {
                                MaterialTheme.colorScheme.onError
                            } else {
                                MaterialTheme.colorScheme.onPrimary
                            },
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                if (memberMode) R.string.daily_browse_apps
                                else R.string.daily_task_test_now
                            ),
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    if (!exempt) {
                        TextButton(
                            onClick = { showPenaltyDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.daily_penalty_info_btn),
                            )
                        }
                    }
                }
            }
        }
    }
}
