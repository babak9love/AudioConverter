package dev.cluvex.strata.audioconverter

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels

/**
 * Strata plugin (API 1): receives the file as the Intent's content URI, shows the encoder options of
 * "Convert Audio/Video" from AUDIO_VIDEO_TOOLS (audio only), and returns the converted file as a content URI
 * together with RESULT_NAME and a MESSAGE report.
 *
 * The conversion itself runs in [ConversionService]. Back or Home while it runs only hides this screen (it keeps
 * waiting for Strata in Strata's task); the notification's Open button brings it back and the result goes to Strata.
 */
class ConverterActivity : ComponentActivity() {
    private val vm: ConverterViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        val uri = intent?.data
        if (intent?.action != PLUGIN_ACTION || uri == null) {
            finish()
            return
        }
        ConversionHub.attach(this, callingPackage)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val name = intent.getStringExtra(EXTRA_FILE_NAME)?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "audio"
        vm.start(uri, name)

        setContent {
            ConverterTheme {
                ConverterScreen(
                    vm = vm,
                    onDone = { r ->
                        ConversionHub.markConsumed()
                        Notifications.cancelDone(this)
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
                    onBackground = { moveTaskToBack(true) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ConversionHub.visible = true
    }

    override fun onStop() {
        ConversionHub.visible = false
        super.onStop()
    }

    override fun onDestroy() {
        ConversionHub.detach(this)
        super.onDestroy()
    }

    private companion object {
        const val PLUGIN_ACTION = "dev.cluvex.strata.plugin.action.FILE"
        const val EXTRA_FILE_NAME = "dev.cluvex.strata.plugin.extra.FILE_NAME"
        const val EXTRA_RESULT_NAME = "dev.cluvex.strata.plugin.extra.RESULT_NAME"
        const val EXTRA_MESSAGE = "dev.cluvex.strata.plugin.extra.MESSAGE"
        const val MAX_MESSAGE = 4_000
    }
}
