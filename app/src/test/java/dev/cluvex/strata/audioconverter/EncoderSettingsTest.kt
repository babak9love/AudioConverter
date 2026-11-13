package dev.cluvex.strata.audioconverter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected arguments are what apply_audio_reencode_args of AUDIO_VIDEO_TOOLS builds for the same input. */
class EncoderSettingsTest {
    private fun args(o: EncoderOptions, inputRate: Int? = 44100) = EncoderSettings.audioArgs(EncoderSettings.resolve(o, inputRate))

    @Test fun aacDefault() = assertEquals(listOf("-c:a", "aac", "-b:a", "192k", "-ar", "44100"), args(EncoderOptions()))

    @Test fun aacOriginalUsesInputRateAndStereo() =
        assertEquals(listOf("-c:a", "aac", "-b:a", "256k", "-ar", "32000", "-ac", "2"), args(EncoderOptions(aacBitrate = 256, channels = ChannelMode.STEREO), 32000))

    @Test fun mp3Cbr() = assertEquals(
        listOf("-c:a", "libmp3lame", "-b:a", "192k", "-minrate", "192k", "-maxrate", "192k", "-bufsize", "384k", "-abr", "0", "-ar", "48000"),
        args(EncoderOptions(codec = AudioCodec.MP3, sampleRate = SampleRateChoice.HZ_48000)),
    )

    @Test fun mp3Vbr320() = assertEquals(
        listOf("-c:a", "libmp3lame", "-q:a", "0", "-abr", "0", "-joint_stereo", "0", "-ar", "44100", "-ac", "1"),
        args(EncoderOptions(codec = AudioCodec.MP3, mp3Mode = Mp3Mode.VBR, mp3Bitrate = 320, channels = ChannelMode.MONO)),
    )

    @Test fun mp3Abr() = assertEquals(
        listOf("-c:a", "libmp3lame", "-b:a", "128k", "-abr", "1", "-minrate", "64k", "-maxrate", "256k", "-bufsize", "256k", "-ar", "44100"),
        args(EncoderOptions(codec = AudioCodec.MP3, mp3Mode = Mp3Mode.ABR, mp3Bitrate = 128)),
    )

    @Test fun opusIsAlways48k() = assertEquals(
        listOf("-c:a", "libopus", "-b:a", "128k", "-application", "voip", "-vbr", "on", "-ar", "48000"),
        args(EncoderOptions(codec = AudioCodec.OPUS)),
    )

    @Test fun opusMusicNoVbr() = assertEquals(
        listOf("-c:a", "libopus", "-b:a", "160k", "-application", "audio", "-vbr", "off", "-ar", "48000", "-ac", "2"),
        args(EncoderOptions(codec = AudioCodec.OPUS, opusApplication = OpusApplication.AUDIO, opusVbr = false, opusBitrate = 160, channels = ChannelMode.STEREO)),
    )

    @Test fun flac16() = assertEquals(
        listOf("-c:a", "flac", "-compression_level", "5", "-sample_fmt", "s16", "-ar", "48000"),
        args(EncoderOptions(codec = AudioCodec.FLAC, sampleRate = SampleRateChoice.HZ_48000)),
    )

    @Test fun flac24Custom() = assertEquals(
        listOf("-c:a", "flac", "-compression_level", "8", "-sample_fmt", "s32", "-ar", "88200"),
        args(EncoderOptions(codec = AudioCodec.FLAC, flacLevel = 8, flacDepth = 24, sampleRate = SampleRateChoice.CUSTOM, customSampleRate = 88200)),
    )

    @Test fun vbrQualityTable() {
        val expected = mapOf(320 to 0, 256 to 1, 192 to 2, 160 to 3, 128 to 4, 96 to 5, 80 to 6, 64 to 7, 32 to 8)
        expected.forEach { (kbps, q) -> assertEquals(q, EncoderSettings.bitrateToVbrQuality(kbps)) }
    }

    @Test fun sampleRateMenusFollowTheCodec() {
        assertEquals(listOf(SampleRateChoice.HZ_48000), EncoderSettings.sampleRateChoices(AudioCodec.OPUS))
        assertTrue(SampleRateChoice.ORIGINAL !in EncoderSettings.sampleRateChoices(AudioCodec.FLAC))
        assertTrue(SampleRateChoice.CUSTOM in EncoderSettings.sampleRateChoices(AudioCodec.FLAC))
        assertEquals(SampleRateChoice.HZ_48000, EncoderOptions(codec = AudioCodec.AAC).withCodec(AudioCodec.FLAC).sampleRate)
        assertEquals(SampleRateChoice.HZ_44100, EncoderOptions(sampleRate = SampleRateChoice.HZ_44100).withCodec(AudioCodec.FLAC).sampleRate)
        assertEquals(SampleRateChoice.HZ_48000, EncoderOptions(sampleRate = SampleRateChoice.HZ_44100).withCodec(AudioCodec.OPUS).sampleRate)
    }

    @Test fun outputNames() {
        assertEquals("song_encoded.m4a", EncoderSettings.outputName("song.wav", AudioCodec.AAC))
        assertEquals("a_b_encoded.mp3", EncoderSettings.outputName("a/b.flac", AudioCodec.MP3))
        assertEquals("media_encoded.opus", EncoderSettings.outputName("..flac", AudioCodec.OPUS))
    }

    @Test fun commandDropsVideo() =
        assertEquals(listOf("-y", "-i", "in.mp4", "-vn", "-c:a", "aac", "-b:a", "192k", "-ar", "44100", "out.m4a"),
            EncoderSettings.command("in.mp4", "out.m4a", EncoderSettings.resolve(EncoderOptions(), 44100)))

    private fun cmd(o: EncoderOptions, meta: SourceMeta) = EncoderSettings.command("in", "out", EncoderSettings.resolve(o, 44100, meta))

    @Test fun keepCoverAndTagsMp3() = assertEquals(
        listOf("-y", "-i", "in", "-map", "0:a:0", "-map", "0:1", "-c:v", "copy", "-disposition:v:0", "attached_pic",
            "-id3v2_version", "3", "-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)", "-map_metadata", "0",
            "-c:a", "libmp3lame", "-b:a", "192k", "-minrate", "192k", "-maxrate", "192k", "-bufsize", "384k", "-abr", "0", "-ar", "44100", "out"),
        cmd(EncoderOptions(codec = AudioCodec.MP3), SourceMeta(hasTags = true, coverStream = 1, coverCodec = "mjpeg")),
    )

    @Test fun keepCoverReencodesOddPictures() {
        val c = cmd(EncoderOptions(codec = AudioCodec.FLAC), SourceMeta(coverStream = 2, coverCodec = "bmp"))
        assertTrue(c.containsAll(listOf("-map", "0:2", "-c:v", "mjpeg", "attached_pic")))
    }

    @Test fun opusKeepsTagsButNoCover() {
        val c = cmd(EncoderOptions(codec = AudioCodec.OPUS), SourceMeta(hasTags = true, coverStream = 1, coverCodec = "mjpeg"))
        assertTrue("-vn" in c)
        assertTrue("-map_metadata" in c && "0" == c[c.indexOf("-map_metadata") + 1])
        assertTrue("attached_pic" !in c)
    }

    @Test fun stripRemovesEverything() {
        val c = cmd(EncoderOptions(keepMetadata = false), SourceMeta(hasTags = true, coverStream = 1, coverCodec = "mjpeg"))
        assertEquals(listOf("-y", "-i", "in", "-vn", "-map_metadata", "-1"), c.take(6))
        assertTrue("attached_pic" !in c)
    }

    @Test fun nothingToKeepLeavesDefaults() =
        assertEquals(listOf("-y", "-i", "in", "-vn", "-c:a", "aac"), cmd(EncoderOptions(), SourceMeta()).take(6))
}
