package com.ntt.crosstest.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntt.crosstest.R
import com.ntt.crosstest.ui.theme.TaskAmberText
import com.ntt.crosstest.ui.theme.TaskAmberTextDark
import com.ntt.crosstest.ui.theme.TaskGreenText
import com.ntt.crosstest.ui.theme.TaskGreenTextDark
import com.ntt.crosstest.ui.theme.TaskRedText
import com.ntt.crosstest.ui.theme.TaskRedTextDark

@Composable
fun DailyTaskCard(
    todayCheckinCount: Int,
    userCredits: Int,
    missedStreak: Int,
    isAuthenticated: Boolean,
    onRequireLogin: () -> Unit,
    onTestAppClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPenaltyDialog by rememberSaveable { mutableStateOf(false) }

    val isDark = isSystemInDarkTheme()
    val isCompleted = todayCheckinCount >= 1

    val accentAmber = if (isDark) TaskAmberTextDark else TaskAmberText
    val accentGreen = if (isDark) TaskGreenTextDark else TaskGreenText
    val accentRed = if (isDark) TaskRedTextDark else TaskRedText

    val accentColor = when {
        !isAuthenticated -> MaterialTheme.colorScheme.primary
        missedStreak > 0 -> accentRed
        else -> accentAmber
    }

    if (showPenaltyDialog) {
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

    if (isAuthenticated && isCompleted) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .clickable { showPenaltyDialog = true },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
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
                        text = stringResource(R.string.daily_quest_completed_compact, todayCheckinCount),
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
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Row 1: [Badge Icon] --------- [Title] --------- [Info Button]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (!isAuthenticated) Icons.Filled.Security else Icons.Filled.Warning,
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

            Spacer(Modifier.height(8.dp))

            // Row 2: [Status Pill] --------------------- [Progress Count]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (statusText, statusBg, statusTextColor) = when {
                    !isAuthenticated -> Triple(
                        stringResource(R.string.daily_guest_badge),
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    missedStreak > 0 -> Triple(
                        stringResource(R.string.daily_missed_badge, missedStreak),
                        accentRed.copy(alpha = 0.12f),
                        accentRed
                    )
                    else -> Triple(
                        stringResource(R.string.daily_quest_pending),
                        accentAmber.copy(alpha = 0.12f),
                        accentAmber
                    )
                }
                Box(
                    modifier = Modifier
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

                Text(
                    text = stringResource(R.string.daily_quest_progress, if (isCompleted) 1 else 0),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                )
            }

            Spacer(Modifier.height(6.dp))

            val animatedProgress by animateFloatAsState(
                targetValue = if (isCompleted) 1f else 0f,
                label = "dailyProgress"
            )

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = accentColor,
                trackColor = accentColor.copy(alpha = 0.2f),
            )

            Spacer(Modifier.height(10.dp))

            // Explanatory Message
            when {
                !isAuthenticated -> {
                    Text(
                        text = stringResource(R.string.daily_guest_prompt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onRequireLogin,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(stringResource(R.string.login_now), fontWeight = FontWeight.Bold)
                    }
                }
                else -> {
                    val warningMsg = if (missedStreak > 0) {
                        val nextPct = when (missedStreak) {
                            1 -> 2
                            2 -> 5
                            else -> 10
                        }
                        stringResource(R.string.daily_missed_streak_alert, missedStreak, nextPct)
                    } else {
                        stringResource(R.string.daily_quest_warning)
                    }

                    Text(
                        text = warningMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = accentColor,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 18.sp,
                    )

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = onTestAppClick,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (missedStreak > 0) accentRed else MaterialTheme.colorScheme.primary
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.daily_task_test_now),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        TextButton(
                            onClick = { showPenaltyDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.daily_penalty_info_btn),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
