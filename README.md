# Voice Note Transcriber · LiteRT

A high-performance **offline** Android app for transcribing voice notes using **Qualcomm NPU** acceleration via **LiteRT** and the **Parakeet TDT v3** model. Optimized for Snapdragon-powered devices (e.g., SM8650).

---

## ✨ Features

- **Offline Transcription**: No internet required, fully local processing.
- **NPU Acceleration**: Leverages **Qualcomm’s QNN runtime** via **LiteRT** for low-latency inference.
- **Multi-Format Support**: Works with **WhatsApp Opus, MP3, M4A, AAC, WAV** (up to 15 minutes).
- **Multi-Language**: Auto-detect or manually select from **13 languages** (German, English, Spanish, French, Italian, Portuguese, Dutch, Polish, Turkish, Japanese, Korean, Chinese).
- **Model Management**: Import custom **TFLite models** (e.g., `parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8650.tflite`).
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

### 3. Build the App
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
| `com.qualcomm.qti:qnn-runtime` | `2.48.0` | Qualcomm Neural Network runtime. |
| `androidx.compose:compose-bom` | `2024.10.01` | Jetpack Compose UI framework. |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | `1.8.0` | Async transcription support. |
| `androidx.datastore:datastore-preferences` | `1.1.1` | Persist user preferences. |

---

## 🚀 Usage

1. **Open the App**: Launch the app on a supported Android device.
2. **Select Audio File**: Choose a voice note (OGG/Opus, MP3, M4A, AAC, or WAV).
3. **Select Model**: Use the default model or import a custom TFLite model.
4. **Select Language**: Auto-detect or manually select a language.
5. **Start Transcription**: Tap "Transcribe" to process the audio.
6. **View Results**: The transcribed text will appear in the UI.

---

## 📊 Benchmarking

The app logs performance metrics for:
- Audio decode time
- Frontend/encoder/decoder latency
- Total processing time

View logs in **Android Studio Logcat** or via `adb logcat`.

---

## 🔄 Custom Models

To use a custom model:
1. Place the `.tflite` file in the project root or `app/src/main/assets/`.
2. Update `ModelRepository.kt` to recognize the new model.
3. Ensure the model is compatible with **LiteRT + QNN NPU**.

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
