package com.example.p2pcodec2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.xxxlinkxxx.R

/**
 * Foreground service that keeps the active call alive when the user backgrounds the app
 * or locks the screen. Holds a partial wake lock so audio threads keep running,
 * and shows an ongoing notification with a tap-to-return action.
 *
 * MainActivity starts this service when a call becomes active and stops it on disconnect.
 */
class CallForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var endActionReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callerName = intent?.getStringExtra(EXTRA_CALLER_NAME) ?: "X-link call"
        // ALWAYS call startForeground first, even on stop path — Android 12+
        // throws ForegroundServiceDidNotStartInTimeException otherwise if the
        // service was started via startForegroundService().
        startForegroundWithNotification(callerName)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        registerEndReceiver()
        return START_STICKY
    }

    private fun startForegroundWithNotification(callerName: String) {
        createNotificationChannel()

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val openPi = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        val endIntent = Intent(ACTION_END_CALL).setPackage(packageName)
        val endPi = PendingIntent.getBroadcast(this, 1, endIntent, pendingFlags)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Call in progress")
            .setContentText(callerName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setContentIntent(openPi)
            .addAction(0, "End call", endPi)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active call",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification shown while a call is in progress"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xlink:callwakelock").apply {
            setReferenceCounted(false)
            acquire(60L * 60L * 1000L)  // safety upper bound: 1h
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun registerEndReceiver() {
        if (endActionReceiver != null) return
        endActionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Forward to MainActivity so the call teardown logic runs.
                val openIntent = Intent(this@CallForegroundService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                    action = ACTION_END_CALL
                }
                startActivity(openIntent)
                stopSelf()
            }
        }
        val filter = IntentFilter(ACTION_END_CALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(endActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(endActionReceiver, filter)
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        endActionReceiver?.let { runCatching { unregisterReceiver(it) } }
        endActionReceiver = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "xlink_active_call_v1"
        const val NOTIFICATION_ID = 8001
        const val ACTION_START = "com.example.p2pcodec2.action.CALL_START"
        const val ACTION_STOP = "com.example.p2pcodec2.action.CALL_STOP"
        const val ACTION_END_CALL = "com.example.p2pcodec2.action.CALL_END_FROM_NOTIFICATION"
        const val EXTRA_CALLER_NAME = "caller_name"

        fun start(context: Context, callerName: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CALLER_NAME, callerName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
