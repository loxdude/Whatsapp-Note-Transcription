# Voice Note Transcriber

Voice Note Transcriber is an Android app that turns audio files into editable text. It can run Parakeet TDT v3 on the phone through LiteRT, or send the original audio file to Mistral's Voxtral Mini API.

The app does not include a Parakeet model. Import a stateful `.tflite` export after installation.

## What it does

- Transcribes OGG and Opus voice notes, MP3, M4A, AAC, and WAV files.
- Runs portable Parakeet models on the CPU without a network connection.
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

Filter Logcat by `VoiceNotesBenchmark` for audio decode and total processing time. Local Parakeet details use the `LiteRtParakeet` tag and include frontend time, encoder time, decoder time, decoder call count, token count, and total inference time.

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
├── MainActivity.kt                 Compose screen and settings sheet
├── AppViewModel.kt                 Selection, progress, cancellation, and backend routing
├── audio/
│   ├── AndroidAudioDecoder.kt      Android codec decoding and 16 kHz mono conversion
│   └── ParakeetFeatureExtractor.kt Five-second Parakeet feature extraction
├── domain/TranscriptionModels.kt   UI and transcription data types
├── inference/
│   ├── LiteRtParakeetBackend.kt    CPU and Qualcomm QNN inference
│   ├── MistralApiKeyStore.kt       Keystore-backed API key storage
│   ├── MistralTranscriptionBackend.kt
│   └── ParakeetAssets.kt
└── model/
    ├── ModelRepository.kt          Model import and saved selection
    └── QualcommModels.kt           Portable-model and chip-target rules
```

`app/src/main/assets/parakeet_frontend.bin` contains the vocabulary and frontend data used by local transcription. Model weights stay outside the repository and APK.

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
