package dev.cluvex.strata.audioconverter

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ConverterUi(
    val fileName: String = "",
    val info: AudioInfo? = null,
    val loading: Boolean = true,
    val options: EncoderOptions = EncoderOptions(),
    val converting: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
    val result: ConvertResult.Done? = null,
)

class ConverterViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(ConverterUi())
    val ui: StateFlow<ConverterUi> = _ui.asStateFlow()

    private var input: File? = null
    private var started = false
    private var myRun = -1L

    init {
        // The conversion runs in ConversionService; this follows the run that this screen started.
        viewModelScope.launch {
            ConversionHub.state.collect { s ->
                if (s.run != myRun) return@collect
                _ui.update {
                    when (s) {
                        is ConversionHub.State.Running -> it.copy(converting = true, progress = s.progress, error = null)
                        is ConversionHub.State.Done -> it.copy(converting = false, progress = 1f, result = s.result)
                        is ConversionHub.State.Failed -> it.copy(converting = false, error = s.message)
                        is ConversionHub.State.Cancelled -> it.copy(converting = false)
                        ConversionHub.State.Idle -> it
                    }
                }
            }
        }
    }

    fun start(uri: Uri, fileName: String) {
        if (started) return
        started = true
        _ui.update { it.copy(fileName = fileName) }
        viewModelScope.launch {
            Engine.cleanUp(getApplication())
            try {
                val file = Engine.stageInput(getApplication(), uri, fileName)
                input = file
                val info = Engine.probe(file.absolutePath)
                _ui.update {
                    if (info == null) it.copy(loading = false, error = "This file could not be read.")
                    else it.copy(loading = false, info = info)
                }
            } catch (t: Throwable) {
                _ui.update { it.copy(loading = false, error = t.message ?: "This file could not be read.") }
            }
        }
    }

    fun update(change: (EncoderOptions) -> EncoderOptions) = _ui.update { it.copy(options = change(it.options), error = null) }

    fun convert() {
        val file = input ?: return
        val state = _ui.value
        val info = state.info ?: return
        if (state.converting) return
        val app = getApplication<Application>()
        if (ConversionHub.isBusy) {
            _ui.update { it.copy(error = app.getString(R.string.busy)) }
            return
        }
        // A new conversion replaces whatever result an earlier one left in the notification.
        Notifications.cancelAll(app)
        val run = ConversionHub.nextRun()
        myRun = run
        ConversionHub.begin(run, ConversionHub.Request(file, state.fileName, info, state.options))
        _ui.update { it.copy(converting = true, progress = 0f, error = null) }
        try {
            ContextCompat.startForegroundService(app, Intent(app, ConversionService::class.java))
        } catch (t: Throwable) {
            ConversionHub.finish(ConversionHub.State.Failed(run, t.message ?: "Encoding failed."))
        }
    }

    fun cancel() = ConversionHub.cancel()

    fun resultConsumed() = _ui.update { it.copy(result = null) }
}
