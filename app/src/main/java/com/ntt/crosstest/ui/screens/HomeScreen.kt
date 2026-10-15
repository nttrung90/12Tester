package com.ntt.crosstest.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.unit.sp
import com.ntt.crosstest.R
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignOwnerStats
import com.ntt.crosstest.data.LeaderboardUser
import com.ntt.crosstest.data.QuestSummary
import com.ntt.crosstest.ui.components.CampaignCard
import com.ntt.crosstest.ui.components.MyAppCard
import com.ntt.crosstest.ui.components.CreditTopupBadge
import com.ntt.crosstest.ui.components.CreditTopupBottomSheet
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
    onCreateClick: () -> Unit,
    onAdminPanelClick: () -> Unit = {},
    onAccountLocked: () -> Unit = {},
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as TestCheoApp
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer { HomeViewModel(app.campaignRepository, app.applicationContext) }
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
                    text = "CẢNH BÁO TỪ HỆ THỐNG ⚠️",
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
                            text = "Lưu ý: Số lần bị cảnh báo trong tuần: ${uiState.weeklyWarningsCount}/3. Nếu bị 3 lần trong 1 tuần, tài khoản sẽ tự động bị tạm khóa.",
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
                    Text("Tôi đã hiểu")
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
                        onOwnerAppClick(campId)
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
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    if (success) {
                        showTopupBottomSheet = false
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
                    text = "App đã đủ số lượng Test hôm nay",
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    text = "Ứng dụng \"${item.campaign.appName}\" đã đạt mục tiêu ${item.dailyTarget} lượt test trong ngày hôm nay.\n\nBạn có muốn tiếp tục test không? (Nếu tiếp tục, bạn vẫn được hưởng đầy đủ quyền lợi và nhận +2 Credit như bình thường).",
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
                    Text("Có, tiếp tục test")
                }
            },
            dismissButton = {
                TextButton(onClick = { appTargetReachedToConfirm = null }) {
                    Text("Chọn app khác")
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
                            Toast.makeText(context, "Đã dọn dẹp bộ nhớ đệm và làm mới dữ liệu!", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.CleaningServices,
                                contentDescription = "Dọn dẹp bộ nhớ đệm",
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
                                    contentDescription = "Thông báo",
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
                        val initial = (uiState.userProfile?.displayName?.take(1)
                            ?: uiState.userProfile?.email?.take(1)
                            ?: "N").uppercase()
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFC2185B))
                                .clickable {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(4)
                                    }
                                },
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
                            text = "Bạn đang xem dữ liệu ngoại tuyến",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
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
                    items = uiState.searchResults ?: uiState.displayItems,
                    isLoading = uiState.isLoading,
                    error = uiState.error,
                    onRefresh = { viewModel.refresh(context, forceRefresh = true) },
                    onCampaignClick = { item ->
                        val campaign = item.campaign
                        val id = campaign.id ?: return@DiscoverTab
                        if (uiState.isAuthenticated && campaign.ownerId == uiState.userProfile?.id) {
                            onOwnerAppClick(id)
                        } else if (item.isTargetReached && !item.hasTestedToday) {
                            appTargetReachedToConfirm = item
                        } else {
                            onCampaignClick(id)
                        }
                    },
                    isAuthenticated = uiState.isAuthenticated,
                    todayCheckinCount = uiState.todayCheckinCount,
                    userCredits = uiState.userCredits,
                    missedStreak = uiState.missedStreak,
                    onRequireLogin = { showLoginPromptDialog = true },
                )
                1 -> QuestsScreen(
                    questSummary = uiState.questSummary,
                    userCredits = uiState.userCredits,
                    claimingMilestone = uiState.claimingMilestone,
                    isAuthenticated = uiState.isAuthenticated,
                    isLoading = uiState.isLoading,
                    onRefresh = { viewModel.refresh(context, forceRefresh = true) },
                    onClaimReward = { type, milestone ->
                        viewModel.claimQuestReward(type, milestone) { _, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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
                    pendingReportsCount = uiState.pendingReportsCount,
                    isLoading = uiState.isLoading,
                    onRefresh = { viewModel.refresh(context, forceRefresh = true) },
                    onCampaignClick = onCampaignClick,
                    onOwnerAppClick = onOwnerAppClick,
                )
                3 -> LeaderboardTab(
                    leaderboard = uiState.leaderboard,
                    isLoading = uiState.isLeaderboardLoading,
                    onRefresh = { viewModel.refreshLeaderboard(forceRefresh = true) },
                )
                else -> ProfileScreen(
                    onCampaignClick = onCampaignClick,
                    onOwnerAppClick = onOwnerAppClick,
                    onAdminPanelClick = onAdminPanelClick,
                    onSignedOut = onSignedOut,
                )
            }
        }
    }
}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoverTab(
    modifier: Modifier = Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    items: List<CampaignDisplayItem>,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onCampaignClick: (CampaignDisplayItem) -> Unit,
    isAuthenticated: Boolean,
    todayCheckinCount: Int,
    userCredits: Int,
    missedStreak: Int,
    onRequireLogin: () -> Unit,
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val coroutineScope = rememberCoroutineScope()

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: App cần test ⚫🟡🟢                                 12/96 app
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "App cần test",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF263238)))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFFFC107)))
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF4CAF50)))
                }
                val testedCount = items.count { it.hasTestedToday }
                val totalCount = items.size
                Text(
                    text = "$testedCount/$totalCount app",
                    color = Color(0xFFD32F2F),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            if (error != null && items.isEmpty()) {
                ErrorBox(error, Modifier.fillMaxSize())
            } else if (items.isEmpty() && !isLoading) {
                EmptyBox(Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (query.isBlank()) {
                        item(key = "daily_task_card") {
                            DailyTaskCard(
                                todayCheckinCount = todayCheckinCount,
                                userCredits = userCredits,
                                missedStreak = missedStreak,
                                isAuthenticated = isAuthenticated,
                                onRequireLogin = onRequireLogin,
                                onTestAppClick = {
                                    coroutineScope.launch {
                                        if (items.isNotEmpty()) {
                                            listState.animateScrollToItem(1)
                                        }
                                    }
                                },
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                    }

                    itemsIndexed(items, key = { index, item -> "disc_${item.campaign.id ?: item.campaign.packageName}_$index" }) { _, item ->
                        CampaignCard(
                            item = item,
                            onClick = { onCampaignClick(item) },
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) } // room for FAB
                }
            }
        }
    }
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
                                    text = "$pendingReportsCount báo cáo chờ bạn duyệt",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4A148C),
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Không duyệt trong 24h = tự chấp nhận. Góp ý hay → tặng điểm.",
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
                                text = "Bạn chưa đăng ứng dụng nào",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Bấm \"+ Đăng app\" bên dưới để bắt đầu nhận 20 testers hỗ trợ kiểm thử chéo.",
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
                    CampaignCard(campaign, onClick = { campaign.id?.let(onCampaignClick) })
                }
            }

            // Space for stacked FABs
            item { Spacer(Modifier.height(130.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaderboardTab(
    modifier: Modifier = Modifier,
    leaderboard: List<LeaderboardUser>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.leaderboard_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.leaderboard_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (leaderboard.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.leaderboard_empty),
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
                    itemsIndexed(leaderboard, key = { index, user -> "lb_${user.userId}_$index" }) { _, user ->
                        LeaderboardUserCard(user = user)
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun LeaderboardUserCard(user: LeaderboardUser) {
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
            containerColor = if (isTop3) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
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
                Text(
                    text = user.displayName.ifBlank { "Tester #${user.rank}" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isTop3) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

@Composable
private fun EmptyBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.empty_list),
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
