package dev.cluvex.strata.audioconverter

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
fun ConverterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConverterScreen(vm: ConverterViewModel, onDone: (ConvertResult.Done) -> Unit, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    BackHandler { if (ui.converting) vm.cancel() else onClose() }
    LaunchedEffect(ui.result) { ui.result?.let(onDone) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_convert)) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FileCard(ui)
            if (ui.loading) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text(stringResource(R.string.reading))
                }
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (ui.info != null) {
                Options(ui.options, enabled = !ui.converting, change = vm::update)
                if (ui.converting) {
                    LinearProgressIndicator(progress = { ui.progress }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.converting, (ui.progress * 100).roundToInt()), style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = vm::cancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
                } else {
                    Button(onClick = vm::convert, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.convert_button)) }
                }
            }
        }
    }
}

@Composable
private fun FileCard(ui: ConverterUi) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ui.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val info = ui.info ?: return@Column
            fun line(label: Int, value: String?) = value?.takeIf { it.isNotEmpty() }?.let { label to it }
            listOfNotNull(
                line(R.string.info_codec, info.codec),
                line(R.string.info_sample_rate, info.sampleRate?.let { "$it Hz" }),
                line(R.string.info_channels, info.channels?.let { c -> listOfNotNull(c.toString(), info.channelLayout?.takeIf { it.isNotEmpty() }?.let { "($it)" }).joinToString(" ") }),
                line(R.string.info_bitrate, info.audioBitrateKbps?.let { "$it kbps" }),
                line(R.string.info_bit_depth, info.bitDepth?.let { "$it-bit" }),
                line(R.string.info_duration, EncoderSettings.formatDuration(info.duration)),
                line(R.string.info_size, EncoderSettings.formatSize(info.sizeBytes)),
            ).forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(label), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Text(value, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (info.hasVideo) Text(stringResource(R.string.video_dropped), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** The options of "Convert Audio/Video" in AUDIO_VIDEO_TOOLS, audio part only, in the same order. */
@Composable
private fun Options(o: EncoderOptions, enabled: Boolean, change: ((EncoderOptions) -> EncoderOptions) -> Unit) {
    Field(stringResource(R.string.label_audio_codec)) {
        Chips(AudioCodec.entries, o.codec, enabled, { it.label }) { c -> change { it.withCodec(c) } }
    }
    when (o.codec) {
        AudioCodec.AAC -> Field(stringResource(R.string.label_aac_bitrate), stringResource(R.string.unit_kbps)) {
            BitrateField(EncoderSettings.AAC_BITRATES, o.aacBitrate, EncoderSettings.AAC_RANGE, enabled) { v -> change { it.copy(aacBitrate = v) } }
        }
        AudioCodec.MP3 -> {
            Field(stringResource(R.string.label_mp3_mode)) {
                Chips(Mp3Mode.entries, o.mp3Mode, enabled, { it.name }) { m -> change { it.copy(mp3Mode = m) } }
            }
            Field(stringResource(R.string.label_mp3_bitrate), stringResource(R.string.unit_kbps)) {
                BitrateField(EncoderSettings.MP3_BITRATES, o.mp3Bitrate, EncoderSettings.MP3_RANGE, enabled) { v -> change { it.copy(mp3Bitrate = v) } }
            }
        }
        AudioCodec.OPUS -> {
            Field(stringResource(R.string.label_opus_app)) {
                Chips(OpusApplication.entries, o.opusApplication, enabled, {
                    if (it == OpusApplication.VOIP) stringResource(R.string.opus_voip) else stringResource(R.string.opus_audio)
                }) { a -> change { it.copy(opusApplication = a) } }
            }
            Field(stringResource(R.string.label_opus_vbr)) {
                Chips(listOf(true, false), o.opusVbr, enabled, { if (it) stringResource(R.string.on) else stringResource(R.string.off) }) { v -> change { it.copy(opusVbr = v) } }
            }
            Field(stringResource(R.string.label_opus_bitrate), stringResource(R.string.unit_kbps)) {
                BitrateField(EncoderSettings.OPUS_BITRATES, o.opusBitrate, EncoderSettings.OPUS_RANGE, enabled) { v -> change { it.copy(opusBitrate = v) } }
            }
        }
        AudioCodec.FLAC -> {
            Field(stringResource(R.string.label_flac_level)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Slider(
                        value = o.flacLevel.toFloat(),
                        onValueChange = { v -> change { it.copy(flacLevel = v.roundToInt().coerceIn(0, 8)) } },
                        valueRange = 0f..8f,
                        steps = 7,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                    Text(o.flacLevel.toString(), style = MaterialTheme.typography.titleMedium)
                }
            }
            Field(stringResource(R.string.label_flac_depth)) {
                Chips(listOf(16, 24), o.flacDepth, enabled, { "$it-bit" }) { d -> change { it.copy(flacDepth = d) } }
            }
        }
    }
    Field(stringResource(R.string.label_channels)) {
        Chips(ChannelMode.entries, o.channels, enabled, {
            when (it) {
                ChannelMode.MONO -> stringResource(R.string.mono)
                ChannelMode.STEREO -> stringResource(R.string.stereo)
                ChannelMode.ORIGINAL -> stringResource(R.string.input_file)
            }
        }) { c -> change { it.copy(channels = c) } }
    }
    Field(stringResource(R.string.label_sample_rate)) {
        Chips(EncoderSettings.sampleRateChoices(o.codec), o.sampleRate, enabled, {
            when (it) {
                SampleRateChoice.ORIGINAL -> stringResource(R.string.sr_original)
                SampleRateChoice.CUSTOM -> stringResource(R.string.custom)
                else -> EncoderSettings.sampleRateLabel(it, o.codec)
            }
        }) { s -> change { it.copy(sampleRate = s) } }
        if (o.codec == AudioCodec.OPUS) {
            Text(stringResource(R.string.hint_opus_sr), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (o.sampleRate == SampleRateChoice.CUSTOM && o.codec != AudioCodec.OPUS) {
            NumberField(o.customSampleRate, stringResource(R.string.hint_hz, EncoderSettings.MIN_CUSTOM_SAMPLE_RATE), enabled) { v -> change { it.copy(customSampleRate = v) } }
        }
    }
}

@Composable
private fun Field(title: String, unit: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (unit != null) Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun <T> Chips(items: List<T>, selected: T, enabled: Boolean, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            FilterChip(selected = item == selected, onClick = { onSelect(item) }, enabled = enabled, label = { Text(label(item)) })
        }
    }
}

/** Preset bitrates plus "Custom" with a number box limited to the range of the web tool. */
@Composable
private fun BitrateField(presets: List<Int>, value: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    var custom by remember { mutableStateOf(value !in presets) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { p ->
                FilterChip(selected = !custom && value == p, onClick = { custom = false; onChange(p) }, enabled = enabled, label = { Text("$p") })
            }
            FilterChip(selected = custom, onClick = { custom = true }, enabled = enabled, label = { Text(stringResource(R.string.custom)) })
        }
        if (custom) NumberField(value, stringResource(R.string.hint_range, range.first, range.last), enabled, onChange)
    }
}

@Composable
private fun NumberField(value: Int, hint: String, enabled: Boolean, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            val digits = s.filter(Char::isDigit).take(7)
            text = digits
            digits.toIntOrNull()?.let(onChange)
        },
        enabled = enabled,
        singleLine = true,
        supportingText = { Text(hint) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
