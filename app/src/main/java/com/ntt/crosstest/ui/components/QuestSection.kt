package com.ntt.crosstest.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.ntt.crosstest.data.QuestMilestone
import com.ntt.crosstest.data.QuestSummary

private val GoldStarColor = Color(0xFFF57F17)
private val GoldBadgeBg = Color(0xFFFFF8E1)
private val GreenDoneColor = Color(0xFF2E7D32)
private val GreenDoneBg = Color(0xFFE8F5E9)

@Composable
fun QuestSection(
    questSummary: QuestSummary?,
    claimingMilestone: String?,
    isAuthenticated: Boolean,
    onClaimReward: (questType: String, milestone: Int) -> Unit,
    onRequireLogin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isExpanded by rememberSaveable { mutableStateOf(true) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0: Daily, 1: Weekly

    val summary = questSummary ?: QuestSummary()
    val totalClaimable = if (isAuthenticated) summary.totalClaimableCount else 0
    val dailyClaimable = if (isAuthenticated) summary.dailyClaimableCount else 0
    val weeklyClaimable = if (isAuthenticated) summary.weeklyClaimableCount else 0

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row: [Icon + Title + Badge] -------- [Expand/Collapse Button]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
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
                            .background(GoldBadgeBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MonetizationOn,
                            contentDescription = null,
                            tint = GoldStarColor,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.quest_section_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            if (totalClaimable > 0) {
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFE65100))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$totalClaimable nhận ngay!",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                        if (!isExpanded) {
                            Text(
                                text = "Hôm nay: ${summary.dailyCount}/50 | Tuần này: ${summary.weeklyCount}/300 lượt",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) stringResource(R.string.quest_collapse) else stringResource(R.string.quest_expand),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    // Segmented Tabs: [ Nhiệm vụ Ngày ] | [ Nhiệm vụ Tuần ]
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                        divider = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.quest_tab_daily),
                                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    if (dailyClaimable > 0) {
                                        Spacer(Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFE65100)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "$dailyClaimable",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.quest_tab_weekly),
                                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    if (weeklyClaimable > 0) {
                                        Spacer(Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFE65100)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = "$weeklyClaimable",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    if (selectedTab == 0) {
                        // ================= DAILY QUESTS =================
                        val dailyCount = summary.dailyCount
                        val dailyProgress = (dailyCount.toFloat() / 50f).coerceIn(0f, 1f)
                        val animatedDailyProgress by animateFloatAsState(targetValue = dailyProgress, label = "dailyProgress")

                        // Overall Progress
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Tiến độ hôm nay",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.quest_daily_progress_format, dailyCount),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { animatedDailyProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )

                        Spacer(Modifier.height(12.dp))

                        // Daily Milestone Items
                        summary.dailyMilestones.forEach { item ->
                            val isClaimed = summary.isDailyMilestoneClaimed(item.milestone)
                            val canClaim = summary.canClaimDaily(item.milestone)
                            val isClaiming = claimingMilestone == "daily_${item.milestone}"

                            val title = when (item.milestone) {
                                10 -> stringResource(R.string.quest_milestone_daily_10)
                                20 -> stringResource(R.string.quest_milestone_daily_20)
                                else -> stringResource(R.string.quest_milestone_daily_50)
                            }

                            QuestItemRow(
                                title = title,
                                milestone = item.milestone,
                                rewardCredits = item.reward,
                                currentCount = dailyCount,
                                isClaimed = isClaimed,
                                canClaim = canClaim,
                                isClaiming = isClaiming,
                                onClaimClick = {
                                    if (!isAuthenticated) {
                                        onRequireLogin()
                                    } else {
                                        onClaimReward("daily", item.milestone)
                                    }
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        // Daily Note
                        Text(
                            text = stringResource(R.string.quest_daily_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(top = 2.dp, start = 2.dp)
                        )
                    } else {
                        // ================= WEEKLY QUESTS =================
                        val weeklyCount = summary.weeklyCount
                        val weeklyProgress = (weeklyCount.toFloat() / 300f).coerceIn(0f, 1f)
                        val animatedWeeklyProgress by animateFloatAsState(targetValue = weeklyProgress, label = "weeklyProgress")

                        // Overall Progress
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Tiến độ tuần này",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.quest_weekly_progress_format, weeklyCount),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { animatedWeeklyProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        )

                        Spacer(Modifier.height(12.dp))

                        // Weekly Milestone Items
                        summary.weeklyMilestones.forEach { item ->
                            val isClaimed = summary.isWeeklyMilestoneClaimed(item.milestone)
                            val canClaim = summary.canClaimWeekly(item.milestone)
                            val isClaiming = claimingMilestone == "weekly_${item.milestone}"

                            val title = when (item.milestone) {
                                150 -> stringResource(R.string.quest_milestone_weekly_150)
                                else -> stringResource(R.string.quest_milestone_weekly_300)
                            }

                            QuestItemRow(
                                title = title,
                                milestone = item.milestone,
                                rewardCredits = item.reward,
                                currentCount = weeklyCount,
                                isClaimed = isClaimed,
                                canClaim = canClaim,
                                isClaiming = isClaiming,
                                onClaimClick = {
                                    if (!isAuthenticated) {
                                        onRequireLogin()
                                    } else {
                                        onClaimReward("weekly", item.milestone)
                                    }
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        // Weekly Note
                        Text(
                            text = stringResource(R.string.quest_weekly_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            lineHeight = 14.sp,
                            modifier = Modifier.padding(top = 2.dp, start = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestItemRow(
    title: String,
    milestone: Int,
    rewardCredits: Int,
    currentCount: Int,
    isClaimed: Boolean,
    canClaim: Boolean,
    isClaiming: Boolean,
    onClaimClick: () -> Unit,
) {
    val containerBg = when {
        isClaimed -> GreenDoneBg.copy(alpha = 0.35f)
        canClaim -> GoldBadgeBg
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
    }

    val borderModifier = if (canClaim) {
        Modifier.border(1.dp, GoldStarColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(borderModifier)
            .clip(RoundedCornerShape(12.dp))
            .background(containerBg)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Left info: Icon + Title + Progress count
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = when {
                        isClaimed -> Icons.Filled.CheckCircle
                        canClaim -> Icons.Filled.Star
                        else -> Icons.Filled.Star
                    },
                    contentDescription = null,
                    tint = when {
                        isClaimed -> GreenDoneColor
                        canClaim -> GoldStarColor
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (isClaimed) {
                            "Đã hoàn thành ($currentCount/$milestone)"
                        } else {
                            "Tiến độ: ${currentCount.coerceAtMost(milestone)}/$milestone lượt"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (canClaim) GoldStarColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (canClaim) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Right Action: Claim Button / Claimed Badge / Reward Badge
            when {
                isClaimed -> {
                    // Đã nhận ✓
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(GreenDoneColor.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.quest_claimed_btn),
                            color = GreenDoneColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                canClaim -> {
                    // Nút Nhận thưởng
                    Button(
                        onClick = onClaimClick,
                        enabled = !isClaiming,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32),
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 6.dp
                        ),
                    ) {
                        if (isClaiming) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.quest_claim_btn, rewardCredits),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
                else -> {
                    // Chưa đủ điều kiện -> Badge phần thưởng
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.quest_reward_badge, rewardCredits),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
