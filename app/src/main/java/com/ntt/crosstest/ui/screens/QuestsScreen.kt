package com.ntt.crosstest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntt.crosstest.R
import com.ntt.crosstest.data.QuestSummary

private val GoldStarColor = Color(0xFFF57F17)
private val GoldBadgeBg = Color(0xFFFFF8E1)
private val GreenDoneColor = Color(0xFF2E7D32)
private val GreenDoneBg = Color(0xFFE8F5E9)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestsScreen(
    questSummary: QuestSummary?,
    userCredits: Int,
    claimingMilestone: String?,
    isAuthenticated: Boolean,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onClaimReward: (questType: String, milestone: Int) -> Unit,
    onRequireLogin: () -> Unit,
    onNavigateToDiscover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0: Daily, 1: Weekly

    val summary = questSummary ?: QuestSummary()
    val totalClaimable = if (isAuthenticated) summary.totalClaimableCount else 0
    val dailyClaimable = if (isAuthenticated) summary.dailyClaimableCount else 0
    val weeklyClaimable = if (isAuthenticated) summary.weeklyClaimableCount else 0

    val dailyProgress = (summary.dailyCount.toFloat() / 50f).coerceIn(0f, 1f)
    val animatedDailyProgress by animateFloatAsState(targetValue = dailyProgress, label = "dailyProgress")
    val weeklyProgress = (summary.weeklyCount.toFloat() / 300f).coerceIn(0f, 1f)
    val animatedWeeklyProgress by animateFloatAsState(targetValue = weeklyProgress, label = "weeklyProgress")

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Overview Hero Banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(GoldBadgeBg),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.EmojiEvents,
                                    contentDescription = null,
                                    tint = GoldStarColor,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.quest_section_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "Kiểm thử app để nhận thêm Credit thưởng",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Stats Row: [Số dư] | [Hôm nay] | [Tuần này]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                                .padding(vertical = 10.dp, horizontal = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Số dư",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "$userCredits 🪙",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Hôm nay",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${summary.dailyCount} app",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color(0xFF2E7D32),
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Tuần này",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "${summary.weeklyCount} app",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color(0xFF1565C0),
                                )
                            }
                        }

                        // Claimable notification banner
                        if (totalClaimable > 0) {
                            Spacer(Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFFE65100))
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "🎉 Bạn có $totalClaimable mốc nhiệm vụ đã sẵn sàng nhận thưởng!",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }

            // 2. Segmented Tabs: [ Nhiệm vụ Ngày ] | [ Nhiệm vụ Tuần ]
            item {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
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
                                            .size(18.dp)
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
                                            .size(18.dp)
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
            }

            // 3. Tab Content
            if (selectedTab == 0) {
                // ================= DAILY QUESTS =================
                val dailyCount = summary.dailyCount

                // Overall progress card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Tiến độ hôm nay",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(R.string.quest_daily_progress_format, dailyCount),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { animatedDailyProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(9.dp)
                                    .clip(RoundedCornerShape(5.dp)),
                            )
                        }
                    }
                }

                // Daily Milestone Items
                summary.dailyMilestones.forEach { item ->
                    item(key = "daily_${item.milestone}") {
                        val isClaimed = summary.isDailyMilestoneClaimed(item.milestone)
                        val canClaim = summary.canClaimDaily(item.milestone)
                        val isClaiming = claimingMilestone == "daily_${item.milestone}"

                        val title = when (item.milestone) {
                            10 -> stringResource(R.string.quest_milestone_daily_10)
                            20 -> stringResource(R.string.quest_milestone_daily_20)
                            else -> stringResource(R.string.quest_milestone_daily_50)
                        }

                        QuestItemCard(
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
                    }
                }

                // Daily Info Note
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Quy tắc điểm thưởng hàng ngày",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.quest_daily_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                    }
                }
            } else {
                // ================= WEEKLY QUESTS =================
                val weeklyCount = summary.weeklyCount

                // Overall progress card
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Tiến độ tuần này",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(R.string.quest_weekly_progress_format, weeklyCount),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { animatedWeeklyProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(9.dp)
                                    .clip(RoundedCornerShape(5.dp)),
                            )
                        }
                    }
                }

                // Weekly Milestone Items
                summary.weeklyMilestones.forEach { item ->
                    item(key = "weekly_${item.milestone}") {
                        val isClaimed = summary.isWeeklyMilestoneClaimed(item.milestone)
                        val canClaim = summary.canClaimWeekly(item.milestone)
                        val isClaiming = claimingMilestone == "weekly_${item.milestone}"

                        val title = when (item.milestone) {
                            150 -> stringResource(R.string.quest_milestone_weekly_150)
                            else -> stringResource(R.string.quest_milestone_weekly_300)
                        }

                        QuestItemCard(
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
                    }
                }

                // Weekly Info Note
                item {
                    OutlinedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Quy tắc nhiệm vụ tuần",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.quest_weekly_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    lineHeight = 18.sp,
                                )
                            }
                        }
                    }
                }
            }

            // 4. CTA to discover tab to test apps
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onNavigateToDiscover() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Bắt đầu làm nhiệm vụ ngay 🚀",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Cài đặt và test app của các thành viên khác tại Trang chủ để tích lũy lượt và nhận thưởng.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun QuestItemCard(
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
        else -> MaterialTheme.colorScheme.surface
    }

    val borderModifier = if (canClaim) {
        Modifier.border(1.dp, GoldStarColor.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
    } else {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(borderModifier),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        elevation = CardDefaults.cardElevation(defaultElevation = if (canClaim) 2.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
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
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (isClaimed) {
                            "Đã hoàn thành ($currentCount/$milestone lượt)"
                        } else {
                            "Tiến độ: ${currentCount.coerceAtMost(milestone)}/$milestone lượt"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (canClaim) GoldStarColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (canClaim) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Right Action: Claim Button / Claimed Badge / Reward Badge
            when {
                isClaimed -> {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(GreenDoneColor.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.quest_claimed_btn),
                            color = GreenDoneColor,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                canClaim -> {
                    Button(
                        onClick = onClaimClick,
                        enabled = !isClaiming,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32),
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
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
                                    modifier = Modifier.size(16.dp),
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
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
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
