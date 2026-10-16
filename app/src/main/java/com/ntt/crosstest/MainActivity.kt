package com.ntt.crosstest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ntt.crosstest.data.AppConfig
import com.ntt.crosstest.data.SessionCacheManager
import com.ntt.crosstest.data.SettingsRepository
import com.ntt.crosstest.data.SupabaseHolder
import com.ntt.crosstest.data.UpdateStatus
import com.ntt.crosstest.ui.navigation.NavGraph
import com.ntt.crosstest.ui.navigation.Routes
import com.ntt.crosstest.ui.screens.ForceUpdateScreen
import com.ntt.crosstest.ui.screens.MissingConfigScreen
import com.ntt.crosstest.ui.screens.OptionalUpdateDialog
import com.ntt.crosstest.ui.theme.TestCheoTheme
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.auth
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun attachBaseContext(newBase: Context) {
        val savedLang = SettingsRepository.getSavedLanguageSync(newBase)
        val locale = SettingsRepository.resolveLocale(savedLang)
        val config = Configuration(newBase.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        val localizedContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(localizedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askNotificationPermissionIfNeeded()

        val app = applicationContext as TestCheoApp

        setContent {
            val languageSetting by app.settingsRepository.appLanguage.collectAsStateWithLifecycle(
                initialValue = SettingsRepository.getSavedLanguageSync(this)
            )
            val configuration = LocalConfiguration.current
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

            val localizedConfig = remember(configuration, currentLocale) {
                Configuration(configuration).apply {
                    setLocale(currentLocale)
                    setLayoutDirection(currentLocale)
                }
            }
            val context = LocalContext.current
            val localizedContext = remember(context, localizedConfig) {
                context.createConfigurationContext(localizedConfig)
            }

            CompositionLocalProvider(
                LocalConfiguration provides localizedConfig,
                LocalContext provides localizedContext,
                LocalActivityResultRegistryOwner provides this,
            ) {
                TestCheoTheme {
                    if (!SupabaseHolder.isInitialized()) {
                        // local.properties is missing the Supabase secrets.
                        MissingConfigScreen()
                        return@TestCheoTheme
                    }

                    val currentVersionCode = BuildConfig.VERSION_CODE
                    val currentVersionName = BuildConfig.VERSION_NAME

                    var appConfig by remember { mutableStateOf<AppConfig?>(null) }
                    var dismissOptionalUpdate by remember { mutableStateOf(false) }

                    LaunchedEffect(Unit) {
                        appConfig = app.appConfigRepository.getAppConfig()
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

                    when (sessionStatus) {
                        is SessionStatus.Initializing -> {
                            if (hasCachedSession) {
                                NavGraph(startDestination = Routes.HOME)
                            } else {
                                LoadingScreen()
                            }
                        }
                        else -> {
                            val startDestination =
                                if (sessionStatus is SessionStatus.Authenticated || (sessionStatus is SessionStatus.NotAuthenticated && hasCachedSession)) {
                                    Routes.HOME
                                } else {
                                    Routes.AUTH
                                }
                            NavGraph(startDestination = startDestination)
                        }
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
