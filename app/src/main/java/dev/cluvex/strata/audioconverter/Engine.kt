package dev.cluvex.strata.audioconverter

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/** What ffprobe reports about a file (the input card and the Output section of the report). */
data class AudioInfo(
    val codec: String?,
    val sampleRate: Int?,
    val channels: Int?,
    val channelLayout: String?,
    val bitrateKbps: Int?,
    val audioBitrateKbps: Int?,
    val bitDepth: Int?,
    val duration: Double,
    val hasVideo: Boolean,
    val sizeBytes: Long,
)

sealed interface ConvertResult {
    data class Done(val uri: Uri, val name: String, val report: String) : ConvertResult
    data class Failed(val message: String) : ConvertResult
}

object Engine {
    const val RESULTS_SUFFIX = ".results"

    fun authority(context: Context) = context.packageName + RESULTS_SUFFIX

    private fun workDir(context: Context, name: String) = File(context.cacheDir, name).apply { mkdirs() }

    /** Results and input copies from earlier runs are removed when a new file arrives. */
    fun cleanUp(context: Context) {
        listOf("results", "input").forEach { File(context.cacheDir, it).deleteRecursively() }
    }

    /** Copies the shared content URI into the cache: ffmpeg needs a real path. */
    suspend fun stageInput(context: Context, uri: Uri, fileName: String): File = withContext(Dispatchers.IO) {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(8)
        val target = File(workDir(context, "input"), if (ext.isEmpty()) "input" else "input.$ext")
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: throw java.io.IOException("Could not open the shared file.")
        target
    }

    suspend fun probe(path: String): AudioInfo? = withContext(Dispatchers.IO) {
        val info = FFprobeKit.getMediaInformation(path).mediaInformation ?: return@withContext null
        val streams = info.streams.orEmpty()
        val audio = streams.firstOrNull { it.type == "audio" }
        val props: JSONObject? = audio?.allProperties
        fun kbps(raw: String?): Int? = raw?.toDoubleOrNull()?.let { (if (it > 1000) it / 1000.0 else it).toInt() }?.takeIf { it > 0 }
        val containerKbps = kbps(info.bitrate)
        val streamKbps = kbps(props?.optString("bit_rate")?.takeIf { it.isNotEmpty() })
        AudioInfo(
            codec = audio?.codec,
            sampleRate = audio?.sampleRate?.toIntOrNull(),
            channels = props?.optInt("channels", 0)?.takeIf { it > 0 },
            channelLayout = audio?.channelLayout,
            bitrateKbps = containerKbps,
            // ffprobe does not always give a per-stream rate (notably for Opus): fall back to the container's.
            audioBitrateKbps = streamKbps ?: containerKbps,
            bitDepth = props?.optString("bits_per_raw_sample")?.toIntOrNull()?.takeIf { it > 0 }
                ?: props?.optInt("bits_per_sample", 0)?.takeIf { it > 0 },
            duration = info.duration?.toDoubleOrNull() ?: 0.0,
            hasVideo = streams.any { it.type == "video" && it.allProperties?.optJSONObject("disposition")?.optInt("attached_pic", 0) != 1 },
            sizeBytes = File(path).length(),
        )
    }

    /** Runs the conversion; [onProgress] gets 0f..1f. Cancelling the coroutine cancels ffmpeg. */
    suspend fun convert(
        context: Context,
        input: File,
        originalName: String,
        inputInfo: AudioInfo?,
        options: EncoderOptions,
        onProgress: (Float) -> Unit,
    ): ConvertResult = withContext(Dispatchers.IO) {
        val settings = EncoderSettings.resolve(options, inputInfo?.sampleRate)
        val duration = inputInfo?.duration ?: 0.0
        if (duration <= 0.0) return@withContext ConvertResult.Failed("Could not detect media duration.")

        val outName = EncoderSettings.outputName(originalName, settings.codec)
        val outFile = File(workDir(context, "results"), outName).also { it.delete() }
        val command = EncoderSettings.command(input.absolutePath, outFile.absolutePath, settings).toTypedArray()

        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            val session = FFmpegKit.executeWithArgumentsAsync(
                command,
                { s -> if (cont.isActive) cont.resume(ReturnCode.isSuccess(s.returnCode)) },
                null,
                { stats -> onProgress((stats.time / 1000.0 / duration).toFloat().coerceIn(0f, 1f)) },
            )
            cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
        }
        if (!ok || !outFile.isFile || outFile.length() == 0L) {
            outFile.delete()
            return@withContext ConvertResult.Failed("Encoding failed.")
        }
        onProgress(1f)
        val outInfo = runCatching { probe(outFile.absolutePath) }.getOrNull()
        val uri = FileProvider.getUriForFile(context, authority(context), outFile)
        ConvertResult.Done(uri, outName, report(outName, settings, outInfo, outFile.length(), duration))
    }

    /** The same Settings / Output sections the web tool shows after a conversion. */
    fun report(fileName: String, s: ResolvedSettings, out: AudioInfo?, outSize: Long, duration: Double): String {
        val lines = mutableListOf<String>()
        fun row(k: String, v: String) { lines += "$k: $v" }
        lines += "Settings"
        row("File", fileName)
        row("Format", fileName.substringAfterLast('.').uppercase(Locale.ROOT))
        row("Audio Codec", s.codec.label)
        if (s.bitrate != null) row("Audio Bitrate", "${s.bitrate} kbps")
        if (s.codec == AudioCodec.MP3 && s.mp3Mode != null) row("MP3 Mode", s.mp3Mode.name)
        if (s.codec == AudioCodec.FLAC) {
            row("FLAC Level", "${s.flacLevel}")
            s.flacDepth?.let { row("Bit Depth", "$it-bit") }
        }
        row("Channels", when (s.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; else -> "Input File" })
        s.sampleRate?.let { row("Sample Rate", "$it Hz") }

        lines += ""
        lines += "Output"
        row("Duration", EncoderSettings.formatDuration(out?.duration?.takeIf { it > 0 } ?: duration))
        row("Size", EncoderSettings.formatSize(outSize))
        out?.codec?.let { row("Audio Codec", it) }
        out?.audioBitrateKbps?.let { row("Audio Bitrate", "$it kbps") }
        out?.bitrateKbps?.let { row("Total Bitrate", "$it kbps") }
        out?.bitDepth?.let { row("Bit Depth", "$it-bit") }
        out?.sampleRate?.let { row("Sample Rate", "$it Hz") }
        out?.channels?.let { row("Channels", when (it) { 1 -> "Mono"; 2 -> "Stereo"; else -> "$it" }) }
        return lines.joinToString("\n")
    }

    fun isCancel(t: Throwable) = t is CancellationException
}
