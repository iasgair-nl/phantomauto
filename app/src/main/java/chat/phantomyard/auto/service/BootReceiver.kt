package chat.phantomyard.auto.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import chat.phantomyard.auto.MainActivity
import chat.phantomyard.auto.R

private const val BOOT_PROMPT_CHANNEL_ID = "phantomauto_boot_prompt"
private const val BOOT_PROMPT_NOTIFICATION_ID = 2

/**
 * PhantomAutoService can't be auto-restarted directly from here: Android refused to start
 * this service from a BOOT_COMPLETED context when it was still a `dataSync`-type foreground
 * service (confirmed even when routed through an expedited WorkManager job - the restriction
 * tracked the app's background-start eligibility during the boot window, not the specific
 * call stack that triggered it). Not re-verified since switching to `remoteMessaging`. A
 * plain notification carries no such restriction either way, so this posts a tap-to-reconnect
 * prompt instead; tapping it opens MainActivity, which starts the service through a normal,
 * user-initiated launch.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // POST_NOTIFICATIONS is a runtime permission from Android 13 on; without it the
        // prompt is silently dropped anyway, so bail out early instead of notifying.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    BOOT_PROMPT_CHANNEL_ID,
                    context.getString(R.string.boot_prompt_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, BOOT_PROMPT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.boot_prompt_title))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(BOOT_PROMPT_NOTIFICATION_ID, notification)
    }
}
