package com.grinch.rivo4.controller

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.grinch.rivo4.MainActivity
import com.grinch.rivo4.R
import com.grinch.rivo4.modal.`interface`.IContactsRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Posts the missed call notification, on behalf of the platform.
 *
 * Telecom only leaves missed call notifications to the default dialer when that
 * dialer declares a receiver for
 * [TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION]. The check is a plain
 * manifest lookup, so without this entry the system posts its own notification
 * regardless of what the app does, and the user sees two.
 *
 * Taking the broadcast also covers what listening to disconnected calls cannot:
 * calls missed while the phone was off are re-announced here after boot.
 */
class MissedCallReceiver : BroadcastReceiver(), KoinComponent {

    private val contactsRepository: IContactsRepository by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISSED) {
            clearMissedCalls(context)
            return
        }
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        if (intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0) <= 0) return

        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER) ?: ""
        val accountHandle = IntentCompat.getParcelableExtra(
            intent, TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, PhoneAccountHandle::class.java
        )

        // Looking up the contact and decoding its photo both hit disk, which a
        // receiver must not do on the main thread.
        val pendingResult = goAsync()
        Thread {
            try {
                notify(context, number, accountHandle)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    /**
     * Tells Telecom the missed calls were seen, which also marks them read in
     * the call log and is what keeps them from being announced again after a
     * reboot.
     *
     * Telecom grants this to the default dialer, so the permission the
     * annotation asks for is not one this app needs to hold.
     */
    @SuppressLint("MissingPermission")
    private fun clearMissedCalls(context: Context) {
        try {
            val telecomManager =
                context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.cancelMissedCallsNotification()
        } catch (e: Exception) {
            // Not the default dialer any more, nothing to clear.
        }
    }

    private fun notify(
        context: Context,
        number: String,
        accountHandle: PhoneAccountHandle?,
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                MISSED_CHANNEL_ID,
                context.getString(R.string.notif_channel_missed_calls),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val contact = if (number.isNotEmpty()) {
            try {
                contactsRepository.getContactByNumber(number)
            } catch (e: Exception) { null }
        } else null

        val contactName = contact?.name
            ?: number.ifEmpty { context.getString(R.string.label_unknown_number) }
        val contactPhoto = getContactBitmap(context, contact?.photoUri)

        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val simLabel = accountHandle?.let {
            try { telecomManager.getPhoneAccount(it)?.label?.toString() } catch (e: SecurityException) { null }
        }

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = "com.grinch.rivo4.ACTION_VIEW_RECENTS"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 10, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val timeString = android.text.format.DateFormat.getTimeFormat(context)
            .format(java.util.Date())

        val missedCallText = buildString {
            append(context.getString(R.string.notif_missed_call_text, contactName, timeString))
            if (simLabel != null) {
                append(" ")
                append(context.getString(R.string.notif_via_sim, simLabel))
            }
        }

        // Swiping the notification away counts as having seen the calls.
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context, 11,
            Intent(context, MissedCallReceiver::class.java).setAction(ACTION_DISMISSED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, MISSED_CHANNEL_ID)
            .setDeleteIntent(dismissPendingIntent)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle(context.getString(R.string.notif_missed_call_title))
            .setContentText(missedCallText)
            .setLargeIcon(contactPhoto)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(Color.RED)

        notificationManager.notify(number.hashCode(), builder.build())
    }

    private fun getContactBitmap(context: Context, photoUri: String?): Bitmap? {
        if (photoUri == null) return null
        return try {
            val uri = Uri.parse(photoUri)
            val inputStream = context.contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        // Same value the service used, so the user's existing channel settings
        // carry over instead of a second channel appearing.
        private const val MISSED_CHANNEL_ID = "missed_call_channel"

        /** Delivered to this receiver by name, so it needs no intent filter. */
        private const val ACTION_DISMISSED = "com.grinch.rivo4.ACTION_MISSED_CALLS_DISMISSED"
    }
}
