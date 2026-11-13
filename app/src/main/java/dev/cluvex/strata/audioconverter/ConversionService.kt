package dev.cluvex.strata.audioconverter

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/** Runs the ffmpeg conversion as a foreground service, so it goes on after Home or Back and shows a notification. */
class ConversionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val lock = Any()
    private var active = false
    private var lastPercent = -1
    private var lastNotifyAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive == true) return START_NOT_STICKY
        Notifications.ensureChannels(this)
        val taken = ConversionHub.takeRequest()
        // startForegroundService() obliges us to call startForeground(), even if there turns out to be nothing to do.
        val failure = startInForeground(Notifications.progress(this, taken?.second?.fileName.orEmpty(), 0f))
        if (failure != null || taken == null) {
            // Never leave the service half started: Android would kill the app if startForeground() did not succeed.
            if (taken != null) ConversionHub.finish(ConversionHub.State.Failed(taken.first, failure ?: "Encoding failed."))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val (run, request) = taken
        synchronized(lock) { active = true }
        job = scope.launch {
            var outcome: ConvertResult? = null
            try {
                if (!ConversionHub.cancelRequested) {
                    outcome = Engine.convert(applicationContext, request.input, request.fileName, request.info, request.options) { p ->
                        onProgress(run, request.fileName, p)
                    }
                }
            } catch (t: Throwable) {
                // A cancellation leaves the outcome empty; anything else is a failure.
                if (!Engine.isCancel(t)) outcome = ConvertResult.Failed(t.message ?: "Encoding failed.")
            } finally {
                finish(run, outcome, startId)
            }
        }
        ConversionHub.cancelHook = { job?.cancel() }
        if (ConversionHub.cancelRequested) job?.cancel()
        return START_NOT_STICKY
    }

    /** Returns null on success, or the reason it failed. */
    private fun startInForeground(n: Notification): String? = try {
        // dataSync exists on every version that has types at all and is declared in the manifest.
        if (Build.VERSION.SDK_INT >= 29) startForeground(Notifications.PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(Notifications.PROGRESS_ID, n)
        null
    } catch (t: Throwable) {
        "Could not start the conversion service: ${t.message ?: t.javaClass.simpleName}"
    }

    private fun onProgress(run: Long, name: String, p: Float) {
        ConversionHub.progress(run, p)
        val pct = (p * 100).toInt()
        val now = SystemClock.elapsedRealtime()
        // Under the lock so that a late callback can never post the notification again after finish() removed it.
        synchronized(lock) {
            if (!active || pct == lastPercent || now - lastNotifyAt < 400) return
            lastPercent = pct
            lastNotifyAt = now
            Notifications.notifyProgress(this, name, p)
        }
    }

    private fun finish(run: Long, outcome: ConvertResult?, startId: Int) {
        synchronized(lock) { active = false }
        ConversionHub.cancelHook = null
        // With the screen on display it hands the result to Strata itself. Otherwise the file is saved in
        // Music/AudioConverter right away; if that fails the result waits behind the notification as before.
        val saved = if (outcome is ConvertResult.Done && !ConversionHub.visible && !ConversionHub.consumed) {
            runCatching { ResultStore.saveToMusic(applicationContext, File(File(cacheDir, "results"), outcome.name)) }.getOrNull()
        } else {
            null
        }
        ConversionHub.finish(
            when {
                saved != null -> ConversionHub.State.Saved(run, saved.uri, saved.name)
                outcome is ConvertResult.Done -> ConversionHub.State.Done(run, outcome)
                outcome is ConvertResult.Failed -> ConversionHub.State.Failed(run, outcome.message)
                else -> ConversionHub.State.Cancelled(run)
            },
        )
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (saved != null) {
            Notifications.postSaved(this, saved.name, saved.uri)
            ConversionHub.releaseHost()
        } else if (!ConversionHub.visible && !ConversionHub.consumed) {
            when (outcome) {
                is ConvertResult.Done -> {
                    ResultStore.forget(this)
                    Notifications.postDone(this, outcome.name)
                }
                is ConvertResult.Failed -> Notifications.postFailed(this, outcome.message)
                null -> Unit
            }
        }
        stopSelf(startId)
    }

    /** Android 15 stops long-running foreground services of these types after a few hours. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ConversionHub.cancel()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
