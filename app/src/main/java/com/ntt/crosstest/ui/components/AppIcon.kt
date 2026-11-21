package com.ntt.crosstest.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ntt.crosstest.ui.theme.statusColors
import com.ntt.crosstest.util.InstalledAppCache

/**
 * Universal app icon component.
 * Priority:
 * 1. iconUrl (if valid remote HTTP/HTTPS or local model)
 * 2. Locally installed application icon from InstalledAppCache
 * 3. Fallback placeholder with the first letter of appName
 */
@Composable
fun AppIcon(
    iconUrl: String,
    packageName: String,
    appName: String = "",
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    shapeRadius: Dp = 12.dp,
    showProductionBadge: Boolean = false,
    productionBadgeContentDescription: String? = null,
) {
    val context = LocalContext.current
    val cacheVersionState = androidx.compose.runtime.produceState(initialValue = InstalledAppCache.version.get()) {
        InstalledAppCache.versionFlow.collect { value = it }
    }
    val cacheVersion = cacheVersionState.value

    val localDrawable: Drawable? = if (iconUrl.isNotBlank()) {
        null
    } else {
        val peeked = remember(packageName, cacheVersion) { InstalledAppCache.peekInstalledAppDrawable(packageName) }
        val loadedState = androidx.compose.runtime.produceState<Drawable?>(initialValue = peeked, key1 = packageName, key2 = cacheVersion) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                InstalledAppCache.getInstalledAppDrawable(context, packageName)
            }
        }
        loadedState.value
    }

    val (effectiveIconUrl, setEffectiveIconUrl) = androidx.compose.runtime.remember(iconUrl) {
        androidx.compose.runtime.mutableStateOf(
            com.ntt.crosstest.util.IconRoutingHelper.resolveIconUrl(iconUrl)
        )
    }

    val modelToLoad: Any? = when {
        effectiveIconUrl.isNotBlank() -> effectiveIconUrl
        localDrawable != null -> localDrawable
        else -> null
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(shapeRadius)),
            contentAlignment = Alignment.Center,
        ) {
            // High performance fallback underneath
            FallbackPlaceholder(appName)

            if (modelToLoad != null) {
                val request = remember(modelToLoad, localDrawable) {
                    ImageRequest.Builder(context)
                        .data(modelToLoad)
                        .crossfade(true)
                        .listener(
                            onError = { _, result ->
                                if (effectiveIconUrl != iconUrl && com.ntt.crosstest.util.IconRoutingHelper.shouldFallbackToOrigin(effectiveIconUrl, result.throwable)) {
                                    setEffectiveIconUrl(iconUrl)
                                }
                            }
                        )
                        .apply {
                            if (localDrawable != null) {
                                error(localDrawable)
                                fallback(localDrawable)
                            }
                        }
                        .build()
                }

                AsyncImage(
                    model = request,
                    contentDescription = appName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        if (showProductionBadge) {
            val badgeSize = if (size <= 48.dp) 16.dp else 18.dp
            val checkSize = if (size <= 48.dp) 10.dp else 12.dp
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp)
                    .size(badgeSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.statusColors.success)
                    .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .semantics {
                        if (!productionBadgeContentDescription.isNullOrBlank()) {
                            contentDescription = productionBadgeContentDescription
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(checkSize),
                )
            }
        }
    }
}

@Composable
private fun FallbackPlaceholder(appName: String) {
    val letter = appName.trim().firstOrNull()?.uppercaseChar()?.toString()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (!letter.isNullOrBlank() && letter.all { it.isLetterOrDigit() }) {
            Text(
                text = letter,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 20.sp,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
