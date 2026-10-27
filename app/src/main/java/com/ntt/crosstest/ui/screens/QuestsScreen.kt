package com.ntt.crosstest.ui.screens

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Mission
import com.ntt.crosstest.data.QuestSummary
import com.ntt.crosstest.ui.components.AppIcon
import com.ntt.crosstest.ui.components.QuestHeroBanner
import com.ntt.crosstest.ui.theme.QuestGoldBgDark
import com.ntt.crosstest.ui.theme.QuestGoldBgLight
import com.ntt.crosstest.ui.theme.QuestGoldTextDark
import com.ntt.crosstest.ui.theme.QuestGoldTextLight
import com.ntt.crosstest.ui.theme.QuestGreenBgDark
import com.ntt.crosstest.ui.theme.QuestGreenBgLight
import com.ntt.crosstest.ui.theme.QuestGreenTextDark
import com.ntt.crosstest.ui.theme.QuestGreenTextLight
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestsScreen(
    questSummary: QuestSummary?,
    userCredits: Int,
    claimingMilestone: String?,
    missions: List<Mission> = emptyList(),
    isMissionsLoading: Boolean = false,
    missionsError: String? = null,
    joiningMissionId: String? = null,
    onJoinMission: (String) -> Unit = {},
    onMissionAppClick: (String) -> Unit = {},
    onRetryMissions: () -> Unit = {},
    isAuthenticated: Boolean,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onClaimReward: (questType: String, milestone: Int) -> Unit,
    onRequireLogin: () -> Unit,
    onNavigateToDiscover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0: Daily, 1: Weekly, 2: App missions
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

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
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Overview Hero Banner
            item {
                QuestHeroBanner(
                    userCredits = userCredits,
                    dailyCount = summary.dailyCount,
                    weeklyCount = summary.weeklyCount,
                    totalClaimable = totalClaimable,
                    onClaimableClick = {
                        val targetTab = if (dailyClaimable > 0) 0 else 1
                        selectedTab = targetTab
                        val milestones =
                            if (targetTab == 0) summary.dailyMilestones else summary.weeklyMilestones
                        val index = milestones.indexOfFirst { milestone ->
                            if (targetTab == 0) {
                                summary.canClaimDaily(milestone.milestone)
                            } else {
                                summary.canClaimWeekly(milestone.milestone)
                            }
                        }
                        if (index >= 0) {
                            coroutineScope.launch {
                                listState.animateScrollToItem(3 + index)
                            }
                        }
                    },
                )
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
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = {
                            Text(
                                text = stringResource(R.string.quest_tab_missions),
                                fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                                    text = stringResource(R.string.quest_progress_today),
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
                                    text = stringResource(R.string.quest_daily_rules_title),
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
            } else if (selectedTab == 1) {
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
                                    text = stringResource(R.string.quest_progress_week),
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
                                    text = stringResource(R.string.quest_weekly_rules_title),
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
            } else {
                // ================= APP MISSIONS (migration 039) =================
                if (isMissionsLoading && missions.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                } else if (missionsError != null && missions.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.mission_load_error),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = missionsError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = onRetryMissions,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.mission_load_error_retry),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                } else if (missions.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    text = stringResource(R.string.mission_list_empty),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.mission_list_empty_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }
                } else {
                    items(missions, key = { mission -> "mission_${mission.id}" }) { mission ->
                        MissionCard(
                            mission = mission,
                            isJoining = joiningMissionId == mission.id,
                            isAuthenticated = isAuthenticated,
                            onJoin = { onJoinMission(mission.id) },
                            onOpenApp = { onMissionAppClick(mission.campaignId) },
                            onRequireLogin = onRequireLogin,
                        )
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
                                text = stringResource(R.string.quest_cta_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.quest_cta_desc),
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
    val isDark = isSystemInDarkTheme()
    val goldText = if (isDark) QuestGoldTextDark else QuestGoldTextLight
    val goldBg = if (isDark) QuestGoldBgDark else QuestGoldBgLight
    val greenText = if (isDark) QuestGreenTextDark else QuestGreenTextLight
    val greenBg = if (isDark) QuestGreenBgDark else QuestGreenBgLight

    val containerBg = when {
        isClaimed -> greenBg
        canClaim -> goldBg
        else -> MaterialTheme.colorScheme.surface
    }

    val borderModifier = if (canClaim) {
        Modifier.border(1.dp, goldText.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
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
                        isClaimed -> greenText
                        canClaim -> goldText
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
                            stringResource(R.string.quest_milestone_completed_tests_format, currentCount, milestone)
                        } else {
                            stringResource(
                                R.string.quest_milestone_progress_format,
                                currentCount.coerceAtMost(milestone),
                                milestone,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (canClaim) goldText else MaterialTheme.colorScheme.onSurfaceVariant,
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
                            .background(greenText.copy(alpha = 0.12f))
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.quest_claimed_btn),
                            color = greenText,
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

/**
 * Thẻ nhiệm vụ do người dùng tạo (sub-tab "Nhiệm vụ App"): hiển thị tên, mô tả,
 * Credit người tham gia được nhận, app liên kết, số chỗ còn lại. Người tham gia
 * bấm "Nhận nhiệm vụ" rồi nộp báo cáo test app; Credit được chuyển khi báo cáo
 * được duyệt.
 */
@Composable
private fun MissionCard(
    mission: Mission,
    isJoining: Boolean,
    isAuthenticated: Boolean,
    onJoin: () -> Unit,
    onOpenApp: () -> Unit,
    onRequireLogin: () -> Unit,
) {
    val ended = mission.isStopped || mission.isExpired
    // Nhiệm vụ đang dừng (paused_by_creator): hiển thị mờ kèm nhãn, không cho nhận/đi test.
    val dimmed = ended || mission.isPaused
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.65f else 1f),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (dimmed) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dimmed) 0.dp else 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Tiêu đề + Credit thưởng
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = mission.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.mission_reward_badge_format, mission.rewardCredits),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Mô tả nhiệm vụ
            if (mission.description.isNotBlank()) {
                Text(
                    text = mission.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 18.sp,
                )
            }

            // App liên kết + người mở nhiệm vụ (M01: cho phép bấm mở app ngay từ header app)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = mission.campaignId.isNotBlank()) { onOpenApp() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(
                    iconUrl = mission.iconUrl.orEmpty(),
                    packageName = mission.packageName,
                    appName = mission.appName,
                    size = 34.dp,
                    shapeRadius = 8.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = mission.appName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (mission.creatorName.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.mission_by_format, mission.creatorName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Chỗ còn lại + số ngày nhận nhiệm vụ
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.mission_slots_format,
                        mission.slotsRemaining,
                        mission.maxParticipants,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                mission.daysRemaining?.let { days ->
                    Text(
                        text = stringResource(R.string.mission_days_remaining_format, days),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Hành động / trạng thái
            when {
                mission.hasCompleted -> {
                    StatusBadge(
                        text = stringResource(R.string.mission_completed_badge),
                        container = Color(0xFF2E7D32).copy(alpha = 0.12f),
                        content = Color(0xFF2E7D32),
                    )
                }
                mission.isMine -> {
                    StatusBadge(
                        text = stringResource(R.string.mission_mine_badge),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                mission.isPaused -> {
                    Text(
                        text = stringResource(R.string.mission_status_paused),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                ended -> {
                    val label = when {
                        mission.isStopped -> stringResource(R.string.mission_status_stopped)
                        else -> stringResource(R.string.mission_status_expired)
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                mission.hasJoined -> {
                    Button(
                        onClick = onOpenApp,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2C4A73),
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.mission_joined_cta),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                mission.isFull -> {
                    Text(
                        text = stringResource(R.string.mission_status_full),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                else -> {
                    Button(
                        onClick = { if (isAuthenticated) onJoin() else onRequireLogin() },
                        enabled = !isJoining,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32),
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        if (isJoining) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.mission_join_btn),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
