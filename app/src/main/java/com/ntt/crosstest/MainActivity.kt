package com.ntt.crosstest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.ntt.crosstest.data.AppConfig
import com.ntt.crosstest.data.Localizer
import com.ntt.crosstest.data.ReportSubmitManager
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.SettingsRepository
import com.ntt.crosstest.data.SupabaseHolder
import com.ntt.crosstest.data.UpdateStatus
import com.ntt.crosstest.ui.navigation.NavGraph
import com.ntt.crosstest.ui.navigation.Routes
import com.ntt.crosstest.ui.components.CreditReward
import com.ntt.crosstest.ui.components.CreditRewardOverlay
import com.ntt.crosstest.ui.components.CreditRewardType
import com.ntt.crosstest.ui.screens.ForceUpdateScreen
import com.ntt.crosstest.ui.screens.MissingConfigScreen
import com.ntt.crosstest.ui.screens.OptionalUpdateDialog
import com.ntt.crosstest.ui.theme.TestCheoTheme
import com.ntt.crosstest.ui.viewmodels.friendlyError
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.auth
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(SettingsRepository.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialTheme = SettingsRepository.getSavedThemeSync(this)
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        applySystemBarIconAppearance(SettingsRepository.resolveDarkTheme(initialTheme, systemDark))
        askNotificationPermissionIfNeeded()

        val app = applicationContext as TestCheoApp

        setContent {
            val languageSetting by app.settingsRepository.appLanguage.collectAsStateWithLifecycle(
                initialValue = SettingsRepository.getSavedLanguageSync(this)
            )
            val themeSetting by app.settingsRepository.appTheme.collectAsStateWithLifecycle(
                initialValue = SettingsRepository.getSavedThemeSync(this)
            )
            val configuration = LocalConfiguration.current
            val systemDarkTheme =
                (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            val isDarkTheme = SettingsRepository.resolveDarkTheme(themeSetting, systemDarkTheme)

            SideEffect {
                applySystemBarIconAppearance(isDarkTheme)
            }

            val currentLocale = remember(languageSetting, configuration) {
                if (languageSetting == SettingsRepository.LANGUAGE_SYSTEM) {
                    val sysLoc = try {
                        configuration.locales[0] ?: Locale.getDefault()
                    } catch (_: Exception) {
                        Locale.getDefault()
                    }
                    if (sysLoc.language.equals("vi", ignoreCase = true)) Locale.forLanguageTag("vi") else Locale.forLanguageTag("en")
                } else {
                    SettingsRepository.resolveLocale(languageSetting)
                }
            }

            LaunchedEffect(currentLocale) {
                Locale.setDefault(currentLocale)
            }

            val localizedConfig = remember(configuration, currentLocale, isDarkTheme) {
                Configuration(configuration).apply {
                    setLocale(currentLocale)
                    setLayoutDirection(currentLocale)
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        (if (isDarkTheme) {
                            Configuration.UI_MODE_NIGHT_YES
                        } else {
                            Configuration.UI_MODE_NIGHT_NO
                        })
                }
            }
            val context = LocalContext.current
            val localizedContext = remember(context, localizedConfig) {
                context.createConfigurationContext(localizedConfig)
            }

            // Reward overlay for the tester's +10 provisional Credit. Kept at
            // Activity level so it survives navigation away from the campaign.
            var creditReward by remember { mutableStateOf<CreditReward?>(null) }

            CompositionLocalProvider(
                LocalConfiguration provides localizedConfig,
                LocalContext provides localizedContext,
                // LocalContext above is a localized ContextImpl, so LocalActivity
                // cannot be derived from it. Provide the real Activity explicitly
                // for APIs that require one (Credential Manager, finishAffinity...).
                LocalActivity provides this@MainActivity,
                LocalActivityResultRegistryOwner provides this,
            ) {
                TestCheoTheme(darkTheme = isDarkTheme) {
                    if (!SupabaseHolder.isInitialized()) {
                        // local.properties is missing the Supabase secrets.
                        MissingConfigScreen()
                        return@TestCheoTheme
                    }

                    // Nộp báo cáo chạy nền: thông báo toàn cục khi thất bại sau retry
                    // (người dùng có thể đã rời màn chi tiết).
                    LaunchedEffect(Unit) {
                        val localizer = Localizer(app)
                        app.reportSubmitManager.events.collect { event ->
                            when (event) {
                                is ReportSubmitManager.SubmitEvent.Failed -> {
                                    val message = localizer.get(
                                        R.string.campaign_submit_failed_toast_format,
                                        event.appName,
                                        friendlyError(event.error ?: Exception(), localizer),
                                    )
                                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                }
                                is ReportSubmitManager.SubmitEvent.Success -> {
                                    // Extra feedback / update that earns 0 Credit
                                    // must not show a reward overlay.
                                    if (event.provisionalAmount > 0) {
                                        creditReward = CreditReward(
                                            type = CreditRewardType.TEST_SUBMITTED,
                                            amount = event.provisionalAmount,
                                            appName = event.appName,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    val currentVersionCode = BuildConfig.VERSION_CODE
                    val currentVersionName = BuildConfig.VERSION_NAME

                    var appConfig by remember { mutableStateOf<AppConfig?>(null) }
                    var dismissOptionalUpdate by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        // Cache RAM/đĩa trả về tức thì để dialog cập nhật hiện ngay,
                        // sau đó làm mới nền (không chặn UI).
                        appConfig = app.appConfigRepository.getAppConfig()
                        app.appConfigRepository.getAppConfig(forceRefresh = true)?.let { appConfig = it }
                    }

                    val updateStatus = remember(appConfig) {
                        appConfig?.checkUpdateStatus(currentVersionCode) ?: UpdateStatus.NO_UPDATE
                    }

                    // 1. Force update: Block app access completely until updated
                    if (updateStatus == UpdateStatus.FORCE_UPDATE && appConfig != null) {
                        ForceUpdateScreen(
                            config = appConfig!!,
                            currentVersionName = currentVersionName,
                            currentVersionCode = currentVersionCode,
                        )
                        return@TestCheoTheme
                    }

                    // 2. Normal flow with NavGraph
                    val supabase = SupabaseHolder.get()
                    val sessionStatus by supabase.auth.sessionStatus.collectAsStateWithLifecycle()
                    // Read fresh on each recomposition: after a sign-out the
                    // cache is cleared and the app must not keep routing HOME.
                    val hasCachedSession = SessionCacheManager.hasCachedSession(app)

                    if (sessionStatus is SessionStatus.Initializing && !hasCachedSession) {
                        LoadingScreen()
                    } else {
                        val navController = rememberNavController()
                        // Chốt đích khởi đầu một lần duy nhất: các dao động tạm thời
                        // (Initializing khi app vào nền, RefreshFailure khi mạng chập chờn)
                        // KHÔNG được dựng lại NavGraph hay đá người dùng về màn đăng nhập.
                        val startDestination = remember {
                            when {
                                sessionStatus is SessionStatus.Authenticated -> Routes.HOME
                                sessionStatus is SessionStatus.NotAuthenticated -> Routes.AUTH
                                hasCachedSession -> Routes.HOME
                                else -> Routes.AUTH
                            }
                        }
                        var wasAuthenticated by remember {
                            mutableStateOf(startDestination == Routes.HOME)
                        }
                        LaunchedEffect(sessionStatus) {
                            when (sessionStatus) {
                                is SessionStatus.Authenticated -> {
                                    wasAuthenticated = true
                                    // Phiên hồi phục sau lỗi tạm thời (RefreshFailure):
                                    // chỉ vào HOME khi app thực sự đang đứng ở màn đăng nhập.
                                    if (startDestination == Routes.AUTH &&
                                        navController.currentDestination?.route == Routes.AUTH
                                    ) {
                                        navController.navigate(Routes.HOME) {
                                            popUpTo(Routes.AUTH) { inclusive = true }
                                        }
                                    }
                                }
                                is SessionStatus.NotAuthenticated -> {
                                    // Xoá snapshot tập trung, tránh race giữa cache và routing.
                                    if (SessionCacheManager.hasCachedSession(app)) {
                                        SessionCacheManager.clear(app)
                                    }
                                    // Chỉ điều hướng khi trước đó đã đăng nhập thật; guest
                                    // (chưa từng Authenticated) không bị đá về login.
                                    if (wasAuthenticated) {
                                        wasAuthenticated = false
                                        if (navController.currentDestination?.route != Routes.AUTH) {
                                            navController.navigate(Routes.AUTH) { popUpTo(0) }
                                        }
                                    }
                                }
                                else -> {}
                            }
                        }
                        NavGraph(navController = navController, startDestination = startDestination)

                        // Drawn after the NavHost so it stays above every screen.
                        CreditRewardOverlay(
                            reward = creditReward,
                            onDismiss = { creditReward = null },
                        )
                    }

                    // 3. Optional update prompt dialog
                    if (updateStatus == UpdateStatus.OPTIONAL_UPDATE && !dismissOptionalUpdate && appConfig != null) {
                        OptionalUpdateDialog(
                            config = appConfig!!,
                            currentVersionName = currentVersionName,
                            currentVersionCode = currentVersionCode,
                            onDismiss = { dismissOptionalUpdate = true },
                        )
                    }
                }
            }
        }
    }

    private fun applySystemBarIconAppearance(isDarkTheme: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDarkTheme
            isAppearanceLightNavigationBars = !isDarkTheme
        }
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
