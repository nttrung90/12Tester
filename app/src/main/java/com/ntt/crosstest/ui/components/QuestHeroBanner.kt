package com.ntt.crosstest.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ntt.crosstest.R
import com.ntt.crosstest.ui.theme.QuestBannerEndDark
import com.ntt.crosstest.ui.theme.QuestBannerEndLight
import com.ntt.crosstest.ui.theme.QuestBannerStartDark
import com.ntt.crosstest.ui.theme.QuestBannerStartLight
import com.ntt.crosstest.ui.theme.QuestClaimAmber
import com.ntt.crosstest.ui.theme.QuestClaimOnAmber
import com.ntt.crosstest.ui.theme.QuestTrophyGoldEnd
import com.ntt.crosstest.ui.theme.QuestTrophyGoldStart
import java.text.NumberFormat

@Composable
fun QuestHeroBanner(
    userCredits: Int,
    dailyCount: Int,
    weeklyCount: Int,
    totalClaimable: Int,
    onClaimableClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val gradientStart = if (isDark) QuestBannerStartDark else QuestBannerStartLight
    val gradientEnd = if (isDark) QuestBannerEndDark else QuestBannerEndLight
    val formattedCredits = remember(userCredits) {
        NumberFormat.getNumberInstance().format(userCredits)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(gradientStart, gradientEnd))),
    ) {
        // Decorative circles + star watermark
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 40.dp, y = (-44).dp)
                .size(130.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.12f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 14.dp, y = 16.dp)
                .size(54.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (-44).dp, y = 48.dp)
                .size(110.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f)),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
        ) {
            // Header: Trophy badge + Title + Subtitle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .shadow(6.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(QuestTrophyGoldStart, QuestTrophyGoldEnd),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.EmojiEvents,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.quest_section_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.quest_screen_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Stat chips: [Số dư] | [Hôm nay] | [Tuần này]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatChip(
                    icon = Icons.Filled.MonetizationOn,
                    value = formattedCredits,
                    label = stringResource(R.string.quest_stat_balance),
                    modifier = Modifier.weight(1f),
                )
                StatChip(
                    icon = Icons.Filled.Today,
                    value = pluralStringResource(
                        R.plurals.quest_stat_apps_plural,
                        dailyCount,
                        dailyCount,
                    ),
                    label = stringResource(R.string.quest_stat_today),
                    modifier = Modifier.weight(1f),
                )
                StatChip(
                    icon = Icons.Filled.DateRange,
                    value = pluralStringResource(
                        R.plurals.quest_stat_apps_plural,
                        weeklyCount,
                        weeklyCount,
                    ),
                    label = stringResource(R.string.quest_stat_week),
                    modifier = Modifier.weight(1f),
                )
            }

            if (totalClaimable > 0) {
                Spacer(Modifier.height(12.dp))
                ClaimPill(
                    totalClaimable = totalClaimable,
                    onClick = onClaimableClick,
                )
            }
        }
    }
}

@Composable
private fun StatChip(
    icon: ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ClaimPill(
    totalClaimable: Int,
    onClick: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "claimPulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "claimPulseScale",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
            }
            .clip(RoundedCornerShape(12.dp))
            .background(QuestClaimAmber)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CardGiftcard,
            contentDescription = null,
            tint = QuestClaimOnAmber,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.quest_ready_banner_format, totalClaimable),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = QuestClaimOnAmber,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = QuestClaimOnAmber,
            modifier = Modifier.size(18.dp),
        )
    }
}
