package com.example.callinsight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) // may be null on newer Android

        val serviceIntent = Intent(context, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_STATE, state)
            putExtra(OverlayService.EXTRA_NUMBER, incomingNumber)
        }

        // Use foreground service start for Android O+
        context.startForegroundService(serviceIntent)

        // Launch post-call screen when returning to IDLE after OFFHOOK.
        CallStateTracker.onState(state) { shouldShowPostCall ->
            if (shouldShowPostCall) {
                val post = Intent(context, PostCallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(PostCallActivity.EXTRA_NUMBER, CallStateTracker.lastNumber ?: incomingNumber)
                }
                context.startActivity(post)
            }
        }

        // Store latest number we saw (best-effort)
        if (!incomingNumber.isNullOrBlank()) {
            CallStateTracker.lastNumber = incomingNumber
        }
    }
}

/**
 * Tracks transitions so we can detect "call ended".
 * This is best-effort; Android call state delivery can vary by device/OS.
 */
private object CallStateTracker {
    private var lastState: String? = null
    private var wasOffhook = false
    var lastNumber: String? = null

    fun onState(newState: String, onEnded: (Boolean) -> Unit) {
        val prev = lastState
        lastState = newState

        when (newState) {
            TelephonyManager.EXTRA_STATE_OFFHOOK -> wasOffhook = true
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val ended = wasOffhook && prev != TelephonyManager.EXTRA_STATE_IDLE
                wasOffhook = false
                onEnded(ended)
                return
            }
        }
        onEnded(false)
    }
}
