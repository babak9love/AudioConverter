package dev.cluvex.strata.audioconverter

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide state shared by the screen ([ConverterActivity]), the foreground [ConversionService] and the
 * notification buttons. The conversion lives in the service, so it survives Home, Back and the activity being
 * recreated; whoever is on screen just observes [state].
 */
object ConversionHub {
    data class Request(val input: File, val fileName: String, val info: AudioInfo, val options: EncoderOptions)

    /** Every state belongs to one run, so a screen opened later never adopts the result of an earlier file. */
    sealed interface State {
        val run: Long

        data object Idle : State { override val run = 0L }
        data class Running(override val run: Long, val fileName: String, val progress: Float) : State
        data class Done(override val run: Long, val result: ConvertResult.Done) : State
        data class Failed(override val run: Long, val message: String) : State
        data class Cancelled(override val run: Long) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val runs = AtomicLong(0)
    @Volatile private var request: Request? = null

    @Volatile var cancelRequested = false
        private set
    @Volatile var cancelHook: (() -> Unit)? = null

    /** The converter screen is on display (between onStart and onStop). */
    @Volatile var visible = false

    /** The finished result was handed over (or opened) and its notification may go away for good. */
    @Volatile var consumed = false
        private set

    /** The app that started the screen for a result (Strata); needed to bring its task back from the notification. */
    @Volatile var callerPackage: String? = null

    private var host: WeakReference<Activity>? = null

    val isBusy get() = _state.value is State.Running

    fun nextRun(): Long = runs.incrementAndGet()

    fun begin(run: Long, r: Request) {
        request = r
        cancelRequested = false
        consumed = false
        _state.value = State.Running(run, r.fileName, 0f)
    }

    fun takeRequest(): Pair<Long, Request>? {
        val r = request ?: return null
        request = null
        val s = _state.value as? State.Running ?: return null
        return s.run to r
    }

    fun progress(run: Long, p: Float) = _state.update { if (it is State.Running && it.run == run) it.copy(progress = p) else it }

    fun finish(s: State) {
        _state.value = s
    }

    fun cancel() {
        cancelRequested = true
        cancelHook?.invoke()
    }

    fun markConsumed() {
        consumed = true
    }

    fun attach(activity: Activity, caller: String?) {
        host = WeakReference(activity)
        if (caller != null) callerPackage = caller
    }

    fun detach(activity: Activity) {
        if (host?.get() === activity) host = null
    }

    /** True while the activity that Strata is waiting on can still hand the result back. */
    fun hostAlive(): Boolean = host?.get()?.let { !it.isFinishing && !it.isDestroyed } == true
}
