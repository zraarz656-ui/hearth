package com.tradequest.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tradequest.app.R
import com.tradequest.app.core.replay.ReplayNotifier
import com.tradequest.app.core.replay.TradeNotification

/**
 * Delivers trade notifications through the Android notification framework
 * (Step 7.9).
 *
 * # Why notifications do not break the offline promise
 *
 * `AGENTS.md` and the manifest state that TradeQuest is fully offline and
 * declares no `INTERNET` permission. A notification is a local IPC to the system
 * notification service; it needs `POST_NOTIFICATIONS` on API 33+, not network
 * access. The manifest stays free of `INTERNET`.
 *
 * # Why the permission is checked rather than assumed
 *
 * From API 33 the user can deny `POST_NOTIFICATIONS`, and a denial is silent:
 * posting without the permission throws nothing and shows nothing. Checking
 * first turns a silent no-op into an explicit decision, and the caller keeps the
 * trade in its own history regardless, so nothing is lost when notifications are
 * refused.
 */
class AndroidReplayNotifier(
    private val context: Context,
) : ReplayNotifier {

    // Lint's flow analysis does not follow the permission check into `canPost()`,
    // so it cannot see that `notify` returns early when POST_NOTIFICATIONS is
    // missing. The check is real and is the first thing the method does; the
    // suppression records that rather than hiding a missing check.
    @SuppressLint("MissingPermission")
    override suspend fun notify(notification: TradeNotification) {
        if (!canPost()) return

        ensureChannel()
        val id = notificationId(notification)
        val built = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(id, built)
    }

    private fun canPost(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_trades),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_trades_description)
            },
        )
    }

    /**
     * A stable id per event.
     *
     * Derived from the season and the simulated instant so that re-delivering the
     * same event — which must not happen, but would be worse as a duplicate
     * notification than as a replacement — overwrites rather than stacks. The kind
     * is folded in so two events at the same instant stay distinct.
     */
    private fun notificationId(notification: TradeNotification): Int {
        var hash = notification.seasonId.hashCode()
        hash = 31 * hash + notification.atSimUtcMs.hashCode()
        hash = 31 * hash + notification.kind.ordinal
        return hash and Int.MAX_VALUE
    }

    private companion object {
        const val CHANNEL_ID = "tradequest.trades"
    }
}
