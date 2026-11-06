package com.github.igrmk.smsq.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.github.igrmk.smsq.Constants
import com.github.igrmk.smsq.R
import com.github.igrmk.smsq.activities.WelcomeActivity
import com.github.igrmk.smsq.entities.PendingReply
import com.github.igrmk.smsq.helpers.*
import com.google.crypto.tink.HybridEncrypt

/**
 * A user-enabled foreground service is necessary to check for replies when
 * Telegram is open but the SMS phone is not. Doze or network restrictions can
 * still delay polling. The backend claims each command only once.
 */
class ReplyPollingService : Service() {
    private val tag = this::class.simpleName!!
    private val worker = HandlerThread("smsq-telegram-replies")
    private lateinit var handler: Handler
    private lateinit var rest: RestTalker
    private val pollRunnable = Runnable { poll() }

    companion object {
        private const val CHANNEL = "smsq_sms_replies"
        private const val NOTIFICATION_ID = 74
        private const val POLL_INTERVAL_MS = 60000L
        private val SMS_NUMBER = Regex("^\\+?[0-9]{8,15}$")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        showForegroundNotification()
        worker.start()
        handler = Handler(worker.looper)
        rest = RestTalker(
            logger(), myPreferences.domainName,
            Constants.PUBLIC_KEY.getPrimitive(HybridEncrypt::class.java)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canPoll()) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        return START_STICKY
    }

    private fun canPoll(): Boolean =
        myPreferences.on &&
        myPreferences.replyEnabled &&
        myPreferences.key != null &&
        myPreferences.domainName.startsWith("https://", ignoreCase = true) &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun showForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "smsQ SMS replies", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val intent = Intent(this, WelcomeActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val pending = PendingIntent.getActivity(this, 0, intent, flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        startForeground(
            NOTIFICATION_ID,
            builder.setContentTitle("smsQ: Telegram SMS replies")
                .setContentText("Checking for replies to forwarded SMS")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
        )
    }

    private fun poll() {
        if (!canPoll()) {
            stopSelf()
            return
        }
        try {
            val key = myPreferences.key ?: return
            flushAcknowledgements(key)
            val messages = rest.pollReplies(key)
            if (messages != null) {
                for (command in messages) {
                    // No automatic SMS retry: a reply claimed by the backend
                    // cannot be fetched a second time.
                    val accepted = submitSMS(command, key)
                    rememberAcknowledgement(command.id, accepted)
                }
                flushAcknowledgements(key)
            }
        } catch (ex: Exception) {
            lerr(tag, "cannot process SMS reply: " + ex.javaClass.simpleName)
        }
        if (canPoll()) {
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        }
    }

    private fun submitSMS(command: PendingReply, expectedKey: String): Boolean {
        // Revocation or disabling replies must take effect even if the
        // backend response arrived while the user was changing settings.
        if (!canPoll() || myPreferences.key != expectedKey) {
            return false
        }
        if (!SMS_NUMBER.matches(command.recipient) ||
            command.text.isBlank() || command.text.toByteArray(Charsets.UTF_8).size > 1600 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return try {
            val sms = SmsManager.getDefault()
            val parts = sms.divideMessage(command.text)
            if (parts.size == 1) {
                sms.sendTextMessage(command.recipient, null, command.text, null, null)
            } else {
                sms.sendMultipartTextMessage(command.recipient, null, parts, null, null)
            }
            true // Android accepted the submission; not proof of carrier delivery.
        } catch (ex: Exception) {
            lerr(tag, "SMS submission failed: " + ex.javaClass.simpleName)
            false
        }
    }

    private fun rememberAcknowledgement(id: Long, success: Boolean) {
        val pending = (myPreferences.getStringSet(Constants.PREF_REPLY_ACKS, emptySet<String>())
            ?: emptySet()).toMutableSet()
        pending.add(id.toString() + ":" + (if (success) "1" else "0"))
        myPreferences.edit().putStringSet(Constants.PREF_REPLY_ACKS, pending).commit()
    }

    private fun flushAcknowledgements(key: String) {
        val pending = (myPreferences.getStringSet(Constants.PREF_REPLY_ACKS, emptySet<String>())
            ?: emptySet()).toMutableSet()
        for (entry in pending.toList()) {
            val parts = entry.split(":")
            val id = parts.getOrNull(0)?.toLongOrNull()
            if (id == null || parts.size != 2) {
                pending.remove(entry)
            } else if (rest.ackReply(key, id, parts[1] == "1")) {
                pending.remove(entry)
            }
        }
        myPreferences.edit().putStringSet(Constants.PREF_REPLY_ACKS, pending).commit()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        worker.quitSafely()
        super.onDestroy()
    }
}
