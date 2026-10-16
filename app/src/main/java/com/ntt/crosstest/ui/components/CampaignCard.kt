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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ntt.crosstest.data.Campaign
import com.ntt.crosstest.ui.viewmodels.CampaignDisplayItem

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
        isInstalledLocally = item.isInstalledLocally,
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
    isInstalledLocally: Boolean? = null,
) {

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
                // App Name (+ lifecycle badge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = campaign.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val badge = when {
                        campaign.isProduction -> Triple("Lên production", Color(0xFFE8F5E9), Color(0xFF2E7D32))
                        campaign.isCompleted -> Triple("Hoàn thành KT", Color(0xFFE3F2FD), Color(0xFF1565C0))
                        campaign.isPaused -> Triple("Tạm dừng", Color(0xFFFFF8E1), Color(0xFFB26A00))
                        else -> null
                    }
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        StatusBadge(label = badge.first, background = badge.second, foreground = badge.third)
                    }
                }

                // Owner Name
                Text(
                    text = ownerName.ifBlank { "Chủ app" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(4.dp))

                // Row 1: trạng thái cài trên máy + trạng thái test hôm nay
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isInstalledLocally != null) {
                        if (isInstalledLocally) {
                            StatusBadge(label = "Đã cài", background = Color(0xFFE8F5E9), foreground = Color(0xFF2E7D32))
                        } else {
                            StatusBadge(label = "Chưa cài", background = Color(0xFFFFF8E1), foreground = Color(0xFFB26A00))
                        }
                    }

                    if (hasTestedToday) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = Color(0xFF2E7D32),
                        )
                        Text(
                            text = "Đã test hôm nay",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.FileDownload,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Chưa test hôm nay",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Row 2: thống kê chung của campaign
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val dailyTarget = campaign.dailyTarget
                    val isTargetReached = todayTestedCount >= dailyTarget

                    Icon(
                        imageVector = Icons.Filled.Groups,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                    Text(
                        text = buildString {
                            append("$todayTestedCount/$dailyTarget hôm nay · $installedCount người đã cài")
                            if (campaign.isOpen) {
                                campaign.testingDaysRemaining?.let { append(" · Còn $it ngày") }
                            }
                        },
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
