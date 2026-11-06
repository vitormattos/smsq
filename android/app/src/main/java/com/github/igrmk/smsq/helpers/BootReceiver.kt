package com.github.igrmk.smsq.helpers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.github.igrmk.smsq.services.ReplyPollingService
import com.github.igrmk.smsq.services.ResenderService

class BootReceiver : BroadcastReceiver() {
    private val tag = this::class.simpleName!!

    override fun onReceive(context: Context, intent: Intent) {
        context.linf(tag, "action received: ${intent.action}, on: ${context.myPreferences.on}")
        if (!context.myPreferences.on) {
            return
        }
        if (intent.action != "android.intent.action.BOOT_COMPLETED" && intent.action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }
        context.startService(Intent(context, ResenderService::class.java))
        if (context.myPreferences.replyEnabled) {
            ContextCompat.startForegroundService(context, Intent(context, ReplyPollingService::class.java))
        }
    }
}
