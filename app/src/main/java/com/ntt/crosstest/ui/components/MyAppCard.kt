package com.ntt.crosstest.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.data.CampaignOwnerStats

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyAppCard(
    campaign: Campaign,
    stats: CampaignOwnerStats? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onTipClick: (() -> Unit)? = null,
) {
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
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: App icon, Title, Package name, Chevron arrow
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(
                    iconUrl = campaign.safeIconUrl,
                    packageName = campaign.packageName,
                    appName = campaign.appName,
                    size = 52.dp,
                )

                Spacer(Modifier.width(12.dp))

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

            Spacer(Modifier.height(14.dp))

            // Badges row matching Image 1
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 0. Slot number pill
                val slotLabel = if (campaign.slotNumber == 1) "Slot 1 (Mặc định)" else "Slot ${campaign.slotNumber}"
                StatPill(
                    icon = Icons.Filled.Apps,
                    text = slotLabel,
                    iconTint = MaterialTheme.colorScheme.primary,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    textColor = MaterialTheme.colorScheme.primary,
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                )

                // 1. Status pill
                val (statusText, statusColor) = when {
                    campaign.isProduction -> "🚀 Production" to Color(0xFF1565C0)
                    campaign.isPaused -> "⏸ Tạm dừng" to Color(0xFFE65100)
                    else -> "▶ Đang test" to Color(0xFF2E7D32)
                }
                StatPill(
                    text = statusText,
                    borderColor = statusColor,
                    textColor = statusColor,
                    backgroundColor = statusColor.copy(alpha = 0.08f),
                )

                // 2. Installed count
                val installedCount = stats?.installedTestersCount ?: campaign.targetTesters
                StatPill(
                    icon = Icons.Filled.Check,
                    text = "$installedCount đã cài",
                )

                // 3. Tested today
                val testedToday = stats?.testedTodayCount ?: 0
                StatPill(
                    icon = Icons.Filled.CalendarMonth,
                    text = "$testedToday test hôm nay",
                )

                // 4. Pending reviews
                val pending = stats?.pendingReportsCount ?: 0
                PendingReviewPill(count = pending)

                // 5. Tip more (Boa thêm) - tạm ẩn chưa dùng đến theo yêu cầu

                // 6. Tester badge
                StatPill(
                    icon = Icons.Filled.Shield,
                    text = "Nhân viên kiểm thử",
                    iconTint = Color(0xFF1976D2),
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
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 5.dp),
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
        )
    }
}

@Composable
fun PendingReviewPill(count: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (count > 0) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFD32F2F)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$count",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "chờ duyệt",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
