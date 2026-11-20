package com.ntt.crosstest.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ntt.crosstest.TestCheoApp
import com.ntt.crosstest.ui.screens.AccountLockedScreen
import com.ntt.crosstest.ui.screens.AdminAuditScreen
import com.ntt.crosstest.ui.screens.AdminBusinessPolicyScreen
import com.ntt.crosstest.ui.screens.AdminCampaignsScreen
import com.ntt.crosstest.ui.screens.AdminNotificationCampaignScreen
import com.ntt.crosstest.ui.screens.AdminReportsScreen
import com.ntt.crosstest.ui.screens.AdminSystemMonitorScreen
import com.ntt.crosstest.ui.screens.AdminTesterReportsQueueScreen
import com.ntt.crosstest.ui.screens.AdminUsersScreen
import com.ntt.crosstest.ui.screens.AuthScreen
import com.ntt.crosstest.ui.screens.CampaignDetailScreen
import com.ntt.crosstest.ui.screens.CarryoverPolicyScreen
import com.ntt.crosstest.ui.screens.CreateCampaignScreen
import com.ntt.crosstest.ui.screens.HomeScreen
import com.ntt.crosstest.ui.screens.MemberDetailScreen
import com.ntt.crosstest.ui.screens.MissionCreateScreen
import com.ntt.crosstest.ui.screens.AppUpdateReleaseScreen
import com.ntt.crosstest.ui.screens.OwnerAppDetailScreen
import com.ntt.crosstest.ui.screens.PostAppGuideScreen
import kotlinx.coroutines.launch

object Routes {
    const val AUTH = "auth"
    const val HOME = "home"
    const val CAMPAIGN_DETAIL = "campaign/{campaignId}?missionId={missionId}"
    const val OWNER_APP_DETAIL = "owner_app/{campaignId}?openReports={openReports}"
    const val APP_UPDATE_RELEASE = "owner_app/{campaignId}/update_release"
    const val CREATE = "create"
    const val MISSION_CREATE = "mission_create"
    const val POST_APP_GUIDE = "post_app_guide"
    const val ADMIN_USERS = "admin_users"
    const val ADMIN_REPORTS = "admin_reports"
    const val ADMIN_MEMBER_DETAIL = "admin_member/{userId}"
    const val ADMIN_CAMPAIGNS = "admin_campaigns"
    const val ADMIN_TESTER_REPORTS = "admin_tester_reports"
    const val ADMIN_AUDIT = "admin_audit"
    const val ADMIN_MONITOR = "admin_monitor"
    const val ADMIN_CARRYOVER = "admin_carryover"
    const val ADMIN_NOTIFICATION_CAMPAIGNS = "admin_notification_campaigns"
    const val ADMIN_BUSINESS_POLICIES = "admin_business_policies"
    const val ACCOUNT_LOCKED = "account_locked"
    const val MISSION_REPORT = "mission_report/{campaignId}/{missionId}"

    fun campaignDetail(campaignId: String, missionId: String? = null) =
        if (missionId != null) "campaign/$campaignId?missionId=$missionId" else "campaign/$campaignId"
    fun missionReport(campaignId: String, missionId: String) =
        "mission_report/$campaignId/$missionId"
    fun ownerAppDetail(campaignId: String, openReports: Boolean = false) =
        "owner_app/$campaignId?openReports=$openReports"
    fun appUpdateRelease(campaignId: String) =
        "owner_app/$campaignId/update_release"
    fun adminMemberDetail(userId: String) =
        "admin_member/$userId"
}

@Composable
fun NavGraph(navController: NavHostController, startDestination: String) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.AUTH) {
            AuthScreen(
                onSignedIn = {
                    if (navController.currentDestination?.route == Routes.AUTH) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                        }
                    }
                },
                onContinueAsGuest = {
                    if (navController.currentDestination?.route == Routes.AUTH) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.AUTH) { inclusive = true }
                        }
                    }
                }
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onCampaignClick = { id -> navController.navigate(Routes.campaignDetail(id)) },
                onMissionAppClick = { cId, mId ->
                    if (!mId.isNullOrBlank()) {
                        navController.navigate(Routes.missionReport(cId, mId))
                    } else {
                        navController.navigate(Routes.campaignDetail(cId))
                    }
                },
                onOwnerAppClick = { id -> navController.navigate(Routes.ownerAppDetail(id)) },
                onOwnerReportNotificationClick = { id ->
                    navController.navigate(Routes.ownerAppDetail(id, openReports = true))
                },
                onCreateClick = { navController.navigate(Routes.CREATE) },
                onCreateMissionClick = { navController.navigate(Routes.MISSION_CREATE) },
                onAdminPanelClick = { navController.navigate(Routes.ADMIN_USERS) },
                onAdminReportsClick = { navController.navigate(Routes.ADMIN_REPORTS) },
                onAccountLocked = {
                    navController.navigate(Routes.ACCOUNT_LOCKED) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
                onSignedOut = {
                    if (navController.currentDestination?.route != Routes.AUTH) {
                        navController.navigate(Routes.AUTH) { popUpTo(0) }
                    }
                },
            )
        }
        composable(
            route = Routes.CAMPAIGN_DETAIL,
            arguments = listOf(
                navArgument("campaignId") { type = NavType.StringType },
                navArgument("missionId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val campaignId = backStackEntry.arguments?.getString("campaignId").orEmpty()
            val missionId = backStackEntry.arguments?.getString("missionId")
            if (!missionId.isNullOrBlank()) {
                androidx.compose.runtime.LaunchedEffect(campaignId, missionId) {
                    navController.navigate(Routes.missionReport(campaignId, missionId)) {
                        popUpTo(Routes.campaignDetail(campaignId, missionId)) { inclusive = true }
                    }
                }
            } else {
                CampaignDetailScreen(
                    campaignId = campaignId,
                    initialMissionId = missionId,
                    onBack = { navController.popBackStack() },
                    onOwnerManageClick = { id ->
                        navController.navigate(Routes.ownerAppDetail(id)) {
                            popUpTo(Routes.campaignDetail(id)) { inclusive = true }
                        }
                    },
                    onNavigateToCampaign = { nextId ->
                        navController.navigate(Routes.campaignDetail(nextId)) {
                            popUpTo(Routes.campaignDetail(campaignId)) { inclusive = true }
                        }
                    },
                    onAccountLocked = {
                        navController.navigate(Routes.ACCOUNT_LOCKED) {
                            popUpTo(0)
                        }
                    },
                )
            }
        }
        composable(
            route = Routes.MISSION_REPORT,
            arguments = listOf(
                navArgument("campaignId") { type = NavType.StringType },
                navArgument("missionId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val campaignId = backStackEntry.arguments?.getString("campaignId").orEmpty()
            val missionId = backStackEntry.arguments?.getString("missionId").orEmpty()
            com.ntt.crosstest.ui.screens.MissionReportScreen(
                campaignId = campaignId,
                missionId = missionId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.OWNER_APP_DETAIL,
            arguments = listOf(
                navArgument("campaignId") { type = NavType.StringType },
                navArgument("openReports") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { backStackEntry ->
            val campaignId = backStackEntry.arguments?.getString("campaignId").orEmpty()
            val openReports = backStackEntry.arguments?.getBoolean("openReports") == true
            OwnerAppDetailScreen(
                campaignId = campaignId,
                openReports = openReports,
                onBack = { navController.popBackStack() },
                onAccountLocked = {
                    navController.navigate(Routes.ACCOUNT_LOCKED) {
                        popUpTo(0)
                    }
                },
                onNavigateToAppUpdate = { id ->
                    navController.navigate(Routes.appUpdateRelease(id))
                },
            )
        }
        composable(
            route = Routes.APP_UPDATE_RELEASE,
            arguments = listOf(
                navArgument("campaignId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val campaignId = backStackEntry.arguments?.getString("campaignId").orEmpty()
            AppUpdateReleaseScreen(
                campaignId = campaignId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CREATE) {
            CreateCampaignScreen(
                onBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() },
                onNavigateToGuide = { navController.navigate(Routes.POST_APP_GUIDE) },
            )
        }
        composable(Routes.MISSION_CREATE) {
            MissionCreateScreen(
                onBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() },
            )
        }
        composable(Routes.POST_APP_GUIDE) {
            val app = LocalContext.current.applicationContext as TestCheoApp
            val coroutineScope = rememberCoroutineScope()
            PostAppGuideScreen(
                onBack = { navController.popBackStack() },
                onConfirmed = {
                    coroutineScope.launch {
                        app.settingsRepository.setTestingGuideConfirmed(true)
                    }
                    navController.popBackStack()
                }
            )
        }
        composable(Routes.ADMIN_USERS) {
            AdminUsersScreen(
                onBack = { navController.popBackStack() },
                onNavigateToMemberDetail = { userId -> navController.navigate(Routes.adminMemberDetail(userId)) },
                onNavigateToCampaigns = { navController.navigate(Routes.ADMIN_CAMPAIGNS) },
                onNavigateToTesterReportsQueue = { navController.navigate(Routes.ADMIN_TESTER_REPORTS) },
                onNavigateToReports = { navController.navigate(Routes.ADMIN_REPORTS) },
                onNavigateToAudit = { navController.navigate(Routes.ADMIN_AUDIT) },
                onNavigateToSystemMonitor = { navController.navigate(Routes.ADMIN_MONITOR) },
                onNavigateToCarryover = { navController.navigate(Routes.ADMIN_CARRYOVER) },
                onNavigateToNotificationCampaigns = { navController.navigate(Routes.ADMIN_NOTIFICATION_CAMPAIGNS) },
                onNavigateToBusinessPolicies = { navController.navigate(Routes.ADMIN_BUSINESS_POLICIES) },
            )
        }
        composable(Routes.ADMIN_REPORTS) {
            AdminReportsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ADMIN_NOTIFICATION_CAMPAIGNS) {
            AdminNotificationCampaignScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ADMIN_BUSINESS_POLICIES) {
            AdminBusinessPolicyScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            Routes.ADMIN_MEMBER_DETAIL,
            arguments = listOf(navArgument("userId") { type = NavType.StringType })
        ) { backStackEntry ->
            val userId = backStackEntry.arguments?.getString("userId").orEmpty()
            MemberDetailScreen(
                userId = userId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ADMIN_CAMPAIGNS) {
            AdminCampaignsScreen(
                onBack = { navController.popBackStack() },
                onOpenCampaignDetail = { id -> navController.navigate(Routes.campaignDetail(id)) },
            )
        }
        composable(Routes.ADMIN_TESTER_REPORTS) {
            AdminTesterReportsQueueScreen(
                onBack = { navController.popBackStack() },
                onOpenCampaign = { id -> navController.navigate(Routes.campaignDetail(id)) },
            )
        }
        composable(Routes.ADMIN_AUDIT) {
            AdminAuditScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ADMIN_MONITOR) {
            AdminSystemMonitorScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ADMIN_CARRYOVER) {
            CarryoverPolicyScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ACCOUNT_LOCKED) {
            AccountLockedScreen(
                onUnlocked = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ACCOUNT_LOCKED) { inclusive = true }
                    }
                },
                onSignedOut = {
                    if (navController.currentDestination?.route != Routes.AUTH) {
                        navController.navigate(Routes.AUTH) { popUpTo(0) }
                    }
                }
            )
        }
    }
}
