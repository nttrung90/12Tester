package com.ntt.crosstest.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ntt.crosstest.R
import com.ntt.crosstest.data.UserNotification
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class NotificationFilter(@StringRes val labelRes: Int) {
    ALL(R.string.notif_filter_all),
    UNREAD(R.string.notif_filter_unread),
    ACCOUNT_CREDIT(R.string.notif_filter_account_credit),
    REPORTS(R.string.notif_filter_reports),
    ADMIN(R.string.notif_filter_admin),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationBottomSheet(
    notifications: List<UserNotification>,
    unreadCount: Int,
    onMarkAsRead: (String) -> Unit,
    onMarkAllAsRead: () -> Unit,
    onDeleteNotification: (String) -> Unit,
    onDismiss: () -> Unit,
    onNotificationClick: ((UserNotification) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedFilter by rememberSaveable { mutableStateOf(NotificationFilter.ALL) }

    val filteredList = remember(notifications, selectedFilter) {
        when (selectedFilter) {
            NotificationFilter.ALL -> notifications
            NotificationFilter.UNREAD -> notifications.filter { !it.isRead }
            NotificationFilter.ACCOUNT_CREDIT -> notifications.filter { it.isCredit || it.isAccount }
            NotificationFilter.REPORTS -> notifications.filter { it.isReport || it.isCampaign }
            NotificationFilter.ADMIN -> notifications.filter { it.isAdmin || it.isWarning }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .navigationBarsPadding(),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.notif_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (unreadCount > 0) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ) {
                            Text(
                                text = if (unreadCount > 99) "99+" else "$unreadCount",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (unreadCount > 0) {
                        TextButton(
                            onClick = onMarkAllAsRead,
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DoneAll,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.notif_mark_all_read), fontSize = 12.sp)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                    }
                }
            }

            // Filter Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NotificationFilter.values().forEach { filter ->
                    val isSelected = selectedFilter == filter
                    val count = when (filter) {
                        NotificationFilter.ALL -> notifications.size
                        NotificationFilter.UNREAD -> notifications.count { !it.isRead }
                        NotificationFilter.ACCOUNT_CREDIT -> notifications.count { it.isCredit || it.isAccount }
                        NotificationFilter.REPORTS -> notifications.count { it.isReport || it.isCampaign }
                        NotificationFilter.ADMIN -> notifications.count { it.isAdmin || it.isWarning }
                    }
                    val filterLabel = stringResource(filter.labelRes)
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = filter },
                        label = {
                            Text(
                                text = if (count > 0) "$filterLabel ($count)" else filterLabel,
                                fontSize = 12.sp,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // Notifications List / Empty State
            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.NotificationsNone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp),
                        )
                        Text(
                            text = if (selectedFilter == NotificationFilter.UNREAD) {
                                stringResource(R.string.notif_empty_read_all)
                            } else {
                                stringResource(R.string.notif_empty_none)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.notif_empty_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredList, key = { it.id }) { notif ->
                        NotificationItemCard(
                            notification = notif,
                            onClick = {
                                if (!notif.isRead) {
                                    onMarkAsRead(notif.id)
                                }
                                onNotificationClick?.invoke(notif)
                            },
                            onDelete = { onDeleteNotification(notif.id) },
                        )
                    }
                    item {
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationItemCard(
    notification: UserNotification,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val config = remember(notification.type) { resolveNotificationConfig(notification.type) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (notification.isRead) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
            }
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Category Icon Badge
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(config.badgeBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = config.icon,
                    contentDescription = null,
                    tint = config.iconTint,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            // Body
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = notification.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (notification.isRead) FontWeight.SemiBold else FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    Spacer(Modifier.width(6.dp))

                    Text(
                        text = formatRelativeTime(notification.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                    )
                }

                Text(
                    text = notification.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notification.isRead) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    lineHeight = 18.sp,
                )
            }

            // Unread dot & Delete button
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 4.dp),
            ) {
                if (!notification.isRead) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = stringResource(R.string.notif_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

private data class NotificationConfig(
    val icon: ImageVector,
    val iconTint: Color,
    val badgeBg: Color,
)

private fun resolveNotificationConfig(type: String): NotificationConfig {
    return when (type) {
        "credit" -> NotificationConfig(
            icon = Icons.Filled.MonetizationOn,
            iconTint = Color(0xFFF57F17),
            badgeBg = Color(0xFFFFF8E1),
        )
        "report" -> NotificationConfig(
            icon = Icons.AutoMirrored.Filled.Assignment,
            iconTint = Color(0xFF1976D2),
            badgeBg = Color(0xFFE3F2FD),
        )
        "warning" -> NotificationConfig(
            icon = Icons.Filled.Warning,
            iconTint = Color(0xFFD32F2F),
            badgeBg = Color(0xFFFFEBEE),
        )
        "account" -> NotificationConfig(
            icon = Icons.Filled.Lock,
            iconTint = Color(0xFF7B1FA2),
            badgeBg = Color(0xFFF3E5F5),
        )
        "campaign" -> NotificationConfig(
            icon = Icons.Filled.RocketLaunch,
            iconTint = Color(0xFF00897B),
            badgeBg = Color(0xFFE0F2F1),
        )
        "admin", "system" -> NotificationConfig(
            icon = Icons.Filled.Campaign,
            iconTint = Color(0xFFE65100),
            badgeBg = Color(0xFFFFF3E0),
        )
        else -> NotificationConfig(
            icon = Icons.Filled.Notifications,
            iconTint = Color(0xFF455A64),
            badgeBg = Color(0xFFECEFF1),
        )
    }
}

@Composable
private fun formatRelativeTime(isoString: String?): String {
    val context = LocalContext.current
    return remember(isoString, context) { formatRelativeTime(context, isoString) }
}

private fun formatRelativeTime(context: Context, isoString: String?): String {
    if (isoString.isNullOrBlank()) return ""
    return try {
        val instant = Instant.parse(isoString)
        val now = Instant.now()
        val duration = Duration.between(instant, now)
        val minutes = duration.toMinutes()
        val hours = duration.toHours()
        val days = duration.toDays()

        when {
            minutes < 1 -> context.getString(R.string.time_just_now)
            minutes < 60 -> context.getString(R.string.time_minutes_ago, minutes.toInt())
            hours < 24 -> context.getString(R.string.time_hours_ago, hours.toInt())
            days < 7 -> context.getString(R.string.time_days_ago, days.toInt())
            else -> {
                val zonedDateTime = instant.atZone(ZoneId.systemDefault())
                DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").format(zonedDateTime)
            }
        }
    } catch (_: Exception) {
        isoString.take(10)
    }
}
