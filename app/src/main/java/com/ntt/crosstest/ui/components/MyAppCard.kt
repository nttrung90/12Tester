package com.ntt.crosstest.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntt.crosstest.R
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignOwnerStats
import com.ntt.crosstest.ui.theme.statusColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyAppCard(
    campaign: Campaign,
    stats: CampaignOwnerStats? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onTipClick: (() -> Unit)? = null,
) {
    val statusColors = MaterialTheme.statusColors
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Header: App icon, Title, Package name, Chevron arrow
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(
                    iconUrl = campaign.safeIconUrl,
                    packageName = campaign.packageName,
                    appName = campaign.appName,
                    size = 44.dp,
                    shapeRadius = 10.dp,
                )

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = campaign.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = campaign.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(8.dp))

            // Badges row matching Image 1
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 0. Slot number pill
                val slotLabel = if (campaign.slotNumber == 1) {
                    stringResource(R.string.my_app_slot_default)
                } else {
                    stringResource(R.string.slot_number_title, campaign.slotNumber)
                }
                StatPill(
                    icon = Icons.Filled.Apps,
                    text = slotLabel,
                    iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
                    borderColor = MaterialTheme.colorScheme.primaryContainer,
                    textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                )

                // 1. Status pill
                val (statusText, statusBackground, statusContent) = when {
                    campaign.isProduction -> Triple(
                        stringResource(R.string.my_app_status_production),
                        statusColors.infoContainer,
                        statusColors.onInfoContainer,
                    )
                    campaign.isCompleted -> Triple(
                        stringResource(R.string.my_app_status_completed),
                        statusColors.infoContainer,
                        statusColors.onInfoContainer,
                    )
                    campaign.isPaused -> Triple(
                        stringResource(R.string.my_app_status_paused),
                        statusColors.warningContainer,
                        statusColors.onWarningContainer,
                    )
                    else -> Triple(
                        stringResource(R.string.my_app_status_testing),
                        statusColors.successContainer,
                        statusColors.onSuccessContainer,
                    )
                }
                StatPill(
                    text = statusText,
                    borderColor = statusBackground,
                    textColor = statusContent,
                    backgroundColor = statusBackground,
                )

                // 1b. Days remaining in the 30-day window (open apps only)
                if (campaign.isOpen) {
                    campaign.testingDaysRemaining?.let { days ->
                        StatPill(
                            icon = Icons.Filled.CalendarMonth,
                            text = stringResource(R.string.slot_active_days, days),
                            iconTint = statusColors.onInfoContainer,
                            borderColor = statusColors.infoContainer,
                            textColor = statusColors.onInfoContainer,
                            backgroundColor = statusColors.infoContainer,
                        )
                    }
                }

                // 2. Installed count
                val installedCount = stats?.installedTestersCount ?: campaign.targetTesters
                StatPill(
                    icon = Icons.Filled.Check,
                    text = stringResource(R.string.my_app_installed_count, installedCount),
                )

                // 3. Tested today
                val testedToday = stats?.testedTodayCount ?: 0
                StatPill(
                    icon = Icons.Filled.CalendarMonth,
                    text = stringResource(R.string.my_app_tested_today_count, testedToday),
                )

                // 4. Pending reviews
                val pending = stats?.pendingReportsCount ?: 0
                PendingReviewPill(count = pending)

                // 5. Tip more (Boa thêm) - tạm ẩn chưa dùng đến theo yêu cầu

                // 6. Tester badge
                StatPill(
                    icon = Icons.Filled.Shield,
                    text = stringResource(R.string.my_app_tester_badge),
                    iconTint = statusColors.onInfoContainer,
                    borderColor = statusColors.infoContainer,
                    textColor = statusColors.onInfoContainer,
                    backgroundColor = statusColors.infoContainer,
                )
            }
        }
    }
}

@Composable
fun StatPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = iconTint,
            )
        }
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
fun PendingReviewPill(count: Int) {
    val statusColors = MaterialTheme.statusColors
    val background = if (count > 0) statusColors.warningContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val content = if (count > 0) statusColors.onWarningContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (count > 0) background else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (count > 0) {
            Text(
                text = "$count",
                color = content,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = content,
            )
        }
        Text(
            text = stringResource(R.string.my_app_pending_review),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = content,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
