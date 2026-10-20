import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

// Secrets live in <project>/local.properties (git-ignored) or environment variables.
// Missing keys default to "" so the project still opens/syncs without them;
// the app shows a friendly setup screen at runtime instead of crashing.
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}
fun appSecret(name: String): String =
    (localProperties.getProperty(name) ?: System.getenv(name) ?: "").trim()

android {
    namespace = "com.ntt.crosstest"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ntt.crosstest"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.7"

        buildConfigField("String", "SUPABASE_URL", "\"${appSecret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${appSecret("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${appSecret("GOOGLE_WEB_CLIENT_ID")}\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = appSecret("KEYSTORE_FILE")
            if (keystorePath.isNotEmpty()) {
                val keystoreFile = file(keystorePath).let {
                    if (it.isAbsolute) it else rootProject.file(keystorePath)
                }
                if (keystoreFile.exists()) {
                    storeFile = keystoreFile
                    storePassword = appSecret("KEYSTORE_PASSWORD")
                    keyAlias = appSecret("KEY_ALIAS")
                    keyPassword = appSecret("KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Supabase (auth + database). Versions pinned by the BOM.
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.ktor.client.okhttp)

    // Compose + Material 3
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.coil.compose)

    // EXIF orientation for uploaded screenshots (BitmapFactory ignores it)
    implementation(libs.exifinterface)

    // Google sign-in (Credential Manager + Google ID)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)

    // Navigation, ViewModel, lifecycle
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Background reminder, settings, utils
    implementation(libs.workmanager.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.core.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    // Firebase Storage (Google Cloud Console)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.storage)
    implementation(libs.coroutines.play.services)

    // Play Store update detection for the manual "Check for updates" action
    implementation(libs.play.app.update)
}
