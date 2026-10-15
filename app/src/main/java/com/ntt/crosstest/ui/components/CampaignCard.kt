package com.ntt.crosstest.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.ui.viewmodels.CampaignDisplayItem
import com.ntt.crosstest.util.InstalledAppCache

@Composable
fun CampaignCard(
    item: CampaignDisplayItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CampaignCard(
        campaign = item.campaign,
        ownerName = item.ownerName,
        installedCount = item.installedCount,
        todayTestedCount = item.todayTestedCount,
        hasTestedToday = item.hasTestedToday,
        onClick = onClick,
        modifier = modifier,
    )
}

@Composable
fun CampaignCard(
    campaign: Campaign,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ownerName: String = "",
    installedCount: Int = 0,
    todayTestedCount: Int = 0,
    hasTestedToday: Boolean = false,
) {
    val context = LocalContext.current
    val isInstalledOnDevice = remember(campaign.packageName) {
        InstalledAppCache.isInstalled(context, campaign.packageName)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // App Icon
            AppIcon(
                iconUrl = campaign.safeIconUrl,
                packageName = campaign.packageName,
                appName = campaign.appName,
                size = 52.dp,
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // App Name
                Text(
                    text = campaign.appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Owner Name
                Text(
                    text = ownerName.ifBlank { "Chủ app" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(4.dp))

                // Status & Stats Row: e.g. "⭳ Chưa cài   👥 13 hôm nay · 21 đã cài"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isInstalledOnDevice) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = if (hasTestedToday) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (hasTestedToday) "Đã cài · đã nộp" else "Đã cài · chưa nộp",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasTestedToday) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (hasTestedToday) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Chưa cài",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.width(4.dp))

                    val dailyTarget = campaign.dailyTarget
                    val isTargetReached = todayTestedCount >= dailyTarget

                    Icon(
                        imageVector = Icons.Filled.Groups,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                    Text(
                        text = "$todayTestedCount/$dailyTarget hôm nay · $installedCount đã cài",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isTargetReached) {
                        Text(
                            text = "· Đủ chỉ tiêu",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}
