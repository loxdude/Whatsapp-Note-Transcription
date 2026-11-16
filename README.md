# Voice Note Transcriber

Voice Note Transcriber is an Android app that turns audio files into editable text. It can run Parakeet Ultra or Parakeet TDT v3 on the phone through LiteRT, or send the original audio file to Mistral's Voxtral Mini API.

The app does not include an encoder. Import a Parakeet Ultra encoder or a stateful Parakeet TDT v3 `.tflite` export after installation. The small Parakeet Ultra mel front ends and decoder (about 24 MB) are bundled in the APK.

## What it does

- Transcribes OGG and Opus voice notes, MP3, M4A, AAC, and WAV files.
- Runs Parakeet Ultra (30/20/10 s windows) and portable Parakeet TDT v3 models on the CPU without a network connection.
- Runs chip-specific Parakeet exports through LiteRT and Qualcomm QNN.
- Offers `voxtral-mini-latest` as an online alternative.
- Supports automatic language detection and 12 named languages: German, English, Spanish, French, Italian, Portuguese, Dutch, Polish, Turkish, Japanese, Korean, and Chinese.
- Keeps the transcript editable and provides a copy button.
- Shows model loading, audio decoding, transcription progress, elapsed time, cancellation, and errors.
- Remembers the selected audio and model between launches.
- Stores the Mistral API key with an AES-GCM key held by Android Keystore.

Local decoding accepts up to 15 minutes of audio. The Mistral path uploads the selected source file instead of decoding it on the phone.

## Current interface

The main screen now keeps the audio picker, current backend, language, progress, and transcription button in one card. The transcript fills the rest of the screen and can be edited or copied. Model import, language selection, and the Mistral API key are in the settings sheet.

The screen stays awake while a transcription is running. A cancel button appears during active work.

### Share to the app

Voice notes can be shared from WhatsApp or any other app (`ACTION_SEND` with `audio/*` or `application/ogg`). The app loads the audio and starts transcribing as soon as the selected model is ready. A cloud model still asks for upload consent first; without a usable model the audio is only loaded.

Shared files come with a temporary read permission that can end when the app's screen is closed. The audio is read when a transcription starts (local decoding opens it, the Mistral path copies it to a staging file), so the automatic start happens while that permission is valid. Shared audio is not remembered for the next launch.

### History

Every finished transcript is saved on the device (`files/transcript_history.json`, newest 100). Edits to the current transcript are saved to its history entry. The list button on the main screen opens the history, where entries can be copied, shared as text, or deleted. The file is written atomically with a `.bak` copy of the previous version; an unreadable file falls back to the backup or an empty history.

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

A portable CPU model can run on an ARM64 Android device. QNN acceleration requires a compatible Qualcomm chip and an export built for that NPU generation.

## Build and install

Clone your fork or local copy, then run:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Set up local transcription

### Parakeet Ultra (recommended)

[Parakeet Ultra](https://huggingface.co/litert-community/parakeet-ultra) runs on the CPU with long windows and is clearly more accurate than the 5 s v3 export on voice notes (see the table below). Download **one** encoder:

| File | Size | Use |
| --- | --- | --- |
| `parakeet_ultra_encoder_30s_i8.tflite` | 665 MB | Recommended: best accuracy |
| `parakeet_ultra_encoder_20s_i8.tflite` | 641 MB | In between |
| `parakeet_ultra_encoder_10s_i8.tflite` | 616 MB | Slower phones and short notes: less padding per window |

Import it like any other model (`Import .tflite`). The window length comes from the filename; browser suffixes such as ` (1)` are accepted. The settings list shows it as `Parakeet Ultra · CPU · 30 s windows`, and the main screen as `On-device model: Parakeet Ultra`. The `f16` encoders are listed but disabled: they are meant for GPU/NPU, which the app does not support for Ultra yet. Do not import the mel or decoder files; the app bundles them.

How the app runs it (a port of the model card's pipeline):

- Audio up to one window (30 s: 479,840 samples; 20 s: 320,000; 10 s: 160,000) is one zero-padded window. Longer audio is cut into overlapping windows with a hop of window − 2 s (30 s → 28 s, 10 s → 8 s). Each token is kept only by the window whose own region contains its time; the regions meet in the middle of each overlap (the first window keeps from 0, the last up to the end). This avoids the split or repeated words of fixed cuts.
- Each window: bundled mel graph → imported encoder → greedy TDT decoding with the bundled int8 decoder, starting from blank with zero state (token 3 ends the window, ten symbols per frame at most, 3,750 steps at most).
- Everything runs on the CPU (XNNPACK): encoder 4 threads, mel 2, decoder 1. Only one encoder is loaded at a time.
- The encoder goes through the same private copy and verification as v3 models: the first preparation copies it (about 0.6 GB) into app-private storage; later starts reuse the verified copy.
- The bundled graphs are loaded straight from the APK (stored uncompressed). If LiteRT cannot load an asset, the app extracts it once to `noBackupFilesDir/bundled-models`, checks size and SHA-256, and loads it from there.

Measured on a Galaxy Note10+ (Exynos 9825, `benchmark_model`, 4 threads): one encoder call takes 0.68 s for a 10 s window and 2.28 s for a 30 s window (the v3 5 s encoder: about 0.69 s).

### Accuracy on WhatsApp voice notes

Word error rate on 29 real German WhatsApp voice notes (23 minutes), measured with a desktop port of the app's pipeline against Mistral `voxtral-small-transcribe-3` transcripts as the reference (not human ground truth):

| Pipeline | WER |
| --- | --- |
| Parakeet TDT v3, 5 s chunks, decoder state carried across chunks (before this fix) | 30.9% |
| Parakeet TDT v3, 5 s chunks, fresh decoder state per chunk (current) | 18.1% |
| Parakeet Ultra i8, 10 s windows, no overlap | 13.4% |
| Parakeet Ultra i8, 10 s windows, hop 8 s (app setting) | 12.2% |
| Parakeet Ultra i8, 30 s windows, no overlap | 10.7% |
| Parakeet Ultra i8, 30 s windows, hop 25 s | 10.4% |

The app uses hop = window − 2 s (28 s for 30 s windows); the 30 s row with overlap was measured with a 25 s hop.

**v3 fix.** The v3 decoder used to carry its predictor state from one 5 s chunk into the next. Every chunk now starts from a fresh predictor (blank label, zero state), which cut the WER from 30.9% to 18.1% at the same cost.

### Parakeet TDT v3

Download a stateful five-second Parakeet TDT 0.6B v3 export from the [LiteRT Community model repository](https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/tree/main). Copy it to a location that Android's file picker can read.

In the app:

1. Open the settings sheet.
2. Tap `Import .tflite`.
3. Select the stateful model file.
4. Leave that model selected and close settings.
5. Select an audio file and tap `Transcribe`.

The importer checks the TFLite header and the filename. It enables files whose names identify both Parakeet and a stateful export. Models are referenced through Android's Storage Access Framework and are not copied into the APK.

### Choose a model

| Device or chip | File to use | Current status |
| --- | --- | --- |
| Any supported ARM64 device | `parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite` | Portable CPU model. Recommended on Galaxy Z Fold 4. |
| Any supported ARM64 device | `parakeet_tdt_0.6b_v3_5s_f32_stateful.tflite` | Portable CPU model. Uses more memory than i8. |
| Snapdragon 8 Gen 1, SM8450 | Filename ending in `Qualcomm_SM8450.tflite` | QNN export. Device testing is still needed. |
| Snapdragon 8+ Gen 1, SM8475 | SM8450 export targets the same HTP v69 generation | The tested Fold 4 returned blank text. Use portable i8 instead. |
| Snapdragon 8 Gen 2, SM8550 | Filename ending in `Qualcomm_SM8550.tflite` | QNN export. Device testing is still needed. |
| Snapdragon 8 Gen 3, SM8650 | Filename ending in `Qualcomm_SM8650.tflite` | Original QNN target. |
| Snapdragon 8 Elite, SM8750 | Filename ending in `Qualcomm_SM8750.tflite` | Requires its own export. Hardware has not been tested in this project. |

Portable filenames select the CPU backend. Other recognized stateful Parakeet filenames select the QNN backend. The app compares a Qualcomm target in the filename with `Build.SOC_MODEL` and reports a mismatch before use when it can identify one.

The APK includes QNN runtime 2.47.0 and the matching Qualcomm LiteRT dispatch library. QNN 2.48.0 failed when the app closed and reopened a model on the tested Fold 4, so the project remains pinned to 2.47.0. Model creation, inference, and disposal use one worker to prevent disposal during inference.

### Fold 4 measurements

These are single-device measurements from September 9, 2026. They are reference results, not performance claims.

| Test | Result |
| --- | --- |
| SM8450 QNN export, five-second speech excerpt | Blank output, about 35 seconds |
| Portable i8 CPU export, same excerpt | 41 tokens in 0.78 seconds |
| Portable i8 CPU export, 127.8-second voice note | 592 tokens in 13.8 seconds |
| Decode portion of the full note | 1.05 seconds |
| Inference portion of the full note | 12.69 seconds |

The tested SM8450 file matched the publisher's SHA-256 checksum.

## Set up Mistral transcription

1. Create an API key in the [Mistral console](https://console.mistral.ai).
2. Open the app's settings sheet.
3. Tap `Add or change Mistral API key` and save the key.
4. Select `Mistral Voxtral Mini (API)` as the model.
5. Select an audio file and tap `Transcribe`.

This option requires internet access. It uploads the original audio to `https://api.mistral.ai/v1/audio/transcriptions` and requests `voxtral-mini-latest`. If you select a language, the app sends its language code. Auto-detect omits that field.

The app encrypts the saved key with AES-GCM. The encryption key stays in Android Keystore. Developers may also provide `MISTRAL_API_KEY` in the app process environment; that value takes precedence over the saved key.

## Logs and measurements

Filter Logcat by `VoiceNotesBenchmark` for audio decode and total processing time. Local Parakeet details use the `LiteRtParakeet` tag and include frontend time, encoder time, decoder time, decoder call count, token count, and total inference time. Parakeet Ultra logs one line per transcription:

```text
ultra windows=3 window=30s mel=..ms encode=..ms decode=..ms frames=.. tokens=.. total=..ms
```

```bash
adb logcat -s VoiceNotesBenchmark LiteRtParakeet
```

## Tests

Run the local model-selection tests with:

```bash
./gradlew testDebugUnitTest
```

The instrumentation smoke test uses the local model already selected in the installed app. If no local model is selected, JUnit skips the test.

```bash
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.local.voicenotes.inference.QualcommNpuSmokeTest
```

By default, the smoke test opens and closes the model three times, opens it once more, and runs a silent five-second chunk. This catches QNN lifecycle failures.

To transcribe the loudest five-second section of the audio selected in the app, add:

```bash
-Pandroid.testInstrumentationRunnerArguments.selectedAudio=true
```

You can provide files that the app process can read:

```bash
-Pandroid.testInstrumentationRunnerArguments.audioPath=/data/local/tmp/test.wav \
-Pandroid.testInstrumentationRunnerArguments.cpuModelPath=/data/local/tmp/parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite
```

`audioPath` replaces the saved audio selection. `cpuModelPath` tests the supplied portable model without changing the model selected in the app.

## Source layout

```text
app/src/main/java/com/local/voicenotes/
├── MainActivity.kt                 Compose screens (main, settings, history) and share intents
├── AppViewModel.kt                 Thin UI adapter over the app-scoped graph
├── AppGraph.kt                     Process-wide coordinator, backends, history, audio grants
├── VoiceNotesApplication.kt        Owns AppGraph; tracks foreground visibility
├── TranscriptionCoordinator.kt     Import/prepare/transcribe state machine (pure Kotlin)
├── history/                        Transcript history store (JSON file, atomic writes)
├── share/SharedAudio.kt            Auto-transcribe rules for shared audio
├── work/                           Foreground service, notifications, cancel receiver
├── audio/
│   ├── AndroidAudioDecoder.kt      Android codec decoding and 16 kHz mono conversion
│   └── ParakeetFeatureExtractor.kt Five-second Parakeet feature extraction
├── domain/TranscriptionModels.kt   UI and transcription data types
├── inference/
│   ├── LiteRtParakeetBackend.kt    CPU and Qualcomm QNN inference (v3 and Parakeet Ultra sessions)
│   ├── ParakeetV3Decoding.kt       v3 greedy chunk loop (fresh state per chunk)
│   ├── ParakeetUltraDecoding.kt    Ultra window planning, overlap stitching, greedy TDT loop
│   ├── BundledModelFiles.kt        Bundled Ultra graphs and verified extraction fallback
│   ├── MistralApiKeyStore.kt       Keystore-backed API key storage
│   ├── MistralTranscriptionBackend.kt
│   └── ParakeetAssets.kt
└── model/
    ├── ModelRepository.kt          Model import and saved selection
    ├── ParakeetUltraModels.kt      Ultra encoder filename rules
    └── QualcommModels.kt           Portable-model and chip-target rules
```

`app/src/main/assets/parakeet_frontend.bin` contains the vocabulary and frontend data used by local transcription (the Ultra tokenizer's vocabulary is byte-identical). `app/src/main/assets/parakeet_ultra_mel_{10,20,30}s.tflite` and `parakeet_ultra_decoder_i8.tflite` are the bundled Parakeet Ultra graphs. Encoders stay outside the repository and APK.

## Main dependencies

| Dependency | Version | Use |
| --- | --- | --- |
| LiteRT | 2.1.6 | TFLite model loading and execution |
| Qualcomm QNN runtime | 2.47.0 | Snapdragon NPU execution |
| Jetpack Compose BOM | 2024.10.01 | UI libraries |
| DataStore Preferences | 1.1.1 | Imported-model metadata and selection |
| OkHttp | 4.12.0 | Mistral API requests |

## Contributing

Open an issue or send a pull request with a focused change. For QNN fixes, include the phone model, `Build.SOC_MODEL`, model filename, and the relevant Logcat output. Do not commit model weights, API keys, or recordings.

## License and third-party software

The project uses the MIT License. See [LICENSE](LICENSE).

Qualcomm QNN runtime files retain Qualcomm's MIT notice. LiteRT uses Apache License 2.0. The Parakeet TDT model repository lists its model under Apache License 2.0.

The bundled Parakeet Ultra files (`parakeet_ultra_mel_10s.tflite`, `parakeet_ultra_mel_20s.tflite`, `parakeet_ultra_mel_30s.tflite`, `parakeet_ultra_decoder_i8.tflite`) are unmodified copies from [litert-community/parakeet-ultra](https://huggingface.co/litert-community/parakeet-ultra), licensed under [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/). They are converted from [moondream/parakeet-ultra](https://huggingface.co/moondream/parakeet-ultra), which is based on NVIDIA's [parakeet-tdt-0.6b-v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) (CC-BY-4.0). The settings sheet shows the same attribution.
