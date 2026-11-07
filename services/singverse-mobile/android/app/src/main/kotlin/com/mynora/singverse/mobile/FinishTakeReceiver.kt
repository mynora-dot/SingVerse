package com.mynora.singverse.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Only this app and its immutable notification PendingIntent can reach this receiver. */
class FinishTakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PlaybackService.current?.finishRecording("从通知栏结束录唱")
    }
}
