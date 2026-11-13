package dev.cluvex.strata.audioconverter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Buttons and swipe events of the conversion notifications. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Notifications.ACTION_CANCEL -> ConversionHub.cancel()
            // Swiped away by mistake: it comes straight back.
            Notifications.ACTION_REPOST_PROGRESS ->
                (ConversionHub.state.value as? ConversionHub.State.Running)
                    ?.let { Notifications.notifyProgress(context, it.fileName, it.progress) }
            Notifications.ACTION_REPOST_DONE ->
                if (!ConversionHub.consumed) Engine.latestResult(context)?.let { Notifications.postDone(context, it.name) }
        }
    }
}
