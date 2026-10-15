package com.ntt.crosstest.ui.components

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntt.crosstest.R

private val GreenDark = Color(0xFF2E7D32)
private val GreenLightBg = Color(0xFFE8F5E9)
private val GreenBorder = Color(0xFFA5D6A7)

private val AmberDark = Color(0xFFE65100)
private val AmberLightBg = Color(0xFFFFF8E1)
private val AmberBorder = Color(0xFFFFCC80)

private val RedDark = Color(0xFFC62828)
private val RedLightBg = Color(0xFFFFEBEE)
private val RedBorder = Color(0xFFEF9A9A)

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

    val isCompleted = todayCheckinCount >= 1
    val isPending = !isCompleted

    val (containerBg, borderColor, iconTint) = when {
        !isAuthenticated -> Triple(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.outlineVariant,
            MaterialTheme.colorScheme.primary
        )
        isCompleted -> Triple(GreenLightBg, GreenBorder, GreenDark)
        missedStreak > 0 -> Triple(RedLightBg, RedBorder, RedDark)
        else -> Triple(AmberLightBg, AmberBorder, AmberDark)
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
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .clickable { showPenaltyDialog = true },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = containerBg),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
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
                        tint = GreenDark,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.daily_quest_completed_compact, todayCheckinCount),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = GreenDark,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = stringResource(R.string.daily_penalty_info_btn),
                    tint = GreenDark.copy(alpha = 0.65f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        return
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row: [Badge Icon + Title] ---------------- [Info Button]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.8f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = when {
                                !isAuthenticated -> Icons.Filled.Security
                                isCompleted -> Icons.Filled.CheckCircle
                                missedStreak > 0 -> Icons.Filled.Warning
                                else -> Icons.Filled.Warning
                            },
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    Spacer(Modifier.width(10.dp))

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.daily_quest_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.width(8.dp))
                            // Status Pill
                            val (statusText, statusBg, statusColor) = when {
                                !isAuthenticated -> Triple(
                                    "Khách",
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                isCompleted -> Triple(
                                    stringResource(R.string.daily_quest_completed),
                                    GreenDark.copy(alpha = 0.15f),
                                    GreenDark
                                )
                                missedStreak > 0 -> Triple(
                                    "Vắng $missedStreak ngày ⚠️",
                                    RedDark.copy(alpha = 0.15f),
                                    RedDark
                                )
                                else -> Triple(
                                    stringResource(R.string.daily_quest_pending),
                                    AmberDark.copy(alpha = 0.15f),
                                    AmberDark
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
                                    color = statusColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }

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

            // Progress Row
            val progressTarget = 1
            val currentProgress = if (isCompleted) 1 else 0
            val animatedProgress by animateFloatAsState(
                targetValue = if (isCompleted) 1f else 0f,
                label = "dailyProgress"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Yêu cầu: Test 1 app / ngày",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.daily_quest_progress, currentProgress),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isCompleted) GreenDark else AmberDark,
                )
            }

            Spacer(Modifier.height(5.dp))

            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (isCompleted) GreenDark else if (missedStreak > 0) RedDark else AmberDark,
                trackColor = Color.White.copy(alpha = 0.6f),
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
                isCompleted -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.daily_quest_safe),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = GreenDark,
                            )
                            Text(
                                text = "Đã check-in $todayCheckinCount app · Số dư: $userCredits Credit an toàn hôm nay",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { showPenaltyDialog = true },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "Xem quy định",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                else -> {
                    // Pending state
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
                        color = if (missedStreak > 0) RedDark else AmberDark,
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
                                containerColor = if (missedStreak > 0) RedDark else AmberDark
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.daily_task_test_now),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                        }

                        OutlinedButton(
                            onClick = { showPenaltyDialog = true },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "Quy định phạt",
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
