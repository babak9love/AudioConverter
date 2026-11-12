package dev.cluvex.strata.audioconverter

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
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
    private var job: Job? = null

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
        if (state.converting || state.info == null) return
        _ui.update { it.copy(converting = true, progress = 0f, error = null) }
        job = viewModelScope.launch {
            try {
                when (val r = Engine.convert(getApplication(), file, state.fileName, state.info, state.options) { p -> _ui.update { it.copy(progress = p) } }) {
                    is ConvertResult.Done -> _ui.update { it.copy(converting = false, result = r) }
                    is ConvertResult.Failed -> _ui.update { it.copy(converting = false, error = r.message) }
                }
            } catch (t: Throwable) {
                _ui.update { it.copy(converting = false, error = if (Engine.isCancel(t)) null else t.message ?: "Encoding failed.") }
                if (Engine.isCancel(t)) throw t
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun resultConsumed() = _ui.update { it.copy(result = null) }
}
