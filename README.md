# Audio Converter (Strata plugin)

Audio-only port of "Convert Audio/Video" from AUDIO_VIDEO_TOOLS. Package `dev.cluvex.strata.audioconverter`.

Options (same as the web tool): codec AAC / MP3 / Opus / FLAC; AAC, MP3 and Opus bitrate presets + custom;
MP3 CBR / VBR / ABR; Opus application (VoIP / Audio) and VBR on/off (sample rate fixed at 48000 Hz);
FLAC level 0-8 and 16/24-bit; channels Mono / Stereo / Input; sample-rate menus per codec
(AAC/MP3: 44100, 48000, input; FLAC: 44100, 48000, 96000, 192000, custom). Video streams are dropped (`-vn`).
The result is returned to Strata as `<name>_encoded.<ext>` with a Settings / Output report as the MESSAGE.

## Build
1. FFmpegKit is fetched from Maven Central (`dev.ffmpegkit-maintained:ffmpeg-kit-audio:6.0.3`, arm64-v8a only); no AAR needed.
2. For the same signature as the first version, create `keystore.properties` next to `settings.gradle.kts`:
   `storeFile=...jks`, `storePassword=...`, `keyAlias=...`, `keyPassword=...`
3. `./gradlew testDebugUnitTest assembleRelease`

## GitHub build
`.github/workflows/build.yml` runs the unit tests and builds the signed release APK (artifact `Audio_Converter`; tags `v*` also attach it to a release).
1. Add the repository secrets `AC_KEYSTORE_BASE64` (`base64 -w0 my-key.jks`), `AC_KEYSTORE_PASSWORD`, `AC_KEY_ALIAS`, `AC_KEY_PASSWORD`.
2. Push to `main` or run the workflow by hand.
