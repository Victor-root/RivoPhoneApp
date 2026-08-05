package com.grinch.rivo4.controller

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager

/**
 * Runs the notification's call back and message actions, then gets out of the
 * way.
 *
 * A notification action does not dismiss its notification: setAutoCancel only
 * covers a tap on the notification body. Something of ours has to run to clear
 * it, and since Android 12 a broadcast receiver started from a notification is
 * not allowed to launch an activity, so that something has to be an activity
 * itself. It shows nothing and finishes in onCreate.
 */
class MissedCallActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        dismiss(number)

        val forwarded = when (intent.getStringExtra(EXTRA_ACTION)) {
            ACTION_CALL_BACK -> Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            ACTION_MESSAGE -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))
            else -> null
        }
        if (forwarded != null && number.isNotEmpty()) {
            try {
                startActivity(forwarded)
            } catch (e: Exception) {
                // No app to take it, or the dialer role was lost. Nothing to
                // show for it either way, the notification is already gone.
            }
        }

        finish()
    }

    /**
     * Clears our own notification and tells Telecom the calls were seen, the
     * second of which marks them read in the call log so they are not announced
     * again after a reboot.
     */
    @SuppressLint("MissingPermission")
    private fun dismiss(number: String) {
        try {
            getSystemService(NotificationManager::class.java)?.cancel(number.hashCode())
        } catch (e: Exception) {
            // Nothing showing, nothing to do.
        }
        try {
            getSystemService(TelecomManager::class.java)?.cancelMissedCallsNotification()
        } catch (e: Exception) {
            // Granted to the default dialer only, which this may no longer be.
        }
    }

    companion object {
        const val EXTRA_ACTION = "com.grinch.rivo4.extra.MISSED_CALL_ACTION"
        const val EXTRA_NUMBER = "com.grinch.rivo4.extra.MISSED_CALL_NUMBER"

        const val ACTION_CALL_BACK = "call_back"
        const val ACTION_MESSAGE = "message"
    }
}
