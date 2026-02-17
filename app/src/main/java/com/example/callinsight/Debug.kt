package com.example.callinsight

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

object Debug {
    private const val TAG = "CallInsight"
    private const val CHANNEL_ID = "callinsight_debug"
    private const val NOTIF_ID = 4242

    fun log(msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, msg, t) else Log.d(TAG, msg)
    }

    fun notify(context: Context, title: String, text: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "CallInsight Debug",
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(ch)
            }

            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()

            nm.notify(NOTIF_ID, n)
        } catch (e: Throwable) {
            log("Debug.notify failed", e)
        }
    }
}
