package com.example.callinsight

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.provider.Telephony
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class OverlayService : Service() {

    companion object {
        const val EXTRA_STATE = "state"
        const val EXTRA_NUMBER = "number"

        private const val CHANNEL_ID = "callinsight_overlay"
        private const val NOTIF_ID = 1001
    }

    private var wm: WindowManager? = null
    private var overlayView: View? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannelIfNeeded()
        startForeground(NOTIF_ID, buildNotification("CallInsight running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = intent?.getStringExtra(EXTRA_STATE)
        val number = intent?.getStringExtra(EXTRA_NUMBER)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> showOverlay(number)
            TelephonyManager.EXTRA_STATE_OFFHOOK -> updateOverlay(number) // optional; keeps it up
            TelephonyManager.EXTRA_STATE_IDLE -> hideOverlayAndStop()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay(number: String?) {
        if (!Settings.canDrawOverlays(this)) return

        if (overlayView == null) {
            overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null, false)

            val type =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                y = 0
            }

            wm?.addView(overlayView, params)
        }

        updateOverlay(number)
    }

    private fun updateOverlay(number: String?) {
        val v = overlayView ?: return

        val header = v.findViewById<TextView>(R.id.overlayHeader)
        val line1 = v.findViewById<TextView>(R.id.overlayLine1)
        val line2 = v.findViewById<TextView>(R.id.overlayLine2)
        val line3 = v.findViewById<TextView>(R.id.overlayLine3)

        val safeNumber = number?.takeIf { it.isNotBlank() } ?: "(unknown number)"
        header.text = "Recent with $safeNumber"

        val items = loadLast3CallsAndSmsForNumber(number)
        line1.text = items.getOrNull(0) ?: ""
        line2.text = items.getOrNull(1) ?: ""
        line3.text = items.getOrNull(2) ?: ""
    }

    private fun hideOverlayAndStop() {
        removeOverlay()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun removeOverlay() {
        overlayView?.let {
            try { wm?.removeView(it) } catch (_: Throwable) {}
        }
        overlayView = null
    }

    private fun loadLast3CallsAndSmsForNumber(number: String?): List<String> {
        // If we don't have a number, just show last 3 call log entries as a fallback.
        val out = mutableListOf<String>()

        val hasCallLog = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

        if (!hasCallLog && !hasSms) {
            return listOf("Missing permissions: READ_CALL_LOG / READ_SMS")
        }

        if (hasCallLog) {
            out += queryCalls(number, limit = 3)
        }
        if (hasSms && out.size < 3) {
            out += querySms(number, limit = 3 - out.size)
        }

        return out.take(3)
    }

    private fun queryCalls(number: String?, limit: Int): List<String> {
        val results = mutableListOf<String>()

        val uri = CallLog.Calls.CONTENT_URI
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        val selection: String?
        val selectionArgs: Array<String>?
        if (!number.isNullOrBlank()) {
            selection = "${CallLog.Calls.NUMBER} = ?"
            selectionArgs = arrayOf(number)
        } else {
            selection = null
            selectionArgs = null
        }

        val sortOrder = "${CallLog.Calls.DATE} DESC LIMIT $limit"

        val c: Cursor? = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        c?.use {
            val idxNum = it.getColumnIndex(CallLog.Calls.NUMBER)
            val idxType = it.getColumnIndex(CallLog.Calls.TYPE)
            val idxDur = it.getColumnIndex(CallLog.Calls.DURATION)

            while (it.moveToNext()) {
                val num = it.getString(idxNum)
                val type = it.getInt(idxType)
                val dur = it.getString(idxDur)

                val typeLabel = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "IN"
                    CallLog.Calls.OUTGOING_TYPE -> "OUT"
                    CallLog.Calls.MISSED_TYPE -> "MISSED"
                    else -> "CALL"
                }

                results += "Call $typeLabel • $num • ${dur}s"
            }
        }

        return results
    }

    private fun querySms(number: String?, limit: Int): List<String> {
        val results = mutableListOf<String>()

        // SMS “address” is the phone number.
        val uri: Uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.TYPE
        )

        val selection: String?
        val selectionArgs: Array<String>?
        if (!number.isNullOrBlank()) {
            selection = "${Telephony.Sms.ADDRESS} = ?"
            selectionArgs = arrayOf(number)
        } else {
            selection = null
            selectionArgs = null
        }

        val sortOrder = "${Telephony.Sms.DEFAULT_SORT_ORDER} LIMIT $limit"

        val c: Cursor? = contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        c?.use {
            val idxAddr = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val idxBody = it.getColumnIndex(Telephony.Sms.BODY)
            val idxType = it.getColumnIndex(Telephony.Sms.TYPE)

            while (it.moveToNext()) {
                val addr = it.getString(idxAddr)
                val body = it.getString(idxBody)?.replace("\n", " ") ?: ""
                val type = it.getInt(idxType)

                val dir = if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) "SMS IN" else "SMS OUT"
                results += "$dir • $addr • ${body.take(60)}"
            }
        }

        return results
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "CallInsight Overlay", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("CallInsightApp")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }
}
