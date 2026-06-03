package com.example.p2pcodec2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.xxxlinkxxx.R
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlin.math.absoluteValue

class XxxFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs = com.example.p2pcodec2.SecurePrefs.get(this)
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
        val localId = prefs.getString(KEY_LOCAL_ID, null)?.takeIf { it.isNotBlank() } ?: return
        FirebaseApp.initializeApp(this)
        FirebaseFirestore.getInstance().collection("users").document(localId).set(
            mapOf(
                "id" to localId,
                "fcmToken" to token,
                "fcmUpdatedAt" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        )
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        createNotificationChannels()
        when (message.data["type"]) {
            "call" -> showCallNotification(message.data)
            "call_cancel" -> cancelCallNotification()
            "message" -> showMessageNotification(message.data)
            else -> showFallbackNotification(message)
        }
    }

    private fun cancelCallNotification() {
        notificationManager().cancel(NOTIFICATION_CALL_ID)
    }

    private fun showCallNotification(data: Map<String, String>) {
        // Suppress FCM-driven call notif when app is in foreground — the Firestore
        // listener already shows the in-app incoming-call screen with its own
        // ringtone. Double-firing causes two overlapping sounds.
        if (isAppInForeground()) return
        val callerId = data["callerId"].orEmpty()
        val callerName = contactName(callerId)
        val intent = contentIntent()
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CALLS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Incoming call")
            .setContentText(callerName.ifBlank { "Unknown caller" })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setSound(notificationSound(R.raw.incoming_call))
            .setVibrate(longArrayOf(0L, 250L, 150L, 250L, 150L, 500L))
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setNumber(1)
            .setContentIntent(intent)
            .setFullScreenIntent(intent, true)
            .setAutoCancel(true)
            .setOngoing(true)
            .build()
        notificationManager().notify(NOTIFICATION_CALL_ID, notification)
    }

    private fun isAppInForeground(): Boolean {
        val prefs = com.example.p2pcodec2.SecurePrefs.get(this)
        return prefs.getBoolean(KEY_APP_IN_FOREGROUND, false)
    }

    private fun showMessageNotification(data: Map<String, String>) {
        if (isAppInForeground()) return
        val senderId = data["senderId"].orEmpty()
        val groupId = data["groupId"].orEmpty()
        val openChatId = groupId.ifBlank { senderId }
        val senderName = contactName(senderId).ifBlank { "New message" }
        val body = data["body"]?.takeIf { it.isNotBlank() } ?: "Encrypted message"
        val count = incrementUnreadCount()
        val notification = NotificationCompat.Builder(this, NOTIFICATION_MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(senderName)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setSound(notificationSound(R.raw.incoming_message))
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setNumber(count)
            .setContentIntent(contentIntent(openChatId))
            .setAutoCancel(true)
            .build()
        notificationManager().notify((NOTIFICATION_MESSAGE_ID_BASE + senderId.hashCode()).absoluteValue, notification)
    }

    private fun showFallbackNotification(message: RemoteMessage) {
        val title = message.notification?.title ?: "XxxLink"
        val body = message.notification?.body ?: "New activity"
        val count = incrementUnreadCount()
        val notification = NotificationCompat.Builder(this, NOTIFICATION_MESSAGES_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            .setSound(notificationSound(R.raw.incoming_message))
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setNumber(count)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        notificationManager().notify(NOTIFICATION_FALLBACK_ID, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Clean up dead v3 channels (cached broken sound URIs).
        runCatching {
            notificationManager().deleteNotificationChannel(LEGACY_CALLS_CHANNEL_ID)
            notificationManager().deleteNotificationChannel(LEGACY_MESSAGES_CHANNEL_ID)
        }
        val callSound = notificationSound(R.raw.incoming_call)
        val messageSound = notificationSound(R.raw.incoming_message)
        val callAudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val messageAudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val callsChannel = NotificationChannel(
            NOTIFICATION_CALLS_CHANNEL_ID,
            "Calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming call alerts"
            enableVibration(true)
            setSound(callSound, callAudioAttributes)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val messagesChannel = NotificationChannel(
            NOTIFICATION_MESSAGES_CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming message alerts"
            enableVibration(true)
            setSound(messageSound, messageAudioAttributes)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager().createNotificationChannels(listOf(callsChannel, messagesChannel))
    }

    private fun notificationSound(resId: Int): Uri =
        Uri.parse("android.resource://$packageName/$resId")

    private fun contentIntent(openChatId: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!openChatId.isNullOrBlank()) putExtra("openChatId", openChatId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        // Use openChatId as requestCode discriminator so multiple per-sender
        // PendingIntents stay independent (otherwise FLAG_UPDATE_CURRENT
        // overrides all to the latest sender).
        val requestCode = openChatId?.hashCode() ?: 0
        return PendingIntent.getActivity(this, requestCode, intent, flags)
    }

    private fun contactName(id: String): String {
        if (id.isBlank()) return ""
        val prefs = com.example.p2pcodec2.SecurePrefs.get(this)
        return prefs.getString("$KEY_CONTACT_PREFIX$id", null)?.takeIf { it.isNotBlank() } ?: id
    }

    private fun incrementUnreadCount(): Int {
        val prefs = com.example.p2pcodec2.SecurePrefs.get(this)
        val count = prefs.getInt(KEY_UNREAD_NOTIFICATION_COUNT, 0) + 1
        prefs.edit().putInt(KEY_UNREAD_NOTIFICATION_COUNT, count).apply()
        return count
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val PREFS_NAME = "xxxlink_prefs"
        private const val KEY_LOCAL_ID = "local_id"
        private const val KEY_CONTACT_PREFIX = "contact_name_"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_UNREAD_NOTIFICATION_COUNT = "unread_notification_count"
        // v4: see MainActivity for rationale (raw resource rename invalidated
        // the cached sound URI on v3 channels).
        private const val NOTIFICATION_CALLS_CHANNEL_ID = "xxxlink_calls_v4"
        private const val NOTIFICATION_MESSAGES_CHANNEL_ID = "xxxlink_messages_v4"
        private const val LEGACY_CALLS_CHANNEL_ID = "xxxlink_calls_v3"
        private const val LEGACY_MESSAGES_CHANNEL_ID = "xxxlink_messages_v3"
        private const val NOTIFICATION_CALL_ID = 5001
        private const val NOTIFICATION_MESSAGE_ID_BASE = 6000
        private const val NOTIFICATION_FALLBACK_ID = 7001
        private const val KEY_APP_IN_FOREGROUND = "app_in_foreground"
    }
}
