package dev.cluvex.strata.audioconverter

import java.util.Locale

/**
 * Audio encoder settings and ffmpeg arguments. A port of the "Convert Audio/Video" encoder of
 * AUDIO_VIDEO_TOOLS (parse_reencode_settings, apply_audio_reencode_args, _encoder_output_ext): the
 * same options, defaults, sample-rate menus and flags. Pure Kotlin, covered by unit tests.
 */
enum class AudioCodec(val ffmpeg: String, val label: String, val extension: String) {
    AAC("aac", "AAC", ".m4a"),
    MP3("libmp3lame", "MP3", ".mp3"),
    OPUS("libopus", "Opus", ".opus"),
    FLAC("flac", "FLAC", ".flac"),
}

enum class Mp3Mode { VBR, CBR, ABR }

enum class OpusApplication(val value: String, val label: String) {
    VOIP("voip", "VoIP (speech)"),
    AUDIO("audio", "Audio (music)"),
}

enum class ChannelMode { MONO, STEREO, ORIGINAL }

/** Sample-rate choices; [CUSTOM] uses [EncoderOptions.customSampleRate]. */
enum class SampleRateChoice(val key: String, val hz: Int?) {
    HZ_44100("44100", 44100),
    HZ_48000("48000", 48000),
    HZ_96000("96000", 96000),
    HZ_192000("192000", 192000),
    ORIGINAL("original", null),
    CUSTOM("custom", null),
}

data class EncoderOptions(
    val codec: AudioCodec = AudioCodec.AAC,
    val aacBitrate: Int = 192,
    val mp3Mode: Mp3Mode = Mp3Mode.CBR,
    val mp3Bitrate: Int = 192,
    val opusApplication: OpusApplication = OpusApplication.VOIP,
    val opusVbr: Boolean = true,
    val opusBitrate: Int = 128,
    val flacLevel: Int = 5,
    val flacDepth: Int = 16,
    val channels: ChannelMode = ChannelMode.ORIGINAL,
    val sampleRate: SampleRateChoice = SampleRateChoice.ORIGINAL,
    val customSampleRate: Int = 48000,
    /** Keep the cover art and the tags (title, artist, album…) of the source; off strips both. Only offered when the source has any. */
    val keepMetadata: Boolean = true,
) {
    /** Switching codec keeps the previous sample rate when the new menu has it, otherwise the menu's default. */
    fun withCodec(next: AudioCodec): EncoderOptions {
        val valid = EncoderSettings.sampleRateChoices(next)
        val kept = if (sampleRate in valid) sampleRate else valid.first { it == EncoderSettings.defaultSampleRate(next) }
        return copy(codec = next, sampleRate = kept)
    }
}

/** What the encoder will actually do, after the same resolution rules as the web tool. */
data class ResolvedSettings(
    val codec: AudioCodec,
    val bitrate: Int?,
    val mp3Mode: Mp3Mode?,
    val opusApplication: String?,
    val opusVbr: String?,
    val flacLevel: Int?,
    val flacDepth: Int?,
    val channelCount: Int?,
    val sampleRate: Int?,
    val metaMode: MetaMode = MetaMode.DEFAULT,
    /** Stream index of the cover art that is carried over, null when it is dropped. */
    val coverStream: Int? = null,
    val coverCodec: String? = null,
)

enum class MetaMode { DEFAULT, KEEP, STRIP }

/** What the source file carries besides the sound. */
data class SourceMeta(val hasTags: Boolean = false, val coverStream: Int? = null, val coverCodec: String? = null) {
    val any get() = hasTags || coverStream != null
}

object EncoderSettings {
    val AAC_BITRATES = listOf(128, 192, 256)
    val MP3_BITRATES = listOf(128, 192, 256, 320)
    val OPUS_BITRATES = listOf(96, 128, 160, 192)

    // The min/max of the number boxes next to the bitrate buttons.
    val AAC_RANGE = 32..512
    val MP3_RANGE = 32..320
    val OPUS_RANGE = 16..512
    const val MIN_CUSTOM_SAMPLE_RATE = 8000

    /** Menus match the original: Opus is fixed, FLAC adds hi-res rates and has no "input file", AAC/MP3 offer the input rate. */
    fun sampleRateChoices(codec: AudioCodec): List<SampleRateChoice> = when (codec) {
        AudioCodec.OPUS -> listOf(SampleRateChoice.HZ_48000)
        AudioCodec.FLAC -> listOf(
            SampleRateChoice.HZ_44100, SampleRateChoice.HZ_48000, SampleRateChoice.HZ_96000, SampleRateChoice.HZ_192000, SampleRateChoice.CUSTOM,
        )
        else -> listOf(SampleRateChoice.HZ_44100, SampleRateChoice.HZ_48000, SampleRateChoice.ORIGINAL)
    }

    fun defaultSampleRate(codec: AudioCodec): SampleRateChoice = when (codec) {
        AudioCodec.OPUS, AudioCodec.FLAC -> SampleRateChoice.HZ_48000
        else -> SampleRateChoice.ORIGINAL
    }

    fun sampleRateLabel(choice: SampleRateChoice, codec: AudioCodec): String = when (choice) {
        SampleRateChoice.HZ_44100 -> "44100 Hz (CD)"
        SampleRateChoice.HZ_48000 -> if (codec == AudioCodec.OPUS) "48000 Hz (Opus fixed)" else "48000 Hz (Studio)"
        SampleRateChoice.HZ_96000 -> "96000 Hz (Hi-Res)"
        SampleRateChoice.HZ_192000 -> "192000 Hz (Ultra)"
        SampleRateChoice.ORIGINAL -> "Input file (original)"
        SampleRateChoice.CUSTOM -> "Custom"
    }

    /** MP3 VBR quality (-q:a) for a target bitrate. */
    fun bitrateToVbrQuality(bitrate: Int): Int = when {
        bitrate >= 320 -> 0
        bitrate >= 256 -> 1
        bitrate >= 192 -> 2
        bitrate >= 160 -> 3
        bitrate >= 128 -> 4
        bitrate >= 96 -> 5
        bitrate >= 80 -> 6
        bitrate >= 64 -> 7
        else -> 8
    }

    fun resolve(options: EncoderOptions, inputSampleRate: Int?, source: SourceMeta = SourceMeta()): ResolvedSettings {
        val codec = options.codec
        val bitrate = when (codec) {
            AudioCodec.AAC -> options.aacBitrate.coerceIn(AAC_RANGE)
            AudioCodec.MP3 -> options.mp3Bitrate.coerceIn(MP3_RANGE)
            AudioCodec.OPUS -> options.opusBitrate.coerceIn(OPUS_RANGE)
            AudioCodec.FLAC -> null
        }
        val sampleRate = when {
            codec == AudioCodec.OPUS -> 48000
            options.sampleRate == SampleRateChoice.ORIGINAL -> inputSampleRate?.takeIf { it > 0 } ?: 44100
            options.sampleRate == SampleRateChoice.CUSTOM -> options.customSampleRate.coerceAtLeast(MIN_CUSTOM_SAMPLE_RATE)
            else -> options.sampleRate.hz ?: 48000
        }
        return ResolvedSettings(
            codec = codec,
            bitrate = bitrate,
            mp3Mode = options.mp3Mode.takeIf { codec == AudioCodec.MP3 },
            opusApplication = options.opusApplication.value.takeIf { codec == AudioCodec.OPUS },
            opusVbr = (if (options.opusVbr) "on" else "off").takeIf { codec == AudioCodec.OPUS },
            flacLevel = options.flacLevel.coerceIn(0, 8).takeIf { codec == AudioCodec.FLAC },
            flacDepth = options.flacDepth.takeIf { codec == AudioCodec.FLAC },
            channelCount = when (options.channels) {
                ChannelMode.MONO -> 1
                ChannelMode.STEREO -> 2
                ChannelMode.ORIGINAL -> null
            },
            sampleRate = sampleRate,
            // Nothing to keep or strip when the source has no cover or tags: leave ffmpeg's defaults alone.
            metaMode = when {
                !source.any -> MetaMode.DEFAULT
                options.keepMetadata -> MetaMode.KEEP
                else -> MetaMode.STRIP
            },
            // Opus (Ogg) cannot carry a cover picture in this ffmpeg, the other formats can.
            coverStream = source.coverStream.takeIf { options.keepMetadata && codec != AudioCodec.OPUS },
            coverCodec = source.coverCodec,
        )
    }

    /** Arguments after "-vn": codec, bitrate / mode flags, sample rate, channels (apply_audio_reencode_args). */
    fun audioArgs(s: ResolvedSettings): List<String> {
        val out = mutableListOf("-c:a", s.codec.ffmpeg)
        val b = s.bitrate ?: 0
        when (s.codec) {
            AudioCodec.MP3 -> {
                when (s.mp3Mode) {
                    Mp3Mode.VBR -> out += listOf("-q:a", bitrateToVbrQuality(b).toString(), "-abr", "0")
                    Mp3Mode.ABR -> out += listOf("-b:a", "${b}k", "-abr", "1", "-minrate", "${b / 2}k", "-maxrate", "${b * 2}k", "-bufsize", "${b * 2}k")
                    else -> out += listOf("-b:a", "${b}k", "-minrate", "${b}k", "-maxrate", "${b}k", "-bufsize", "${b * 2}k", "-abr", "0")
                }
                if (b >= 320) out += listOf("-joint_stereo", "0")
            }
            AudioCodec.OPUS -> out += listOf("-b:a", "${b}k", "-application", s.opusApplication ?: "voip", "-vbr", s.opusVbr ?: "on", "-ar", "48000")
            AudioCodec.FLAC -> out += listOf("-compression_level", (s.flacLevel ?: 5).toString(), "-sample_fmt", if ((s.flacDepth ?: 16) == 16) "s16" else "s32")
            AudioCodec.AAC -> out += listOf("-b:a", "${b}k")
        }
        if (s.sampleRate != null && s.codec != AudioCodec.OPUS) out += listOf("-ar", s.sampleRate.toString())
        if (s.channelCount != null) out += listOf("-ac", s.channelCount.toString())
        return out
    }

    /**
     * ["-y", "-i", input, "-vn", ...audio args, output]: audio only, video streams are dropped.
     * With a cover to keep, the first audio stream and the cover picture are mapped and the picture is copied
     * (re-encoded to JPEG when it is not already JPEG or PNG) and marked as attached picture.
     */
    fun command(input: String, output: String, s: ResolvedSettings): List<String> {
        val args = mutableListOf("-y", "-i", input)
        val cover = s.coverStream
        if (cover != null) {
            args += listOf("-map", "0:a:0", "-map", "0:$cover")
            args += listOf("-c:v", if (s.coverCodec == "mjpeg" || s.coverCodec == "png") "copy" else "mjpeg", "-disposition:v:0", "attached_pic")
            if (s.codec == AudioCodec.MP3) {
                args += listOf("-id3v2_version", "3", "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)")
            }
        } else {
            args += "-vn"
        }
        when (s.metaMode) {
            MetaMode.KEEP -> args += listOf("-map_metadata", "0")
            MetaMode.STRIP -> args += listOf("-map_metadata", "-1")
            MetaMode.DEFAULT -> Unit
        }
        return args + audioArgs(s) + output
    }

    /** "<name>_encoded" with the same file-name cleaning as the web tool. */
    fun outputBaseName(original: String): String {
        val stem = original.substringBeforeLast('.', original)
        val safe = stem.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' || it == '.') it else '_' }
            .joinToString("").trim(' ', '.', '_').take(50).ifEmpty { "media" }
        return "${safe}_encoded"
    }

    fun outputName(original: String, codec: AudioCodec) = outputBaseName(original) + codec.extension

    fun formatDuration(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.2f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
