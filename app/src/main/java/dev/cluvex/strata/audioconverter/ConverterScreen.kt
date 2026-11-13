package dev.cluvex.strata.audioconverter

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// Premium dark UI with a luminous purple and silver gradient across selected controls and borders.
private val Bg = Color(0xFF080611)
private val BgTop = Color(0xFF10091B)
private val Panel = Color(0xFF130F1D)
private val Raised = Color(0xFF1B1526)
private val Border = Color(0xFF342443)
private val TextMain = Color(0xFFE8EDF2)
private val TextDim = Color(0xFF9A91A8)
private val Accent = Color(0xFFB44CFF)
private val Silver = Color(0xFFE8EAF2)
private val Danger = Color(0xFFF76A6A)
// Dark text on the gradient: white would vanish on the silver stops (contrast 1.2:1); this is 4.4:1 or better everywhere.
private val OnRainbow = Color(0xFF14081F)
private val Rainbow = listOf(
    Color(0xFF9350F5), Color(0xFFA566FF), Color(0xFFC79BFF), Color(0xFFE8EAF2),
    Color(0xFFB879FF), Color(0xFF9350F5),
)
private val RainbowLoop = Rainbow + Rainbow.first()

@Composable
fun ConverterTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Accent, onPrimary = OnRainbow, background = Bg, onBackground = TextMain,
        surface = Panel, onSurface = TextMain, surfaceVariant = Raised, onSurfaceVariant = TextDim,
        outline = Border, error = Danger,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 0..1, repeating: how far the purple gradient has panned. Read inside draw blocks so panning never recomposes. */
@Composable
private fun rememberPan(durationMs: Int): State<Float> =
    rememberInfiniteTransition(label = "pan").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMs, easing = LinearEasing)),
        label = "pan",
    )

private fun rainbowBrush(width: Float, pan: Float): Brush {
    val period = width * 2f
    val start = (pan % 1f) * period
    return Brush.linearGradient(RainbowLoop, start = Offset(start, 0f), end = Offset(start + period, 0f), tileMode = TileMode.Repeated)
}

private fun Modifier.rainbowFill(pan: State<Float>, shape: Shape, phase: Float = 0f, alpha: () -> Float = { 1f }): Modifier = drawBehind {
    val a = alpha()
    if (size.width <= 0f || a <= 0f) return@drawBehind
    drawOutline(shape.createOutline(size, layoutDirection, this), rainbowBrush(size.width, pan.value + phase), alpha = a)
}

private fun Modifier.rainbowBorder(pan: State<Float>, shape: Shape, width: Float): Modifier = drawBehind {
    if (size.width <= 0f) return@drawBehind
    val w = width * density
    inset(w / 2f) {
        drawOutline(shape.createOutline(this.size, layoutDirection, this), rainbowBrush(this.size.width, pan.value), style = Stroke(width = w))
    }
}

@Composable
private fun Appear(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 70L)
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(380)) + slideInVertically(tween(380)) { it / 5 },
    ) { content() }
}

@Composable
fun ConverterScreen(vm: ConverterViewModel, onDone: (ConvertResult.Done) -> Unit, onClose: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    BackHandler { if (ui.converting) vm.cancel() else onClose() }
    LaunchedEffect(ui.result) { ui.result?.let(onDone) }
    val slow = rememberPan(8000)
    val fast = rememberPan(5000)

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(BgTop, Bg))).systemBarsPadding().imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (ui.converting) vm.cancel() else onClose() }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close), tint = TextMain)
            }
            Text(
                stringResource(R.string.title_convert),
                style = TextStyle(brush = Brush.horizontalGradient(RainbowLoop), fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
            )
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val card = RoundedCornerShape(22.dp)
            Column(
                Modifier.fillMaxWidth()
                    .shadow(18.dp, card, ambientColor = Accent.copy(alpha = 0.35f), spotColor = Accent.copy(alpha = 0.35f))
                    .background(Panel, card)
                    .rainbowBorder(slow, card, 1.5f)
                    .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Appear(0) { FileCard(ui) }
                if (ui.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Accent, strokeWidth = 3.dp)
                        Text(stringResource(R.string.reading), color = TextDim)
                    }
                }
                ui.error?.let { Text(it, color = Danger) }
                val info = ui.info
                if (info != null) {
                    Options(ui.options, enabled = !ui.converting, pan = slow, change = vm::update)
                    // Only offered when the file really has a cover or tags to keep.
                    if (info.coverStream != null || info.hasTags) {
                        Appear(5) { KeepMetadataRow(info, ui.options, !ui.converting, slow, vm::update) }
                    }
                    Appear(6) {
                        if (ui.converting) Progress(ui.progress, fast, vm::cancel) else ConvertButton(fast, vm::convert)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileCard(ui: ConverterUi) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            ui.fileName,
            style = TextStyle(brush = Brush.horizontalGradient(RainbowLoop), fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val info = ui.info ?: return@Column
        val shape = RoundedCornerShape(16.dp)
        Column(Modifier.fillMaxWidth().background(Raised, shape).border(1.dp, Border, shape).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.info_audio), color = Accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(label), color = TextDim, fontSize = 15.sp, modifier = Modifier.weight(0.42f))
                    Text(value, color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.58f))
                }
            }
            if (info.hasVideo) Text(stringResource(R.string.video_dropped), color = Accent, fontSize = 13.sp)
        }
    }
}

/** The options of "Convert Audio/Video" in AUDIO_VIDEO_TOOLS, audio part only, in the same order. */
@Composable
private fun Options(o: EncoderOptions, enabled: Boolean, pan: State<Float>, change: ((EncoderOptions) -> EncoderOptions) -> Unit) {
    Appear(1) {
        Field(stringResource(R.string.label_audio_codec)) {
            Seg(AudioCodec.entries, o.codec, enabled, pan, { it.label }) { c -> change { it.withCodec(c) } }
        }
    }
    Appear(2) {
        AnimatedContent(
            targetState = o.codec,
            transitionSpec = { (fadeIn(tween(260)) + expandVertically(tween(260))) togetherWith (fadeOut(tween(160)) + shrinkVertically(tween(260))) },
            label = "codec-options",
        ) { codec ->
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) { CodecOptions(codec, o, enabled, pan, change) }
        }
    }
    Appear(3) {
        Field(stringResource(R.string.label_channels)) {
            Seg(ChannelMode.entries, o.channels, enabled, pan, {
                when (it) {
                    ChannelMode.MONO -> stringResource(R.string.mono)
                    ChannelMode.STEREO -> stringResource(R.string.stereo)
                    ChannelMode.ORIGINAL -> stringResource(R.string.input_file)
                }
            }) { c -> change { it.copy(channels = c) } }
        }
    }
    Appear(4) {
        Field(stringResource(R.string.label_sample_rate)) {
            Seg(EncoderSettings.sampleRateChoices(o.codec), o.sampleRate, enabled, pan, {
                when (it) {
                    SampleRateChoice.ORIGINAL -> stringResource(R.string.sr_original)
                    SampleRateChoice.CUSTOM -> stringResource(R.string.custom)
                    else -> EncoderSettings.sampleRateLabel(it, o.codec)
                }
            }) { s -> change { it.copy(sampleRate = s) } }
            if (o.codec == AudioCodec.OPUS) Text(stringResource(R.string.hint_opus_sr), color = TextDim, fontSize = 13.sp)
            AnimatedVisibility(o.sampleRate == SampleRateChoice.CUSTOM && o.codec != AudioCodec.OPUS) {
                NumberField(o.customSampleRate, stringResource(R.string.hint_hz, EncoderSettings.MIN_CUSTOM_SAMPLE_RATE), enabled) { v -> change { it.copy(customSampleRate = v) } }
            }
        }
    }
}

@Composable
private fun CodecOptions(codec: AudioCodec, o: EncoderOptions, enabled: Boolean, pan: State<Float>, change: ((EncoderOptions) -> EncoderOptions) -> Unit) {
    when (codec) {
        AudioCodec.AAC -> Field(stringResource(R.string.label_aac_bitrate), stringResource(R.string.unit_kbps)) {
            BitrateField(EncoderSettings.AAC_BITRATES, o.aacBitrate, EncoderSettings.AAC_RANGE, enabled, pan) { v -> change { it.copy(aacBitrate = v) } }
        }
        AudioCodec.MP3 -> {
            Field(stringResource(R.string.label_mp3_mode)) {
                Seg(Mp3Mode.entries, o.mp3Mode, enabled, pan, { it.name }) { m -> change { it.copy(mp3Mode = m) } }
            }
            // Each mode gets the control that matches what it really does: a fixed rate, an average, or a quality level.
            when (o.mp3Mode) {
                Mp3Mode.CBR -> Field(stringResource(R.string.label_mp3_bitrate), stringResource(R.string.unit_kbps)) {
                    BitrateField(EncoderSettings.MP3_BITRATES, o.mp3Bitrate, EncoderSettings.MP3_RANGE, enabled, pan) { v -> change { it.copy(mp3Bitrate = v) } }
                }
                Mp3Mode.ABR -> Field(stringResource(R.string.label_mp3_avg_bitrate), stringResource(R.string.unit_kbps)) {
                    BitrateField(EncoderSettings.MP3_BITRATES, o.mp3Bitrate, EncoderSettings.MP3_RANGE, enabled, pan) { v -> change { it.copy(mp3Bitrate = v) } }
                    val range = EncoderSettings.abrRange(o.mp3Bitrate.coerceIn(EncoderSettings.MP3_RANGE))
                    Text(stringResource(R.string.mp3_abr_note, o.mp3Bitrate.coerceIn(EncoderSettings.MP3_RANGE), range.first, range.last), color = TextDim, fontSize = 13.sp)
                }
                Mp3Mode.VBR -> Field(stringResource(R.string.label_mp3_quality)) {
                    Seg((0..9).toList(), o.mp3VbrQuality, enabled, pan, { "V$it" }) { q -> change { it.copy(mp3VbrQuality = q) } }
                    AnimatedContent(o.mp3VbrQuality, transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(100)) }, label = "vbr-note") { q ->
                        val l = EncoderSettings.VBR_LEVELS[q.coerceIn(0, 9)]
                        Text(stringResource(R.string.mp3_quality_note, q, l.average, l.min, l.max), color = TextMain, fontSize = 14.sp)
                    }
                    Text(stringResource(R.string.mp3_quality_hint), color = TextDim, fontSize = 13.sp)
                }
            }
        }
        AudioCodec.OPUS -> {
            Field(stringResource(R.string.label_opus_app)) {
                Seg(OpusApplication.entries, o.opusApplication, enabled, pan, {
                    if (it == OpusApplication.VOIP) stringResource(R.string.opus_voip) else stringResource(R.string.opus_audio)
                }) { a -> change { it.copy(opusApplication = a) } }
            }
            Field(stringResource(R.string.label_opus_vbr)) {
                Seg(listOf(true, false), o.opusVbr, enabled, pan, { if (it) stringResource(R.string.on) else stringResource(R.string.off) }) { v -> change { it.copy(opusVbr = v) } }
            }
            Field(stringResource(R.string.label_opus_bitrate), stringResource(R.string.unit_kbps)) {
                BitrateField(EncoderSettings.OPUS_BITRATES, o.opusBitrate, EncoderSettings.OPUS_RANGE, enabled, pan) { v -> change { it.copy(opusBitrate = v) } }
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
                        colors = SliderDefaults.colors(
                            thumbColor = Silver, activeTrackColor = Accent, inactiveTrackColor = Raised,
                            activeTickColor = Silver, inactiveTickColor = Border,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    AnimatedContent(o.flacLevel, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) }, label = "flac-level") { n ->
                        Text(n.toString(), color = TextMain, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Field(stringResource(R.string.label_flac_depth)) {
                Seg(listOf(16, 24), o.flacDepth, enabled, pan, { "$it-bit" }) { d -> change { it.copy(flacDepth = d) } }
            }
        }
    }
}

@Composable
private fun Field(title: String, unit: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (unit != null) Text(unit, color = TextDim, fontSize = 13.sp)
        }
        content()
    }
}

/** A segmented control: the selected button is filled with the panning purple gradient and pops in, presses squash it. */
@Composable
private fun <T> Seg(items: List<T>, selected: T, enabled: Boolean, pan: State<Float>, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    FlowRow(
        Modifier.clip(shape).background(Raised).border(1.dp, Border, shape).padding(4.dp).alpha(if (enabled) 1f else 0.55f),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { i, item -> SegButton(label(item), item == selected, enabled, pan, phase = i * 0.19f) { onSelect(item) } }
    }
}

@Composable
private fun SegButton(text: String, selected: Boolean, enabled: Boolean, pan: State<Float>, phase: Float, onClick: () -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val on by animateFloatAsState(if (selected) 1f else 0f, tween(260), label = "seg-on")
    val press by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "seg-press")
    val ink by animateColorAsState(if (selected) OnRainbow else TextDim, tween(220), label = "seg-ink")
    Box(
        Modifier
            .defaultMinSize(minWidth = 66.dp)
            .height(46.dp)
            .graphicsLayer { scaleX = press * (1f + 0.03f * on); scaleY = press * (1f + 0.03f * on) }
            .clip(shape)
            .rainbowFill(pan, shape, phase) { on }
            .clickable(interaction, indication = null, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = ink, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

/** Preset bitrates plus "Custom" with a number box limited to the range of the web tool. */
@Composable
private fun BitrateField(presets: List<Int>, value: Int, range: IntRange, enabled: Boolean, pan: State<Float>, onChange: (Int) -> Unit) {
    var custom by remember { mutableStateOf(value !in presets) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val shape = RoundedCornerShape(14.dp)
        FlowRow(
            Modifier.clip(shape).background(Raised).border(1.dp, Border, shape).padding(4.dp).alpha(if (enabled) 1f else 0.55f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            presets.forEachIndexed { i, p -> SegButton("$p", !custom && value == p, enabled, pan, i * 0.19f) { custom = false; onChange(p) } }
            SegButton(stringResource(R.string.custom), custom, enabled, pan, presets.size * 0.19f) { custom = true }
        }
        AnimatedVisibility(custom) { NumberField(value, stringResource(R.string.hint_range, range.first, range.last), enabled, onChange) }
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
        shape = RoundedCornerShape(12.dp),
        supportingText = { Text(hint, color = TextDim) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent, unfocusedBorderColor = Border, cursorColor = Accent,
            focusedContainerColor = Raised, unfocusedContainerColor = Raised,
            focusedTextColor = TextMain, unfocusedTextColor = TextMain,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun KeepMetadataRow(info: AudioInfo, o: EncoderOptions, enabled: Boolean, pan: State<Float>, change: ((EncoderOptions) -> EncoderOptions) -> Unit) {
    val hasCover = info.coverStream != null
    val opus = o.codec == AudioCodec.OPUS
    val note = when {
        hasCover && opus && info.hasTags -> R.string.keep_meta_opus
        hasCover && opus -> R.string.keep_meta_opus_cover_only
        hasCover && info.hasTags -> R.string.keep_meta_both
        hasCover -> R.string.keep_meta_cover
        else -> R.string.keep_meta_tags
    }
    Toggle(stringResource(R.string.keep_meta_title), stringResource(note), o.keepMetadata, enabled, pan) { v -> change { it.copy(keepMetadata = v) } }
}

/** A switch row in the same style: the track fills with the panning gradient, the thumb slides and changes colour. */
@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, enabled: Boolean, pan: State<Float>, onChange: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val on by animateFloatAsState(if (checked) 1f else 0f, tween(260), label = "toggle")
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Raised).border(1.dp, Border, shape).alpha(if (enabled) 1f else 0.55f)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = TextDim, fontSize = 13.sp)
        }
        Box(
            Modifier.size(width = 56.dp, height = 32.dp).clip(CircleShape).background(Bg).border(1.dp, Border, CircleShape).rainbowFill(pan, CircleShape) { on },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .offset { IntOffset(((4f + 24f * on) * density).roundToInt(), 0) }
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(lerp(TextDim, Silver, on)),
            )
        }
    }
}

/** The big purple-gradient button: the colours keep panning, a press squashes it. */
@Composable
private fun ConvertButton(pan: State<Float>, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "convert-press")
    Box(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(14.dp, shape, ambientColor = Accent.copy(alpha = 0.5f), spotColor = Accent.copy(alpha = 0.5f))
            .clip(shape)
            .rainbowFill(pan, shape)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.convert_button), color = OnRainbow, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
    }
}

@Composable
private fun Progress(progress: Float, pan: State<Float>, onCancel: () -> Unit) {
    val p by animateFloatAsState(progress, tween(300, easing = LinearEasing), label = "progress")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.converting, (p * 100).roundToInt()), color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(Raised).border(1.dp, Border, CircleShape)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(p.coerceIn(0.03f, 1f)).rainbowFill(pan, CircleShape))
        }
        val shape = RoundedCornerShape(14.dp)
        Box(
            Modifier.fillMaxWidth().height(50.dp).clip(shape).border(1.5.dp, Danger.copy(alpha = 0.8f), shape).clickable(role = Role.Button, onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.cancel), color = Danger, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
    }
}
