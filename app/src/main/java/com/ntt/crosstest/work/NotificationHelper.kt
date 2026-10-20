package com.ntt.crosstest.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ntt.crosstest.MainActivity
import com.ntt.crosstest.R
import com.ntt.crosstest.data.SettingsRepository

object NotificationHelper {

    private const val CHANNEL_ID = "checkin_reminder"
    private const val NOTIFICATION_ID = 1001

    private const val UPDATE_CHANNEL_ID = "app_update"
    private const val UPDATE_NOTIFICATION_ID = 1002

    fun showCheckinReminder(context: Context, campaignNames: List<String>) {
        // Wrap with the in-app language so notifications match the UI language
        // instead of the system language.
        val context = SettingsRepository.wrap(context)
        ensureChannel(
            context,
            channelId = CHANNEL_ID,
            nameRes = R.string.notif_channel_name,
            descriptionRes = R.string.notif_channel_desc,
        )

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val shown = campaignNames.take(3).joinToString(", ")
        val more = if (campaignNames.size > 3) {
            context.getString(R.string.notif_more_campaigns, campaignNames.size - 3)
        } else ""
        val appName = context.getString(R.string.app_name)
        val targetText = "$shown$more"
        val bigText = context.getString(R.string.notif_reminder_big_text, appName, targetText)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_reminder_title))
            .setContentText(context.getString(R.string.notif_reminder_text, targetText))
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Notifies the user that a newer app version is available on the store.
     * Tapping the notification opens the app (MainActivity), where the update
     * dialog / force-update screen takes over.
     */
    fun showUpdateAvailable(context: Context, versionName: String) {
        // Wrap with the in-app language so the notification matches the UI language.
        val context = SettingsRepository.wrap(context)
        ensureChannel(
            context,
            channelId = UPDATE_CHANNEL_ID,
            nameRes = R.string.notif_update_channel_name,
            descriptionRes = R.string.notif_update_channel_desc,
        )

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_update_title))
            .setContentText(context.getString(R.string.notif_update_text, versionName))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(UPDATE_NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(
        context: Context,
        channelId: String,
        nameRes: Int,
        descriptionRes: Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                context.getString(nameRes),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(descriptionRes)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
