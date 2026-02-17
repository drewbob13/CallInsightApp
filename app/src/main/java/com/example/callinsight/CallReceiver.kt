package com.example.callinsight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            // Android sends these; never rely on ACTION_PHONE_STATE_CHANGED constant
            val action = intent.action ?: ""
            if (action != "android.intent.action.PHONE_STATE" &&
                action != "android.telephony.action.PHONE_STATE_CHANGED"
            ) {
                return
            }

            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: "null"
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            Debug.log("Receiver fired. action=$action state=$state number=$incomingNumber")
            Debug.notify(context, "CallInsight RECEIVER", "state=$state\nnumber=$incomingNumber\naction=$action")

            val svc = Intent(context, OverlayService::class.java).apply {
                putExtra(OverlayService.EXTRA_STATE, state)
                putExtra(OverlayService.EXTRA_NUMBER, incomingNumber)
            }

            // Start service safely (Android 8+ requires startForegroundService)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }

            // Track call end -> show post-call activity
            CallStateTracker.onState(state) { shouldShowPostCall ->
                if (shouldShowPostCall) {
                    Debug.notify(context, "CallInsight POST-CALL", "Launching PostCallActivity")
                    val post = Intent(context, PostCallActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(
                            PostCallActivity.EXTRA_NUMBER,
                            CallStateTracker.lastNumber ?: incomingNumber
                        )
                    }
                    context.startActivity(post)
                }
            }

            if (!incomingNumber.isNullOrBlank()) {
                CallStateTracker.lastNumber = incomingNumber
            }

        } catch (t: Throwable) {
            Debug.log("Receiver crashed", t)
            Debug.notify(context, "CallInsight RECEIVER CRASH", t.toString())
        }
    }
}

/**
 * Tracks transitions so we can detect "call ended".
 */
object CallStateTracker {
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
