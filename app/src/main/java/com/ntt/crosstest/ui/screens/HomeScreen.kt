package com.ntt.crosstest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Surface
import com.ntt.crosstest.util.NetworkMonitor
import com.ntt.crosstest.ui.viewmodels.CampaignDisplayItem
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.DailyTaskStatus
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignOwnerStats
import com.ntt.crosstest.data.CampaignStats
import com.ntt.crosstest.data.LeaderboardUser
import com.ntt.crosstest.data.MyRank
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.Mission
import com.ntt.crosstest.data.QuestSummary
import com.ntt.crosstest.ui.components.CampaignCard
import com.ntt.crosstest.ui.components.MyAppCard
import com.ntt.crosstest.ui.components.CreditTopupBadge
import com.ntt.crosstest.ui.components.CreditTopupBottomSheet
import com.ntt.crosstest.ui.components.CreditRewardOverlay
import com.ntt.crosstest.ui.components.DailyTaskCard
import com.ntt.crosstest.ui.components.NotificationBottomSheet
import com.ntt.crosstest.ui.screens.QuestsScreen
import com.ntt.crosstest.util.TopupConfig
import com.ntt.crosstest.ui.viewmodels.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCampaignClick: (String) -> Unit,
    onOwnerAppClick: (String) -> Unit = onCampaignClick,
    onOwnerReportNotificationClick: (String) -> Unit = onOwnerAppClick,
    onCreateClick: () -> Unit,
    onCreateMissionClick: () -> Unit = {},
    onAdminPanelClick: () -> Unit = {},
    onAdminReportsClick: () -> Unit = {},
    onAccountLocked: () -> Unit = {},
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.applicationContext as TestCheoApp
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    repository = app.campaignRepository,
                    context = app.applicationContext,
                    localizer = Localizer(app),
                    reportSubmitManager = app.reportSubmitManager,
                )
            }
        }
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isOnline by remember(context) {
        NetworkMonitor.observeNetwork(context)
    }.collectAsStateWithLifecycle(initialValue = NetworkMonitor.isOnline(context))
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 5 })
    val coroutineScope = rememberCoroutineScope()
    var showLoginPromptDialog by rememberSaveable { mutableStateOf(false) }
    var showNotificationBottomSheet by rememberSaveable { mutableStateOf(false) }
    var showCreditHistoryDialog by rememberSaveable { mutableStateOf(false) }
    var showTopupBottomSheet by rememberSaveable { mutableStateOf(false) }
    var isTopupProcessing by rememberSaveable { mutableStateOf(false) }
    var appTargetReachedToConfirm by rememberSaveable { mutableStateOf<CampaignDisplayItem?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh(context, forceRefresh = false)
        onPauseOrDispose { }
    }

    LaunchedEffect(uiState.userProfile?.isLocked) {
        if (uiState.userProfile?.isLocked == true) {
            onAccountLocked()
        }
    }

    // System warning popup if tester/owner has an active unread warning
    val activeWarning = uiState.unreadWarning
    if (activeWarning != null) {
        AlertDialog(
            onDismissRequest = { /* Must acknowledge */ },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = Color(0xFFF57F17),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.warning_dialog_title),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD32F2F),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = activeWarning.message,
                        style = MaterialTheme.typography.bodyMedium,
                        lineHeight = 20.sp,
                    )
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.warning_quota_format, uiState.weeklyWarningsCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFC62828),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        activeWarning.id?.let { viewModel.dismissWarning(it) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2C4A73))
                ) {
                    Text(stringResource(R.string.warning_confirm_btn))
                }
            }
        )
    }

    val tabs = listOf(
        Triple(stringResource(R.string.tab_discover), Icons.Filled.Home, 0),
        Triple(stringResource(R.string.tab_quests), Icons.Filled.EmojiEvents, 1),
        Triple(stringResource(R.string.tab_mine), Icons.Filled.Apps, 2),
        Triple(stringResource(R.string.tab_leaderboard), Icons.Filled.Leaderboard, 3),
        Triple(stringResource(R.string.tab_profile), Icons.Filled.Person, 4),
    )

    if (showLoginPromptDialog) {
        AlertDialog(
            onDismissRequest = { showLoginPromptDialog = false },
            title = { Text(stringResource(R.string.require_login_title)) },
            text = { Text(stringResource(R.string.require_login_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLoginPromptDialog = false
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(4)
                        }
                    }
                ) {
                    Text(stringResource(R.string.login_now))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLoginPromptDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showNotificationBottomSheet) {
        NotificationBottomSheet(
            notifications = uiState.notifications,
            unreadCount = uiState.unreadNotificationsCount,
            onMarkAsRead = viewModel::markNotificationAsRead,
            onMarkAllAsRead = viewModel::markAllNotificationsAsRead,
            onDeleteNotification = viewModel::deleteNotification,
            onDismiss = { showNotificationBottomSheet = false },
            onNotificationClick = { notif ->
                val campId = notif.campaignId
                if (!campId.isNullOrBlank()) {
                    showNotificationBottomSheet = false
                    val isOwnerApp = uiState.myCampaigns.any { it.id == campId }
                    if (isOwnerApp) {
                        if (notif.isReport) onOwnerReportNotificationClick(campId)
                        else onOwnerAppClick(campId)
                    } else {
                        onCampaignClick(campId)
                    }
                } else if (notif.isCredit) {
                    showNotificationBottomSheet = false
                    showCreditHistoryDialog = true
                }
            }
        )
    }

    if (showCreditHistoryDialog) {
        CreditHistoryDialog(
            logs = uiState.creditLogs,
            currentBalance = uiState.userCredits,
            showLoadError = uiState.creditLogs.isEmpty() && uiState.creditLogsLoadFailed,
            onDismiss = { showCreditHistoryDialog = false }
        )
    }

    if (showTopupBottomSheet) {
        CreditTopupBottomSheet(
            currentBalance = uiState.userCredits,
            packages = uiState.topupPackages,
            isProcessing = isTopupProcessing,
            onPurchasePackage = { pkg ->
                isTopupProcessing = true
                viewModel.processTopup(pkg) { success, msg ->
                    isTopupProcessing = false
                    if (success) {
                        // The reward overlay is the success feedback and shows
                        // the receipt + updated balance; avoid a duplicate Toast.
                        showTopupBottomSheet = false
                    } else {
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = {
                if (!isTopupProcessing) showTopupBottomSheet = false
            }
        )
    }

    appTargetReachedToConfirm?.let { item ->
        AlertDialog(
            onDismissRequest = { appTargetReachedToConfirm = null },
            title = {
                Text(
                    text = stringResource(R.string.home_target_dialog_title),
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.home_target_dialog_message_format, item.campaign.appName, item.dailyTarget),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val id = item.campaign.id.orEmpty()
                        appTargetReachedToConfirm = null
                        onCampaignClick(id)
                    }
                ) {
                    Text(stringResource(R.string.home_target_dialog_continue_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { appTargetReachedToConfirm = null }) {
                    Text(stringResource(R.string.home_target_dialog_other_btn))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (pagerState.currentPage) {
                            0 -> stringResource(R.string.tab_discover)
                            1 -> stringResource(R.string.tab_quests)
                            2 -> stringResource(R.string.tab_mine)
                            3 -> stringResource(R.string.tab_leaderboard)
                            else -> stringResource(R.string.tab_profile)
                        },
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    if (pagerState.currentPage in 0..2) {
                        CreditTopupBadge(
                            credits = uiState.userCredits,
                            onBadgeClick = {
                                if (uiState.isAuthenticated) {
                                    showCreditHistoryDialog = true
                                } else {
                                    showLoginPromptDialog = true
                                }
                            },
                            onTopupClick = {
                                if (uiState.isAuthenticated) {
                                    showTopupBottomSheet = true
                                } else {
                                    showLoginPromptDialog = true
                                }
                            },
                            isTopupEnabled = TopupConfig.IS_TOPUP_FEATURE_ENABLED,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        IconButton(onClick = {
                            viewModel.clearCacheAndReload(context)
                            Toast.makeText(context, resources.getString(R.string.home_cache_cleared_toast), Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.CleaningServices,
                                contentDescription = stringResource(R.string.home_clear_cache_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            if (uiState.isAuthenticated) {
                                showNotificationBottomSheet = true
                            } else {
                                showLoginPromptDialog = true
                            }
                        }) {
                            val unread = uiState.unreadNotificationsCount
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    imageVector = Icons.Filled.Notifications,
                                    contentDescription = stringResource(R.string.notif_sheet_title),
                                )
                                if (unread > 0) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFD32F2F)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = if (unread > 99) "99+" else "$unread",
                                            color = Color.White,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                        val avatarUrl = uiState.userProfile?.avatarUrl
                        val initial = (uiState.userProfile?.displayName?.takeIf { it.isNotBlank() }?.take(1)
                            ?: uiState.userProfile?.email?.takeIf { it.isNotBlank() }?.take(1)
                            ?: "N").uppercase()
                        val avatarModifier = Modifier
                            .padding(end = 12.dp)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFC2185B))
                            .clickable {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(4)
                                }
                            }
                        if (!avatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = avatarUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = avatarModifier,
                            )
                        } else {
                            Box(
                                modifier = avatarModifier,
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = initial,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val claimableQuests = if (uiState.isAuthenticated) uiState.questSummary?.totalClaimableCount ?: 0 else 0
                tabs.forEach { (label, icon, index) ->
                    NavigationBarItem(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        icon = {
                            if (index == 1 && claimableQuests > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = Color(0xFFE65100),
                                            contentColor = Color.White
                                        ) {
                                            Text("$claimableQuests")
                                        }
                                    }
                                ) {
                                    Icon(icon, contentDescription = label)
                                }
                            } else {
                                Icon(icon, contentDescription = label)
                            }
                        },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (pagerState.currentPage == 2) {
                // 2 FAB chồng nhau: "Đăng APP" + "Mở nhiệm vụ" (MyTab chừa sẵn 130dp)
                Column(horizontalAlignment = Alignment.End) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            if (uiState.isAuthenticated) {
                                onCreateClick()
                            } else {
                                showLoginPromptDialog = true
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.post_app_btn),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    ExtendedFloatingActionButton(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        onClick = {
                            if (uiState.isAuthenticated) {
                                onCreateMissionClick()
                            } else {
                                showLoginPromptDialog = true
                            }
                        },
                    ) {
                        Icon(
                            Icons.Filled.AddTask,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.mission_create_btn),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            } else if (pagerState.currentPage == 0) {
                FloatingActionButton(
                    onClick = {
                        if (uiState.isAuthenticated) {
                            onCreateClick()
                        } else {
                            showLoginPromptDialog = true
                        }
                    }
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.create_campaign))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Subtle 2dp progress bar for silent background server sync
            if (uiState.isBackgroundSyncing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
            }

            // Offline indicator banner when network is disconnected
            AnimatedVisibility(
                visible = !isOnline && uiState.openCampaigns.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WifiOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.home_offline_banner),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            // Bảng xếp hạng tải lazy khi người dùng mở tab (không chặn tải Trang chủ).
            LaunchedEffect(pagerState.currentPage) {
                if (pagerState.currentPage == 3) viewModel.ensureLeaderboardLoaded()
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 4,
                userScrollEnabled = true,
            ) { page ->
            when (page) {
                0 -> DiscoverTab(
                    query = uiState.query,
                    onQueryChange = viewModel::onQueryChange,
                    items = uiState.displayItems,
                    pausedItems = uiState.pausedDisplayItems,
                    completedItems = uiState.completedDisplayItems,
                    searchResults = uiState.searchResults,
                    isLoading = uiState.isLoading,
                    error = uiState.error,
                    onRefresh = { viewModel.refresh(context, forceRefresh = true) },
                    currentUserId = uiState.userProfile?.id,
                    onCampaignClick = { id -> onCampaignClick(id) },
                    onOwnerAppClick = { id -> onOwnerAppClick(id) },
                    onTargetReached = { item -> appTargetReachedToConfirm = item },
                    isAuthenticated = uiState.isAuthenticated,
                    isPenaltyExempt = uiState.userProfile?.canModerate == true,
                    todayCheckinCount = uiState.todayCheckinCount,
                    userCredits = uiState.userCredits,
                    missedStreak = uiState.missedStreak,
                    dailyTask = uiState.dailyTask,
                    onRequireLogin = { showLoginPromptDialog = true },
                )
                1 -> QuestsScreen(
                    questSummary = uiState.questSummary,
                    userCredits = uiState.userCredits,
                    claimingMilestone = uiState.claimingMilestone,
                    missions = uiState.missions,
                    isMissionsLoading = uiState.isMissionsLoading,
                    missionsError = uiState.missionsError,
                    joiningMissionId = uiState.joiningMissionId,
                    onJoinMission = { missionId ->
                        viewModel.joinMission(missionId) { success, msg ->
                            if (success) {
                                Toast.makeText(context, resources.getString(R.string.mission_join_success), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onMissionAppClick = { campaignId -> onCampaignClick(campaignId) },
                    onRetryMissions = { viewModel.refreshMissions(forceRefresh = true) },
                    isAuthenticated = uiState.isAuthenticated,
                    isLoading = uiState.isLoading,
                    onRefresh = { viewModel.refresh(context, forceRefresh = true) },
                    onClaimReward = { type, milestone ->
                        viewModel.claimQuestReward(type, milestone) { success, msg ->
                            // Success is announced by the reward overlay.
                            if (!success) {
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onRequireLogin = { showLoginPromptDialog = true },
                    onNavigateToDiscover = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(0)
                        }
                    },
                )
                2 -> MyTab(
                    isAuthenticated = uiState.isAuthenticated,
                    onGoToProfile = {
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(4)
                        }
                    },
                    myCampaigns = uiState.myCampaigns,
                    joinedCampaigns = uiState.joinedCampaigns,
                    ownerStatsMap = uiState.ownerStatsMap,
                    joinedStatsMap = uiState.joinedStatsMap,
                    ownerNameById = uiState.ownerNameById,
                    pendingReportsCount = uiState.pendingReportsCount,
                    isLoading = uiState.isLoading,
                    onRefresh = { viewModel.refresh(context, forceRefresh = true) },
                    onCampaignClick = onCampaignClick,
                    onOwnerAppClick = onOwnerAppClick,
                )
                3 -> LeaderboardTab(
                    leaderboard = uiState.leaderboard,
                    testLeaderboard = uiState.testLeaderboard,
                    isLoading = uiState.isLeaderboardLoading,
                    isTestLoading = uiState.isTestLeaderboardLoading,
                    myRank = uiState.myRank,
                    currentUserId = uiState.userProfile?.id,
                    myDisplayName = uiState.userProfile?.displayName,
                    onRefresh = { viewModel.refreshLeaderboard(forceRefresh = true) },
                    onTestRefresh = { viewModel.refreshTestLeaderboard(forceRefresh = true) },
                    onTestTabOpened = { viewModel.ensureTestLeaderboardLoaded() },
                )
                else -> ProfileScreen(
                    isActive = pagerState.currentPage == 4,
                    onAdminPanelClick = onAdminPanelClick,
                    onAdminReportsClick = onAdminReportsClick,
                    onSignedOut = onSignedOut,
                )
            }
        }
    }
}

    // Overlays the Scaffold so quest/top-up rewards stay visible above the
    // bottom sheet while it dismisses. Offset below the TopAppBar so the
    // count-up in CreditTopupBadge remains visible during the animation.
    CreditRewardOverlay(
        reward = uiState.reward,
        onDismiss = viewModel::clearReward,
        modifier = Modifier.padding(top = 64.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DiscoverTab(
    modifier: Modifier = Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    items: List<CampaignDisplayItem>,
    pausedItems: List<CampaignDisplayItem>,
    completedItems: List<CampaignDisplayItem>,
    searchResults: List<CampaignDisplayItem>?,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    currentUserId: String?,
    onCampaignClick: (String) -> Unit,
    onOwnerAppClick: (String) -> Unit,
    onTargetReached: (CampaignDisplayItem) -> Unit,
    isAuthenticated: Boolean,
    isPenaltyExempt: Boolean,
    todayCheckinCount: Int,
    userCredits: Int,
    missedStreak: Int,
    dailyTask: DailyTaskStatus?,
    onRequireLogin: () -> Unit,
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    val listsByTab = listOf(items, pausedItems, completedItems)
    val activeItems: List<CampaignDisplayItem> = if (query.isBlank()) {
        listsByTab[selectedTab]
    } else {
        query.let { q ->
            listsByTab[selectedTab].filter {
                it.campaign.appName.contains(q, ignoreCase = true) ||
                    it.campaign.packageName.contains(q, ignoreCase = true) ||
                    it.ownerName.contains(q, ignoreCase = true)
            }
        }
    }

    val onCardClick: (CampaignDisplayItem) -> Unit = { item ->
        val campaign = item.campaign
        val id = campaign.id
        if (id != null) {
            when {
                currentUserId != null && campaign.ownerId == currentUserId -> onOwnerAppClick(id)
                campaign.isOpen -> {
                    if (item.isTargetReached && !item.hasTestedToday) onTargetReached(item)
                    else onCampaignClick(id)
                }
                else -> onCampaignClick(id)
            }
        }
    }

    val allListsEmpty = items.isEmpty() && pausedItems.isEmpty() && completedItems.isEmpty()

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        if (error != null && allListsEmpty) {
            ErrorBox(error, Modifier.fillMaxSize())
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (query.isBlank()) {
                    item(key = "daily_task_card") {
                        DailyTaskCard(
                            todayCheckinCount = todayCheckinCount,
                            userCredits = userCredits,
                            missedStreak = missedStreak,
                            isAuthenticated = isAuthenticated,
                            isPenaltyExempt = isPenaltyExempt,
                            dailyTask = dailyTask,
                            onRequireLogin = onRequireLogin,
                            onTestAppClick = {
                                coroutineScope.launch {
                                    selectedTab = 0
                                    if (items.isNotEmpty()) {
                                        listState.animateScrollToItem(2)
                                    }
                                }
                            },
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                }

                stickyHeader(key = "home_sub_tabs") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        TabRow(selectedTabIndex = selectedTab) {
                            HomeSubTab(
                                selected = selectedTab == 0,
                                label = stringResource(R.string.home_tab_need_test),
                                count = items.size,
                                onClick = { selectedTab = 0 },
                            )
                            HomeSubTab(
                                selected = selectedTab == 1,
                                label = stringResource(R.string.home_tab_paused),
                                count = pausedItems.size,
                                onClick = { selectedTab = 1 },
                            )
                            HomeSubTab(
                                selected = selectedTab == 2,
                                label = stringResource(R.string.home_tab_completed),
                                count = completedItems.size,
                                onClick = { selectedTab = 2 },
                            )
                        }
                        when (selectedTab) {
                            0 -> {
                                val testedCount = items.count { it.hasTestedToday }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF263238)))
                                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFFFC107)))
                                        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF4CAF50)))
                                    }
                                    Text(
                                        text = stringResource(R.string.home_tested_progress_format, testedCount, items.size),
                                        color = Color(0xFFD32F2F),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                            }
                            1 -> TabHint(stringResource(R.string.home_paused_hint))
                            else -> TabHint(stringResource(R.string.home_completed_hint))
                        }
                    }
                }

                if (activeItems.isEmpty() && !isLoading) {
                    item(key = "empty_$selectedTab") {
                        EmptyBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp),
                            text = when (selectedTab) {
                                0 -> stringResource(R.string.home_empty_open)
                                1 -> stringResource(R.string.home_empty_paused)
                                else -> stringResource(R.string.home_empty_completed)
                            },
                        )
                    }
                } else {
                    itemsIndexed(activeItems, key = { index, item -> "disc_${item.campaign.id ?: item.campaign.packageName}_$index" }) { _, item ->
                        CampaignCard(
                            item = item,
                            onClick = { onCardClick(item) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                item { Spacer(Modifier.height(72.dp)) } // room for FAB
            }
        }
    }
}

@Composable
private fun HomeSubTab(
    selected: Boolean,
    label: String,
    count: Int,
    onClick: () -> Unit,
) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = count.toString(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun TabHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyTab(
    modifier: Modifier = Modifier,
    isAuthenticated: Boolean,
    onGoToProfile: () -> Unit,
    myCampaigns: List<Campaign>,
    joinedCampaigns: List<Campaign>,
    ownerStatsMap: Map<String, CampaignOwnerStats>,
    joinedStatsMap: Map<String, CampaignStats>,
    ownerNameById: Map<String, String>,
    pendingReportsCount: Int,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onCampaignClick: (String) -> Unit,
    onOwnerAppClick: (String) -> Unit,
) {
    if (!isAuthenticated) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Apps,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.tab_mine),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.guest_my_apps_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onGoToProfile) {
                        Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.login_now))
                    }
                }
            }
        }
        return
    }

    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Banner chờ duyệt (Matching Image 1) - Chỉ hiện khi có báo cáo chờ duyệt thực tế
            if (pendingReportsCount > 0 && myCampaigns.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                val targetApp = myCampaigns.find { (ownerStatsMap[it.id]?.pendingReportsCount ?: 0) > 0 }
                                    ?: myCampaigns.firstOrNull()
                                if (targetApp?.id != null) onOwnerAppClick(targetApp.id)
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFF6EEFB)
                        ),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Assignment,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = Color(0xFF6A1B9A),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.home_pending_reports_format, pendingReportsCount),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4A148C),
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.home_pending_reports_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF6A1B9A),
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFF6A1B9A),
                            )
                        }
                    }
                }
            }

            // Apps created by me (Owner Apps using MyAppCard)
            if (myCampaigns.isEmpty()) {
                item {
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
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = stringResource(R.string.home_owner_empty_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.home_owner_empty_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(myCampaigns, key = { index, item -> "my_${item.id ?: item.packageName}_$index" }) { _, campaign ->
                    MyAppCard(
                        campaign = campaign,
                        stats = ownerStatsMap[campaign.id],
                        onClick = { campaign.id?.let(onOwnerAppClick) },
                        onTipClick = { campaign.id?.let(onOwnerAppClick) },
                    )
                }
            }

            // Joined apps
            if (joinedCampaigns.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.joined_by_me),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                itemsIndexed(joinedCampaigns, key = { index, item -> "joined_${item.id ?: item.packageName}_$index" }) { _, campaign ->
                    val stats = campaign.id?.let { joinedStatsMap[it] }
                    CampaignCard(
                        campaign = campaign,
                        onClick = { campaign.id?.let(onCampaignClick) },
                        ownerName = campaign.ownerId?.let { ownerNameById[it] }.orEmpty(),
                        installedCount = stats?.installedCount ?: 0,
                        todayTestedCount = stats?.todayTestedCount ?: 0,
                        hasTestedToday = stats?.hasTestedToday ?: false,
                    )
                }
            }

            // Space for stacked FABs
            item { Spacer(Modifier.height(130.dp)) }
        }
    }
}

// Tạm ẩn sub-tab "Top Test" trong tab Xếp hạng. Đổi thành true để bật lại.
private const val SHOW_LEADERBOARD_TEST_TAB = false

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaderboardTab(
    modifier: Modifier = Modifier,
    leaderboard: List<LeaderboardUser>,
    testLeaderboard: List<LeaderboardUser>,
    isLoading: Boolean,
    isTestLoading: Boolean,
    myRank: MyRank?,
    currentUserId: String?,
    myDisplayName: String?,
    onRefresh: () -> Unit,
    onTestRefresh: () -> Unit,
    onTestTabOpened: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val isTestTab = SHOW_LEADERBOARD_TEST_TAB && selectedTab == 1
    val activeList = if (isTestTab) testLeaderboard else leaderboard
    val activeLoading = if (isTestTab) isTestLoading else isLoading

    val creditListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val testListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val listState = if (isTestTab) testListState else creditListState

    // Tải lazy bảng Top Test khi người dùng mở sub-tab.
    LaunchedEffect(selectedTab) {
        if (isTestTab) onTestTabOpened()
    }

    PullToRefreshBox(
        isRefreshing = activeLoading,
        onRefresh = if (isTestTab) onTestRefresh else onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(Modifier.height(8.dp))

            TabRow(
                selectedTabIndex = if (SHOW_LEADERBOARD_TEST_TAB) selectedTab else 0,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Tab(
                    selected = !isTestTab,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = stringResource(R.string.leaderboard_tab_credit),
                            fontWeight = if (!isTestTab) FontWeight.Bold else FontWeight.Medium,
                        )
                    },
                )
                if (SHOW_LEADERBOARD_TEST_TAB) {
                    Tab(
                        selected = isTestTab,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                text = stringResource(R.string.leaderboard_tab_test),
                                fontWeight = if (isTestTab) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Thẻ ghim hạng của người dùng hiện tại (chỉ tab Top Credit, đã đăng nhập).
            if (!isTestTab && currentUserId != null && myRank != null && myRank.rank > 0) {
                MyRankCard(
                    myRank = myRank,
                    displayName = myDisplayName,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
            }

            if (activeList.isEmpty() && !activeLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(if (isTestTab) R.string.leaderboard_test_empty else R.string.leaderboard_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(activeList, key = { index, user -> "lb_${selectedTab}_${user.userId}_$index" }) { _, user ->
                        LeaderboardUserCard(
                            user = user,
                            isCurrentUser = currentUserId != null && user.userId == currentUserId,
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LeaderboardUserCard(user: LeaderboardUser, isCurrentUser: Boolean = false) {
    val rankBadge = when (user.rank) {
        1 -> "🥇"
        2 -> "🥈"
        3 -> "🥉"
        else -> "#${user.rank}"
    }

    val isTop3 = user.rank in 1..3

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isCurrentUser -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                isTop3 -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        border = if (isCurrentUser) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
        } else {
            null
        },
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Rank badge / text
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isTop3) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = rankBadge,
                    style = if (isTop3) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (isTop3) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(14.dp))

            // User Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = user.displayName.ifBlank { "Tester #${user.rank}" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (isTop3) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isCurrentUser) {
                        Spacer(Modifier.width(8.dp))
                        YouBadge()
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.test_count_format, user.checkinCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.app_count_format, user.campaignsCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Credit Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isTop3) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "🪙 ${stringResource(R.string.credit_badge_format, user.credits)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isTop3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Thẻ ghim "Hạng của bạn" đầu tab Xếp hạng — hạng chính xác kể cả khi ngoài Top 100. */
@Composable
private fun MyRankCard(myRank: MyRank, displayName: String?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.leaderboard_my_rank_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (myRank.totalUsers > 0) {
                        stringResource(R.string.leaderboard_rank_of_total, myRank.rank, myRank.totalUsers)
                    } else {
                        "#${myRank.rank}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayName?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.leaderboard_you),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        YouBadge()
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.test_count_format, myRank.checkinCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(R.string.app_count_format, myRank.campaignsCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // Credit Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "🪙 ${stringResource(R.string.credit_badge_format, myRank.credits)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun YouBadge() {
    Text(
        text = stringResource(R.string.leaderboard_you),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun EmptyBox(modifier: Modifier = Modifier, text: String = stringResource(R.string.empty_list)) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorBox(error: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text = error, color = MaterialTheme.colorScheme.error)
    }
}
