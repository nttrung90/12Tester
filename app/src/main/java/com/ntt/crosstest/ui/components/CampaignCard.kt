package com.ntt.crosstest.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Groups
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.ui.viewmodels.CampaignDisplayItem
import com.ntt.crosstest.ui.theme.statusColors

@Composable
fun CampaignCard(
    item: CampaignDisplayItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onUninstallClick: (() -> Unit)? = null,
    showTesterBonusBadge: Boolean = false,
) {
    CampaignCard(
        campaign = item.campaign,
        ownerName = item.ownerName,
        installedCount = item.installedCount,
        todayTestedCount = item.todayTestedCount,
        hasTestedToday = item.hasTestedToday,
        isInstalledLocally = item.isInstalledLocally,
        displayTestedCount = item.displayTestedCount,
        onClick = onClick,
        onUninstallClick = onUninstallClick,
        showTesterBonusBadge = showTesterBonusBadge,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CampaignCard(
    campaign: Campaign,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ownerName: String = "",
    installedCount: Int = 0,
    todayTestedCount: Int = 0,
    hasTestedToday: Boolean = false,
    isInstalledLocally: Boolean? = null,
    displayTestedCount: Int? = null,
    onUninstallClick: (() -> Unit)? = null,
    showTesterBonusBadge: Boolean = false,
) {
    val statusColors = MaterialTheme.statusColors
    val shouldShowBonus = showTesterBonusBadge &&
        campaign.isOpen &&
        campaign.testerBonusStatus == "active" &&
        (campaign.testerBonusCredits ?: 0) > 0 &&
        (campaign.testerBonusRemainingSlots ?: 0) > 0

    val bonusCredits = campaign.testerBonusCredits ?: 0
    val hasUninstall = isInstalledLocally == true && onUninstallClick != null

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = if (shouldShowBonus) Alignment.Top else Alignment.CenterVertically,
            ) {
                val showPlayProdBadge = campaign.isOpen && campaign.isPlayProductionDetected
                AppIcon(
                    iconUrl = campaign.safeIconUrl,
                    packageName = campaign.packageName,
                    appName = campaign.appName,
                    size = 44.dp,
                    shapeRadius = 10.dp,
                    showProductionBadge = showPlayProdBadge,
                    productionBadgeContentDescription = if (showPlayProdBadge) {
                        stringResource(R.string.play_production_badge_cd)
                    } else null,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = campaign.appName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Wrap individual labels on narrow windows and with larger fonts.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = ownerName.ifBlank { stringResource(R.string.campaign_owner_fallback) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        val badge = when {
                            campaign.isProduction -> Triple(
                                stringResource(R.string.campaign_badge_production),
                                statusColors.infoContainer,
                                statusColors.onInfoContainer,
                            )
                            campaign.isCompleted -> Triple(
                                stringResource(R.string.campaign_badge_completed),
                                statusColors.infoContainer,
                                statusColors.onInfoContainer,
                            )
                            campaign.isPaused -> Triple(
                                stringResource(R.string.campaign_badge_paused),
                                statusColors.warningContainer,
                                statusColors.onWarningContainer,
                            )
                            else -> null
                        }
                        if (badge != null) {
                            StatusBadge(label = badge.first, background = badge.second, foreground = badge.third)
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (isInstalledLocally != null) {
                            if (isInstalledLocally) {
                                StatusBadge(
                                    label = stringResource(R.string.campaign_badge_installed),
                                    background = statusColors.successContainer,
                                    foreground = statusColors.onSuccessContainer,
                                )
                            } else {
                                StatusBadge(
                                    label = stringResource(R.string.campaign_badge_not_installed),
                                    background = statusColors.warningContainer,
                                    foreground = statusColors.onWarningContainer,
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = if (hasTestedToday) Icons.Filled.Check else Icons.Filled.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = if (hasTestedToday) statusColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(
                                    if (hasTestedToday) R.string.campaign_tested_today else R.string.campaign_not_tested_today,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hasTestedToday) statusColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (hasTestedToday) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }

                if (shouldShowBonus) {
                    Spacer(Modifier.width(8.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        // Match the icon's 44dp row and keep the badge at the card's right edge.
                        Box(
                            modifier = Modifier.height(44.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            TesterBonusBadge(credits = bonusCredits)
                        }
                        if (hasUninstall) {
                            Spacer(Modifier.height(2.dp))
                            UninstallButton(onClick = onUninstallClick)
                        }
                    }
                } else if (hasUninstall) {
                    Spacer(Modifier.width(8.dp))
                    UninstallButton(onClick = onUninstallClick)
                }
            }

            // Full-width statistics avoid the narrow column beside the app icon.
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val dailyTarget = campaign.dailyTarget
                val isTargetReached = todayTestedCount >= dailyTarget

                Icon(
                    imageVector = Icons.Filled.Groups,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val statsText = buildString {
                    append(
                        stringResource(
                            R.string.campaign_stats_line_format,
                            displayTestedCount?.toString() ?: "--",
                            dailyTarget,
                            installedCount,
                        )
                    )
                    if (campaign.isOpen) {
                        campaign.testingDaysRemaining?.let {
                            append(stringResource(R.string.campaign_days_left_suffix, it))
                        }
                    }
                }
                val targetReachedText = if (isTargetReached) stringResource(R.string.campaign_target_reached) else ""
                Text(
                    text = buildAnnotatedString {
                        append(statsText)
                        if (isTargetReached) {
                            append(" ")
                            withStyle(SpanStyle(color = statusColors.success, fontWeight = FontWeight.Bold)) {
                                append(targetReachedText)
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun UninstallButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.DeleteOutline,
                contentDescription = stringResource(R.string.campaign_uninstall_btn),
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.campaign_uninstall_btn),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun StatusBadge(
    label: String,
    background: Color,
    foreground: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * Huy hiệu thưởng thêm cam kèm icon Credit, hiển thị ở góc phải của tên ứng dụng.
 * Tương phản cao trên cả theme Sáng và Tối, có ngữ nghĩa hỗ trợ TalkBack đầy đủ.
 */
@Composable
fun TesterBonusBadge(
    credits: Int,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    val isDark = (0.299f * surface.red + 0.587f * surface.green + 0.114f * surface.blue) < 0.5f
    val badgeBg = if (isDark) Color(0xFF422100) else Color(0xFFFFECE0)
    val badgeContent = if (isDark) Color(0xFFFFB74D) else Color(0xFFB54700)
    val borderStroke = BorderStroke(
        1.dp,
        if (isDark) Color(0xFFFFB74D).copy(alpha = 0.5f) else Color(0xFFB54700).copy(alpha = 0.4f),
    )
    val talkBack = stringResource(R.string.campaign_bonus_badge_talkback, credits)

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = badgeBg,
        border = borderStroke,
        modifier = modifier.semantics {
            contentDescription = talkBack
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.MonetizationOn,
                contentDescription = null,
                tint = badgeContent,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = "+$credits",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = badgeContent,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
