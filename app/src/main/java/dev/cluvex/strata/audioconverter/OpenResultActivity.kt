package dev.cluvex.strata.audioconverter

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.util.Locale

/**
 * Target of the notification taps; it has no screen. "Open" brings Strata back, where the converter screen is still
 * waiting and hands the file over, so Strata can save it. If that screen is gone, the file is opened in Strata directly.
 */
class OpenResultActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { handle(intent?.getBooleanExtra(EXTRA_FINISHED, false) == true) }
        finish()
    }

    private fun handle(finished: Boolean) {
        if (!finished) {
            openStrata()
            return
        }
        ConversionHub.markConsumed()
        Notifications.cancelDone(this)
        if (ConversionHub.hostAlive()) openStrata() else viewResult()
    }

    private fun strataPackages() = (listOfNotNull(ConversionHub.callerPackage) + STRATA_PACKAGES).distinct()

    private fun openStrata() {
        for (pkg in strataPackages()) {
            val launch = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            startActivity(launch)
            return
        }
    }

    private fun viewResult() {
        val file = Engine.latestResult(this)
        if (file == null) {
            Toast.makeText(this, R.string.result_missing, Toast.LENGTH_LONG).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, Engine.authority(this), file)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT)) ?: "audio/*"
        fun view() = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        for (pkg in strataPackages()) {
            try {
                startActivity(view().setPackage(pkg))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        try {
            startActivity(Intent.createChooser(view(), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
        }
    }

    companion object {
        const val EXTRA_FINISHED = "finished"
        private val STRATA_PACKAGES = listOf("dev.cluvex.strata", "dev.cluvex.strata.debug")
    }
}
