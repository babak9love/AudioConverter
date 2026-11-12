package dev.cluvex.strata.audioconverter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels

/**
 * Strata plugin (API 1): receives the file as the Intent's content URI, shows the encoder options of
 * "Convert Audio/Video" from AUDIO_VIDEO_TOOLS (audio only), and returns the converted file as a content URI
 * together with RESULT_NAME and a MESSAGE report.
 */
class ConverterActivity : ComponentActivity() {
    private val vm: ConverterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val uri = intent?.data
        if (intent?.action != PLUGIN_ACTION || uri == null) {
            finish()
            return
        }
        val name = intent.getStringExtra(EXTRA_FILE_NAME)?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "audio"
        vm.start(uri, name)

        setContent {
            ConverterTheme {
                ConverterScreen(
                    vm = vm,
                    onDone = { r ->
                        setResult(
                            Activity.RESULT_OK,
                            Intent().setData(r.uri)
                                .putExtra(EXTRA_RESULT_NAME, r.name)
                                .putExtra(EXTRA_MESSAGE, r.report.take(MAX_MESSAGE))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                        vm.resultConsumed()
                        finish()
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    private companion object {
        const val PLUGIN_ACTION = "dev.cluvex.strata.plugin.action.FILE"
        const val EXTRA_FILE_NAME = "dev.cluvex.strata.plugin.extra.FILE_NAME"
        const val EXTRA_RESULT_NAME = "dev.cluvex.strata.plugin.extra.RESULT_NAME"
        const val EXTRA_MESSAGE = "dev.cluvex.strata.plugin.extra.MESSAGE"
        const val MAX_MESSAGE = 4_000
    }
}
