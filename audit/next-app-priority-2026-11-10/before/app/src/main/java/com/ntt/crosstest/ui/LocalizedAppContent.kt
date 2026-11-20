package com.ntt.crosstest.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources

@Composable
internal fun LocalizedAppContent(
    context: Context,
    activity: ComponentActivity,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalConfiguration provides context.resources.configuration,
        LocalContext provides context,
        // Dialog roots re-provide their View's original Context/Configuration.
        // Pin the app resources too, so stringResource keeps the selected language
        // in dialogs and nested dialogs after an in-app language change.
        LocalResources provides context.resources,
        LocalActivity provides activity,
        LocalActivityResultRegistryOwner provides activity,
        content = content,
    )
}
