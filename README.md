# Voice Note Transcriber · LiteRT

An **offline** Android app for transcribing voice notes using **LiteRT** and **Parakeet TDT v3**, with portable CPU models and chip-specific Qualcomm NPU exports.

---

## ✨ Features

- **Dual Transcription Backends**: Choose between offline **LiteRT/Parakeet** or online **Mistral API** transcription.
- **Mistral Voxtral Mini (API)**: Cloud-based transcription via the [Mistral AI API](https://mistral.ai) using the **voxtral-mini-latest** model. Requires an API key and internet connection.
- **Offline Transcription**: No internet required, fully local processing.
- **NPU Acceleration**: Leverages **Qualcomm’s QNN runtime** via **LiteRT** for low-latency inference.
- **Multi-Format Support**: Works with **WhatsApp Opus, MP3, M4A, AAC, WAV** (up to 15 minutes).
- **Multi-Language**: Auto-detect or manually select from **13 languages** (German, English, Spanish, French, Italian, Portuguese, Dutch, Polish, Turkish, Japanese, Korean, Chinese).
- **Model Management**: Import custom **TFLite models** (e.g., `parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8650.tflite`).
- **Secure API Key Storage**: Mistral API key is encrypted with an **Android Keystore**-backed AES-GCM key.
- **Benchmarking**: Logs audio decode time, inference latency, and total processing time.
- **Modern UI**: Built with **Jetpack Compose** and **Material 3**.

---

## 📋 Requirements

- **Android SDK**: `minSdk=31`, `targetSdk=35`
- **ABI**: `arm64-v8a` (64-bit ARM)
- **Hardware**: Qualcomm Snapdragon NPU (e.g., SM8650)
- **Build Tools**: Gradle 8.6.1, Kotlin 2.3.0

---

## 🛠 Setup

### 1. Clone the Repository
```bash
git clone https://github.com/your-repo/Transcriber-android-litert.git
cd Transcriber-android-litert
```

### 2. Add the Default Model
Place the **Parakeet TDT v3** model anywhere on your phone (where the android file selector has access to):
```
parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8650.tflite
```
*Download the model from [Huggingface](https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/tree/main)*

Choose the **stateful 5s f32** export for your phone:

| Phone / chipset | Export suffix | Validation |
|---|---|---|
| Galaxy Z Fold 4 / Snapdragon 8+ Gen 1 (SM8475) | `parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite` (CPU) | Speech verified; recommended for Fold 4 |
| Snapdragon 8 Gen 1 (SM8450) | `Qualcomm_SM8450.tflite` | Requires device testing |
| Snapdragon 8 Gen 2 (SM8550) | `Qualcomm_SM8550.tflite` | Requires device testing |
| Snapdragon 8 Gen 3 (SM8650) | `Qualcomm_SM8650.tflite` | Original target |
| Snapdragon 8 Elite (SM8750) | `Qualcomm_SM8750.tflite` | Runtime included; requires device testing |

The portable `parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite` and
`parakeet_tdt_0.6b_v3_5s_f32_stateful.tflite` exports automatically use CPU.
Use the quantized i8 model on Fold 4. CPU processing stays entirely offline;
it does not call the Mistral API. Chip-specific exports use NPU.

The same APK contains the QNN runtimes for these generations; import the appropriate
model through the app. Models are not bundled in the APK. SM8450 and SM8475 share
HTP v69. LiteRT 2.1.6's built-in device allowlist omits both, so the app explicitly
allows those Qualcomm chips while retaining native QNN model validation. The
Snapdragon 8 Elite uses a different NPU generation and needs its own export.
See [LiteRT Qualcomm support](https://developers.google.com/edge/litert/next/qualcomm).

Fold 4 testing (2026-09-09): the SM8450 NPU export loads but returns blank output
for both a voice-note excerpt and a public speech fixture, taking about 35 seconds
per 5-second chunk. Its SHA-256 matches the publisher. The portable i8 CPU export
transcribed the same voice-note excerpt in 0.78 seconds (41 tokens). These timings
are individual measurements, not guarantees. SM8750 hardware has not been tested.
The full 127.8-second voice note completed in 13.8 seconds on Fold 4 with app
0.3.0 (1.05 seconds audio decoding, 12.69 seconds inference, 592 tokens).
The device test `QualcommNpuSmokeTest` uses the selected local model and skips when
none is selected.

### 3. (Optional) Add a Mistral API Key

To use the **Mistral Voxtral Mini** transcription backend:

1. Get an API key from [console.mistral.ai](https://console.mistral.ai).
2. Open the app, tap the **⚙ Settings** icon, and enter your key.
3. The key is stored encrypted on-device via **Android Keystore** (AES-GCM).

*Note: Mistral transcription requires an internet connection. Local transcription works fully offline.*

### 4. Build the App
```bash
./gradlew assembleDebug
```

### 4. Run on Device
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📂 Project Structure

```
Transcriber-android-litert/
├── app/
│   ├── src/main/
│   │   ├── java/com/local/voicenotes/
│   │   │   ├── MainActivity.kt          # UI (Jetpack Compose)
│   │   │   ├── AppViewModel.kt          # Business logic
│   │   │   ├── inference/
│   │   │   │   ├── LiteRtParakeetBackend.kt  # NPU inference engine
│   │   │   │   ├── ParakeetAssets.kt         # Asset loader
│   │   │   │   └── ...
│   │   │   ├── audio/                     # Audio decoding
│   │   │   ├── domain/                    # Data models
│   │   │   └── model/                     # Model management
│   │   ├── assets/parakeet_frontend.bin  # Prepackaged Parakeet assets
│   │   └── res/                          # UI resources
│   └── build.gradle.kts                  # App dependencies
├── build.gradle.kts                      # Project-level Gradle
├── settings.gradle.kts                   # Project settings
├── parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8650.tflite  # Default model
├── tools/                                # Utility scripts
```

---

## 🔧 Key Components

| **Component** | **File** | **Description** |
|--------------|----------|----------------|
| **UI** | [`MainActivity.kt`](app/src/main/java/com/local/voicenotes/MainActivity.kt) | Jetpack Compose UI with Material 3. |
| **Business Logic** | [`AppViewModel.kt`](app/src/main/java/com/local/voicenotes/AppViewModel.kt) | Orchestrates transcription workflow. |
| **Inference Engine** | [`LiteRtParakeetBackend.kt`](app/src/main/java/com/local/voicenotes/inference/LiteRtParakeetBackend.kt) | Handles NPU-accelerated inference via LiteRT + QNN. |
| **Asset Loader** | [`ParakeetAssets.kt`](app/src/main/java/com/local/voicenotes/inference/ParakeetAssets.kt) | Loads Parakeet frontend assets (vocabulary, mel filterbank). |
| **Audio Decoding** | [`ParakeetFeatureExtractor.kt`](app/src/main/java/com/local/voicenotes/audio/ParakeetFeatureExtractor.kt) | Extracts audio features (16kHz mono PCM). |
| **Model Management** | [`ModelRepository.kt`](app/src/main/java/com/local/voicenotes/model/ModelRepository.kt) | Manages imported TFLite models. |

---

## 📦 Dependencies

| **Library** | **Version** | **Purpose** |
|------------|------------|------------|
| `com.google.ai.edge.litert:litert` | `2.1.6` | LiteRT runtime for NPU acceleration. |
| `com.qualcomm.qti:qnn-runtime` | `2.47.0` | Qualcomm Neural Network runtime, matched to the bundled dispatch library. |
| `androidx.compose:compose-bom` | `2024.10.01` | Jetpack Compose UI framework. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | `1.8.0` | Async transcription support. |
| `androidx.datastore:datastore-preferences` | `1.1.1` | Persist user preferences. |

---

## 🚀 Usage

1. **Open the App**: Launch the app on a supported Android device.
2. **Select Audio File**: Choose a voice note (OGG/Opus, MP3, M4A, AAC, or WAV).
3. **Select Model**: Use the default Parakeet model for offline transcription, or select **Mistral Voxtral Mini (API)** for cloud-based transcription (requires API key).
4. **Select Language**: Auto-detect or manually select a language.
5. **Start Transcription**: Tap "Transcribe" to process the audio.
6. **View Results**: The transcribed text will appear in the UI.

### Mistral API Transcription

1. Obtain an API key from [console.mistral.ai](https://console.mistral.ai).
2. Open the app → tap the **⚙ Settings** icon → enter your Mistral API key.
3. In the model dropdown, select **Mistral Voxtral Mini (API)**.
4. Select your audio file and language, then tap **Transcribe**.
5. The app sends the original audio file (e.g., MP3, Opus) directly to the Mistral API — no local audio decoding needed.

*The Mistral API key is encrypted with AES-GCM via the Android Keystore. The app also supports a `MISTRAL_API_KEY` environment variable as a fallback.*

---

## 📊 Benchmarking

The app logs performance metrics for:
- Audio decode time
- Frontend/encoder/decoder latency
- Total processing time

View logs in **Android Studio Logcat** or via `adb logcat`.

### NPU reload regression

QNN 2.48.0 reproducibly fails on the Fold 4 when a loaded model is closed and
reopened in the same app process: DSP teardown errors are followed by
`Failed to compile model`. QNN is pinned to 2.47.0, matching the bundled dispatch
library. Native creation, inference and disposal also run on one worker so disposal
cannot race an active inference call.

`QualcommNpuSmokeTest` exercises three close/reload cycles before inference.
Its optional instrumentation argument `selectedAudio=true` additionally transcribes
the highest-energy five-second excerpt of the selected audio locally and requires
a nonempty result. `audioPath` supplies a test WAV instead; `cpuModelPath` supplies
an app-readable portable model for comparison without changing the selection.

---

## 🔄 Custom Models

To use a custom model:
1. Download the stateful `.tflite` export matching your phone to the phone.
2. Import it using the app's model file picker; no source changes are needed.
3. Select it and wait for NPU initialization. Model notes identify the export target
   and device; initialization errors include the chip and native failure reason.

---

## 🤝 Contributing

Do whatever you want, this repo is not that serious.

---


## 🙌 Acknowledgments

- **LiteRT**: Google's runtime for on-device ML.
- **QNN Runtime**: Qualcomm's Neural Network runtime for Snapdragon NPUs.
- **Parakeet TDT v3**: Open-source speech recognition model.
- **Jetpack Compose**: Modern Android UI toolkit.

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

### Third-Party Licenses
- **Qualcomm QNN Runtime**: MIT License (Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.)
- **LiteRT**: Apache License 2.0
- **Parakeet TDT Model**: Apache License 2.0
