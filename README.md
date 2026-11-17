# Voice Note Transcriber

Voice Note Transcriber is an Android app that turns audio files into editable text. It runs Parakeet Ultra on the phone's CPU through LiteRT, or sends the original audio file to Mistral's Voxtral Mini API.

The APK does not include the encoder: tap **Download model** once (665 MB, verified by SHA-256) or import an encoder file. The small Parakeet Ultra mel front ends and decoder (about 24 MB) are bundled in the APK.

## What it does

- Transcribes OGG and Opus voice notes, MP3, M4A, AAC, and WAV files.
- Decodes Ogg Opus directly with bundled libopus/libopusfile; other formats use Android MediaCodec.
- Runs Parakeet Ultra (30/20/10 s windows) on the CPU without a network connection.
- Offers `voxtral-mini-latest` as an online alternative.
- Detects the spoken language automatically (both engines).
- Keeps the transcript editable and provides a copy button.
- Shows model loading, audio decoding, transcription progress, elapsed time, cancellation, and errors.
- Remembers the selected audio and model between launches.
- Stores the Mistral API key with an AES-GCM key held by Android Keystore.

Local decoding accepts up to 15 minutes of audio. The Mistral path uploads the selected source file instead of decoding it on the phone.

## Interface

Material 3 with Material You: colours follow the wallpaper, including the adaptive launcher icon (system accent tones plus a monochrome layer for themed icons) (switchable to a built-in green palette), and the theme follows the system or is forced to light/dark in settings.

- **Local / Cloud switch** at the top. Local runs Parakeet Ultra on the phone; Cloud uses Mistral Voxtral. Choosing Local without an installed model selects nothing, so audio is never uploaded while the switch says Local.
- Local shows nothing extra once ready; Cloud shows one line (uploads to Mistral, automatic language). A setup card with the one-tap download appears only while no local model is installed; Cloud shows the API-key status. There is no language picker: both engines detect the language.
- **Audio card**: `WhatsApp` asks once for the WhatsApp voice-note folder (a read-only Storage Access Framework grant, opened at `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes`) and then shows an in-app list of the newest voice notes first (only the newest weekly folders are read). The system picker cannot be sorted by an app, and that folder has a `.nomedia` file, so MediaStore does not index it. `Files` opens the system picker at the top of internal storage. A waveform animates while work runs.
- **Transcript card**: editable, with copy and share.
- **Bottom bar**: the large Transcribe button, stage/percent/elapsed progress, Cancel, and the finished/failed status.
- **Settings sheet**: appearance, on-device model (download, remove, import), Mistral API key, about/licences.
- **Unfolded foldables / tablets** (≥ 600 dp): two panes, controls left and a full-height transcript right.
- **History**: collapsing app bar, search, Local/Cloud badges, copy/share/delete.

The screen stays awake while a transcription is running. A cancel button appears during active work. UI and notification Cancel use the same process-owned authority, including shares still waiting for metadata or model loading; cancelling these releases their foreground-service reservation immediately.

### Share to the app

Voice notes can be shared from WhatsApp or any other app (`ACTION_SEND` with `audio/*` or `application/ogg`). The app loads the audio and starts transcribing as soon as the selected model is ready. A cloud model still asks for upload consent first; without a usable model the audio is only loaded.

Shared files come with a temporary read permission that can end when the app's screen is closed. The audio is read when a transcription starts (local decoding opens it, the Mistral path copies it to a staging file), so the automatic start happens while that permission is valid. Shared audio is not remembered for the next launch.

### History

Every finished transcript is saved on the device (`files/transcript_history.json`, newest 100). Edits to the current transcript are saved to its history entry. The list button on the main screen opens the history, where entries can be copied, shared as text, or deleted. The file is written atomically with a synced `.bak` recovery copy; an unreadable file falls back to the backup or an empty history. Deleting an entry also sanitizes recovery copies so it cannot reappear from `.bak`/`.corrupt`. If saving fails, the newest snapshot stays pending in RAM: the app shows an error and a `Retry saving` action, and background flush retries even without another edit. Pending changes cannot survive process death until storage succeeds.

### Background work

Imports and transcriptions run in an app-wide coordinator, not in the screen. While work runs, a foreground service shows a progress notification with a Cancel action, so the work continues when the app is in the background or removed from recents. If it finishes while the app is not visible, a "Transcription finished" notification opens the app. On Android 13+ the notification permission is requested once, at the first transcription; everything works without it, only the notifications are hidden. The service type is `mediaProcessing` on Android 15 and `dataSync` on Android 12–14.

## Requirements

| Item | Requirement |
| --- | --- |
| Android | Android 12 or newer, API 31 |
| CPU architecture | `arm64-v8a` |
| Build JDK | Java 17 |
| Android SDK | Compile SDK 35 |
| Gradle plugin | Android Gradle Plugin 8.6.1 |
| Kotlin | 2.3.0 |

Parakeet Ultra runs on the CPU of any ARM64 Android device; no NPU or GPU is used.

## Build and install

Clone your fork or local copy, then run:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Set up local transcription

### Parakeet Ultra

[Parakeet Ultra](https://huggingface.co/litert-community/parakeet-ultra) is the only on-device model. It runs on the CPU with long windows. Download **one** encoder and copy it to a location that Android's file picker can read:

| File | Size | Use |
| --- | --- | --- |
| `parakeet_ultra_encoder_30s_i8.tflite` | 665 MB | Recommended: best accuracy |
| `parakeet_ultra_encoder_20s_i8.tflite` | 641 MB | In between |
| `parakeet_ultra_encoder_10s_i8.tflite` | 616 MB | Slower phones and short notes: less padding per window |

In the app:

**One tap:** with Local selected, tap `Download model` on the main screen (or in settings). The app downloads `parakeet_ultra_encoder_30s_i8.tflite` from litert-community/parakeet-ultra into app-private storage (`no_backup/models`), resumes after cancellation or network errors, checks the exact size and the pinned SHA-256 (`706675fd…e5cd`) before installing it, and selects it. On a metered connection it asks first. A foreground notification keeps the download running in the background. `Remove download` in settings frees the space again.

**Manual import** (e.g. the 10 s encoder):

1. Open the settings sheet.
2. Tap `Import .tflite file instead` and select the encoder.
3. Select an audio file and tap `Transcribe`.

The importer checks the filename and the TFLite header; the model is referenced through Android's Storage Access Framework, not copied into the APK. Files that are not part of the Parakeet Ultra family (for example Parakeet TDT v3 or NPU exports) are rejected with an error that names the Ultra encoder to import. The window length comes from the filename; browser suffixes such as ` (1)` are accepted. The settings list shows it as `Parakeet Ultra · CPU · 30 s windows`, and the main screen as `On-device model: Parakeet Ultra`. The `f16` encoders are listed but disabled: they are meant for GPU/NPU, which the app does not support for Ultra yet. Do not import the mel or decoder files; the app bundles them.

How the app runs it (a port of the model card's pipeline):

- Audio up to one window (30 s: 479,840 samples; 20 s: 320,000; 10 s: 160,000) is one zero-padded window. Longer audio is cut into overlapping windows with a hop of window − 2 s (30 s → 28 s, 10 s → 8 s). Each token is kept only by the window whose own region contains its time; the regions meet in the middle of each overlap (the first window keeps from 0, the last up to the end). This avoids the split or repeated words of fixed cuts.
- Each window: bundled mel graph → imported encoder → greedy TDT decoding with the bundled int8 decoder, starting from blank with zero state (token 3 ends the window, ten symbols per frame at most, 3,750 steps at most).
- Everything runs on the CPU (XNNPACK): encoder 4 threads, mel 2, decoder 1. Only one encoder is loaded at a time.
- The encoder is used from a verified private copy: the first preparation copies it (about 0.6 GB) into app-private storage; later starts reuse the verified copy.
- The bundled graphs are loaded straight from the APK (stored uncompressed). If LiteRT cannot load an asset, the app extracts it once to `noBackupFilesDir/bundled-models`, checks size and SHA-256, and loads it from there.

Measured on a Galaxy Note10+ (Exynos 9825, `benchmark_model`, 4 threads): one encoder call takes 0.68 s for a 10 s window and 2.28 s for a 30 s window (the v3 5 s encoder: about 0.69 s).

### Why Parakeet TDT v3 and the NPU path were removed

Earlier versions also ran Parakeet TDT 0.6B v3 (stateful 5 s exports) on the CPU and on Qualcomm NPUs through QNN. On the three test phones (OnePlus 12 / SM8650, Galaxy Z Fold 4 / SM8475, Galaxy Note10+ / Exynos 9825) Parakeet Ultra i8 on the CPU was both more accurate (table below) and faster than v3. The only advantage of v3, NPU exports, did not pay off: on the OnePlus 12 the v3 i8 CPU model already beat its own NPU export, and the SM8450 export returned blank text on the Fold 4. Removing v3 and QNN dropped the QNN runtime and the Qualcomm dispatch library and shrank the debug APK from 136.5 MB to 63.1 MB.

Models imported with an older version stay in the model list, disabled, with the note "Parakeet v3 was replaced by Parakeet Ultra — import parakeet_ultra_encoder_30s_i8.tflite (or 10s on slower phones)". If one of them was selected, nothing is selected (the cloud model is never chosen automatically). Their private copies in app storage are released at the next app start.

### Accuracy on WhatsApp voice notes

Word error rate on 29 real German WhatsApp voice notes (23 minutes), measured with a desktop port of the app's pipeline against Mistral `voxtral-small-transcribe-3` transcripts as the reference (not human ground truth):

| Pipeline | WER |
| --- | --- |
| Parakeet TDT v3, 5 s chunks, decoder state carried across chunks (original) | 30.9% |
| Parakeet TDT v3, 5 s chunks, fresh decoder state per chunk (last v3 version) | 18.1% |
| Parakeet Ultra i8, 10 s windows, no overlap | 13.4% |
| Parakeet Ultra i8, 10 s windows, hop 8 s (app setting) | 12.2% |
| Parakeet Ultra i8, 30 s windows, no overlap | 10.7% |
| Parakeet Ultra i8, 30 s windows, hop 25 s | 10.4% |

The app uses hop = window − 2 s (28 s for 30 s windows); the 30 s row with overlap above was measured with a 25 s hop. A follow-up using the app's fixed 30 s encoder padding and 28 s hop scored 10.36% with the previous desktop/ffmpeg PCM and 10.39% with actual Android native-libopus/resampler PCM on the same 29 notes: effectively unchanged at this sample size.

Parakeet TDT v3 rows are kept for reference; v3 is no longer part of the app.

## Set up Mistral transcription

1. Create an API key in the [Mistral console](https://console.mistral.ai).
2. Open the app's settings sheet.
3. Tap `Add or change Mistral API key` and save the key.
4. Select `Mistral Voxtral Mini (API)` as the model.
5. Select an audio file and tap `Transcribe`.

This option requires internet access. It uploads the original audio to `https://api.mistral.ai/v1/audio/transcriptions` and requests `voxtral-mini-latest`. If you select a language, the app sends its language code. Auto-detect omits that field.

The settings dialog can also remove a saved key. Status loading, saving and removing all run off the UI thread, with loading/busy states and errors. A slow KeyStore read from a cancelled request cannot synchronously block dialog composition. UI and backend share the same key store, with cross-instance serialization of read/write/reset.

The app encrypts the saved key with AES-GCM. The encryption key stays in Android Keystore. In debuggable builds, developers may also provide `MISTRAL_API_KEY` in the app process environment; that value takes precedence over the saved key. Release builds ignore the environment override.

## Logs and measurements

Filter Logcat by `VoiceNotesBenchmark` for audio decode and total processing time. Local Parakeet Ultra details use the `LiteRtParakeet` tag (session preparation, fast path, verification) and one line per transcription:

```text
ultra windows=3 window=30s mel=..ms encode=..ms decode=..ms frames=.. tokens=.. total=..ms
```

```bash
adb logcat -s VoiceNotesBenchmark LiteRtParakeet
```

## Tests

Run the JVM unit tests with:

```bash
./gradlew testDebugUnitTest
```

The hardware-dependent resampler timing probe is excluded from ordinary unit tests. Run it explicitly with `./gradlew :app:resamplerBenchmark`; it reports timing without a host-speed pass/fail threshold. The functional length, split-independence and signal-quality tests remain in the ordinary suite.

The dependency-free `AudioDecoderChecks` instrumentation runner exercises native Opus, Android fallback, invalid input and the duration limit on real devices. Native host tests compare sample counts and PCM against ffmpeg/libopus, including stereo, chained streams and SAF offset/length handling. See [native/README.md](native/README.md) for rebuilding the native library and running device checks.

## Source layout

```text
app/src/main/java/com/local/voicenotes/
├── MainActivity.kt                 Activity: share intents, theme, screen switching
├── ui/                             Compose UI: theme, main screen, settings sheet, history, dialogs
├── AppViewModel.kt                 Thin UI adapter over the app-scoped graph
├── AppGraph.kt                     Process-wide coordinator, backends, history, audio grants
├── VoiceNotesApplication.kt        Owns AppGraph; tracks foreground visibility
├── TranscriptionCoordinator.kt     Import/prepare/transcribe state machine (pure Kotlin)
├── history/                        Transcript history store (JSON file, atomic writes)
├── share/SharedAudio.kt            Auto-transcribe rules for shared audio
├── work/                           Foreground service, notifications, cancel receiver
├── audio/
│   ├── AndroidAudioDecoder.kt      Native Opus fast path / Android fallback, 16 kHz mono
│   ├── NativeOpus.kt               Narrow JNI interface to libopusfile
│   └── OpusPcm.kt                  Bounded, cancellable PCM conversion
├── domain/TranscriptionModels.kt   UI and transcription data types
├── inference/
│   ├── LiteRtParakeetBackend.kt    Parakeet Ultra CPU sessions (mel → encoder → decoder)
│   ├── ParakeetUltraDecoding.kt    Ultra window planning, overlap stitching, greedy TDT loop
│   ├── BundledModelFiles.kt        Bundled Ultra graphs and verified extraction fallback
│   ├── MistralApiKeyStore.kt       Keystore-backed API key storage
│   ├── MistralTranscriptionBackend.kt
│   └── ParakeetVocabulary.kt       Bundled vocabulary and detokenization
└── model/
    ├── ModelRepository.kt          Model import/download records, saved selection, v3 migration
    ├── ModelDownload.kt            Resumable, SHA-256-pinned encoder download
    ├── ModelFileCache.kt           Verified private copies of imported encoders
    └── ParakeetUltraModels.kt      Ultra encoder filename rules
```

`app/src/main/assets/parakeet_vocab.bin` contains the 8192-piece tokenizer vocabulary (identical to the Parakeet TDT v3 vocabulary it was extracted from). `app/src/main/assets/parakeet_ultra_mel_{10,20,30}s.tflite` and `parakeet_ultra_decoder_i8.tflite` are the bundled Parakeet Ultra graphs. Encoders stay outside the repository and APK.

## Main dependencies

| Dependency | Version | Use |
| --- | --- | --- |
| LiteRT | 2.1.6 | TFLite model loading and execution |
| Jetpack Compose BOM | 2024.10.01 | UI libraries |
| DataStore Preferences | 1.1.1 | Imported-model metadata and selection |
| OkHttp | 4.12.0 | Mistral API requests |

## Contributing

Open an issue or send a pull request with a focused change. For model or performance issues, include the phone model, `Build.SOC_MODEL`, the encoder filename, and the relevant Logcat output. Do not commit model weights, API keys, or recordings.

## License and third-party software

The project uses the MIT License. See [LICENSE](LICENSE).

LiteRT uses Apache License 2.0.

The bundled `libvoicenotes_opus.so` links libopus 1.5.2, libogg 1.3.5 and opusfile 0.12 (BSD-style licenses). Their complete notices ship in `app/src/main/assets/licenses/`. The JNI wrapper and reproducible, checksum-pinned build script are in `native/`; normal Gradle builds use the prebuilt arm64 library and do not need an NDK.

The bundled Parakeet Ultra files (`parakeet_ultra_mel_10s.tflite`, `parakeet_ultra_mel_20s.tflite`, `parakeet_ultra_mel_30s.tflite`, `parakeet_ultra_decoder_i8.tflite`) are unmodified copies from [litert-community/parakeet-ultra](https://huggingface.co/litert-community/parakeet-ultra), licensed under [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/). They are converted from [moondream/parakeet-ultra](https://huggingface.co/moondream/parakeet-ultra), which is based on NVIDIA's [parakeet-tdt-0.6b-v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) (CC-BY-4.0). The settings sheet shows the same attribution.
