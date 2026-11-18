# Voice Notes

An Android app that turns voice notes into text. It runs [Parakeet Ultra](https://huggingface.co/litert-community/parakeet-ultra) on your phone, so the audio stays on the device. If you add a key, it can use Mistral's API (`voxtral-mini-latest`) instead.

Get the APK from the [releases page](https://github.com/loxdude/Whatsapp-Note-Transcription/releases). It needs Android 12 or newer on an arm64 phone.

## What it does

- Transcribes OGG/Opus (WhatsApp), MP3, M4A, AAC and WAV.
- Detects the language on its own, so there is no language picker.
- Optional Arabic speech model (Cohere Transcribe Arabic) for Arabic voice notes, chosen in Settings.
- Optional on-device translation of any transcript (Gemma 4), German by default.
- Lists your newest WhatsApp voice notes in the app after you grant access to that folder once. You can also share a note to the app from WhatsApp or any other app.
- Keeps the last 100 transcripts. You can search, edit, copy, share and delete them.
- Keeps working in the background, shows its progress in a notification, and can be cancelled.
- Uses Material You colours, a dark mode and an adaptive icon. Unfolded foldables get a two-pane layout.

## Getting started

Local: tap Download once. The app fetches the 665 MB encoder and checks its SHA-256 before using it. If the connection drops, tap Retry and it continues where it stopped. Then pick a note and tap Transcribe. If you already have `parakeet_ultra_encoder_30s_i8.tflite`, you can import it in Settings instead.

Cloud: create a key at [console.mistral.ai](https://console.mistral.ai), add it in Settings and switch to Cloud. The app uploads the original audio file to Mistral. It asks once before the first upload, and you can take that back in Settings. Audio shared from another app is never uploaded until you tap Transcribe. The key is encrypted with AES-GCM using a key held in Android Keystore.

Arabic: in Settings under Local transcription, choose "Arabic (Cohere)" and download the 1.3 GB model. Local transcription then treats every note as Arabic. Switch back to Parakeet at any time; both models can stay installed.

Translation: turn on Translation in Settings, pick Gemma 4 E4B (3.7 GB, better) or E2B (2.6 GB, faster), download it and choose the target language (English, German, Arabic or a custom one). Both models can stay installed; switching only changes which one is used. A Translate button then appears under every transcript. The translation runs on the phone and is saved with the transcript in the history. The first translation after starting the app loads the model, which takes 5 seconds to about a minute depending on the phone.

All downloads are pinned to a fixed revision and checked against their size and SHA-256, and they resume after an interruption.

The app never switches to the cloud by itself. If Local is selected and no model is installed, it asks you to download one.

## How local transcription works

The APK bundles the mel front end and the decoder (about 20 MB), so the encoder is the only large download. Audio is cut into 30 s windows that overlap by 2 s. Each window keeps the words from its own half of the overlap, which avoids most cut-off or doubled words at the seams, though it can still miss or repeat one now and then. Everything runs on the CPU with XNNPACK. Local notes can be up to 15 minutes long.

On a Galaxy Note10+ each 30 s window takes about 2.4 s to encode. A Z Fold 4 does a 77 s note in about 3 s. WhatsApp's Opus files are decoded by a bundled libopus. On these phones that took under a second for notes of a few minutes.

## Accuracy

These are word error rates on 29 German WhatsApp voice notes (23 minutes). The reference transcripts come from Mistral `voxtral-small-transcribe-3`, not from a human, so treat the numbers as relative.

| Pipeline | WER |
| --- | --- |
| Parakeet TDT v3, 5 s chunks (earlier versions of this app) | 18.1% |
| Parakeet Ultra, 10 s windows with overlap | 12.2% |
| Parakeet Ultra, 30 s windows with overlap (current) | 10.4% |

The 10 s windows were less accurate. On the Note10+ they also saved no compute on long notes, because the overlap repeats more audio. Short notes run faster with 10 s windows, but not by enough to justify a second encoder, so the app only supports the 30 s one.

## Building

You need JDK 17 and the Android SDK (compile SDK 35).

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew assembleRelease` builds the minified APK (about 28 MB). For now it is signed with the local debug key. It installs over a debug build only if both were signed on the same machine, and a build from another machine cannot update the published APK. Run the JVM tests with `./gradlew testDebugUnitTest`.

In debuggable builds a `MISTRAL_API_KEY` environment variable overrides the saved key. Release builds ignore it. For timings, run `adb logcat -s VoiceNotesBenchmark LiteRtParakeet`.

The native Opus decoder and the Cohere library ship prebuilt, so normal builds don't need the NDK. [native/README.md](native/README.md) explains how to rebuild them.

## Code layout

```
app/src/main/java/com/local/voicenotes/
  ui/          Compose screens, theme, dialogs
  audio/       native Opus decoder, MediaCodec fallback
  inference/   LiteRT backend, windowing, TDT decoding, Cohere (JNI), Mistral client
  translate/   Gemma translation via LiteRT-LM, chunking
  model/       download, import, model records
  history/     transcript store (atomic JSON writes with a .bak copy)
  share/       share intents, WhatsApp folder listing
  work/        foreground service and notifications
```

Main dependencies: LiteRT 2.1.6, LiteRT-LM 0.19.1, Compose BOM 2024.10.01, DataStore 1.1.1, OkHttp 4.12.0.

Bug reports are welcome. For model problems, include the phone model and the logcat output. Please don't commit model weights, API keys or recordings.

## License

The app is MIT licensed, see [LICENSE](LICENSE).

LiteRT and LiteRT-LM are Apache 2.0. libopus 1.5.2, libogg 1.3.5 and opusfile 0.12 are BSD licensed; CrispASR and ggml are MIT licensed. Their notices are in `app/src/main/assets/licenses/`.

Downloaded models: [Cohere Transcribe Arabic GGUF](https://huggingface.co/cstr/cohere-transcribe-arabic-07-2026-GGUF) and [Gemma 4 E4B](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm) / [E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) for LiteRT-LM are Apache 2.0. They are downloaded on request and not part of the APK. The bundled Parakeet Ultra files are unmodified copies from [litert-community/parakeet-ultra](https://huggingface.co/litert-community/parakeet-ultra) under [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/). They were converted from [moondream/parakeet-ultra](https://huggingface.co/moondream/parakeet-ultra), which is based on NVIDIA's [parakeet-tdt-0.6b-v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3).
