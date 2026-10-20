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
import com.ntt.crosstest.ui.screens.AdminReportsScreen
import com.ntt.crosstest.ui.screens.AdminUsersScreen
import com.ntt.crosstest.ui.screens.AuthScreen
import com.ntt.crosstest.ui.screens.CampaignDetailScreen
import com.ntt.crosstest.ui.screens.CreateCampaignScreen
import com.ntt.crosstest.ui.screens.HomeScreen
import com.ntt.crosstest.ui.screens.OwnerAppDetailScreen
import com.ntt.crosstest.ui.screens.PostAppGuideScreen
import kotlinx.coroutines.launch

object Routes {
    const val AUTH = "auth"
    const val HOME = "home"
    const val CAMPAIGN_DETAIL = "campaign/{campaignId}"
    const val OWNER_APP_DETAIL = "owner_app/{campaignId}?openReports={openReports}"
    const val CREATE = "create"
    const val POST_APP_GUIDE = "post_app_guide"
    const val ADMIN_USERS = "admin_users"
    const val ADMIN_REPORTS = "admin_reports"
    const val ACCOUNT_LOCKED = "account_locked"

    fun campaignDetail(campaignId: String) = "campaign/$campaignId"
    fun ownerAppDetail(campaignId: String, openReports: Boolean = false) =
        "owner_app/$campaignId?openReports=$openReports"
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
                onOwnerAppClick = { id -> navController.navigate(Routes.ownerAppDetail(id)) },
                onOwnerReportNotificationClick = { id ->
                    navController.navigate(Routes.ownerAppDetail(id, openReports = true))
                },
                onCreateClick = { navController.navigate(Routes.CREATE) },
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
            arguments = listOf(navArgument("campaignId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val campaignId = backStackEntry.arguments?.getString("campaignId").orEmpty()
            CampaignDetailScreen(
                campaignId = campaignId,
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
            )
        }
        composable(Routes.CREATE) {
            CreateCampaignScreen(
                onBack = { navController.popBackStack() },
                onCreated = { navController.popBackStack() },
                onNavigateToGuide = { navController.navigate(Routes.POST_APP_GUIDE) },
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
            )
        }
        composable(Routes.ADMIN_REPORTS) {
            AdminReportsScreen(
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
