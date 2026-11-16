# Native Ogg Opus decoder

The normal Android Gradle build packages the checked-in arm64 library at
`app/src/main/jniLibs/arm64-v8a/libvoicenotes_opus.so`. It needs no NDK or network.

## Rebuild

Install CMake, a C compiler, make, curl, tar and Android NDK r27c. Run:

```sh
ANDROID_NDK_HOME=/path/to/android-ndk-r27c bash native/build.sh
```

The script downloads libopus 1.5.2, libogg 1.3.5 and opusfile 0.12 into
`build/native-deps`, verifies pinned SHA-256 checksums before extracting, builds
static dependencies and a stripped arm64 JNI library (API 31, 16 KB page alignment),
then copies only the final library into the Android source tree. `VN_NATIVE_DEPS`
overrides the source cache directory. `JOBS` defaults to 4.

The original BSD-style license notices ship inside the APK at `assets/licenses/`.

## Decode behavior

- libopusfile handles Ogg pages/CRC, packet continuation, chained logical streams,
  pre-skip, output gain and end-granule trimming; there is no custom Ogg parser.
- Native code duplicates the provider fd, respects the asset offset/length and
  uses positional reads without changing the caller's file position. Nonseekable
  descriptors are rejected to the Android fallback. No URI/path is passed to C.
- PCM leaves JNI as small chunks of 48 kHz mono floats (stereo is averaged).
  Existing Kotlin bandlimited resampling produces 16 kHz mono for the model.
- The 15-minute bound is checked against the decoded frame count before decoding
  when the total is known, and against actual emitted frames otherwise.
- Cancellation is checked inside native read/seek callbacks (including opening and
  chained-stream metadata parsing), as well as between PCM chunks. Each native open/read
  call has a 2-second processing deadline, 2 MiB read-work budget and 8,192 callback-check
  cap. Confirmed Opus input length is capped at 64 MiB and aggregate reads at 128 MiB;
  bounded partial opening identifies the format first, so large WAV/MP3 still reach Android. This bounds giant
  continued tags/non-audio scanning before metadata allocations can grow without limit.
  Callback checks cannot interrupt an already-blocked provider/kernel `pread` itself.
  `use` closes the decoder/fd on success, cancellation, limit rejection or error.
  Progress is throttled.
- A failed native open or corrupt-packet error discards all native output and
  retries with Android MediaCodec. Other formats keep the existing decoder.
  Cancellation, parser safety limits and the duration-limit exception are never treated
  as fallbacks. Native pointer handles and error status travel separately, since Android
  tagged pointers can be negative Java `long` values.
- R8 keeps the JNI class/method names. Only arm64 is supported, matching the app.

## Tests

Host tests require Python 3 and ffmpeg with libopus support:

```sh
bash native/build.sh host
```

These check reference PCM/sample count, pre-skip/end trim, stereo mix, chained
streams, subfile offsets/lengths, fd ownership, pipe fallback, invalid ranges and
failed-open fd leaks, oversized continued tags and cancellation during opening/reading. JVM tests (`./gradlew testDebugUnitTest`) cover resampling,
known/unknown length limits, cancellation, native errors and progress.

For device checks, build the app and test APK:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL push YOUR_FIXTURE_DIR /data/local/tmp/decoder-fixtures
adb -s SERIAL shell run-as com.local.voicenotes.litert mkdir -p files/decoder-fixtures
adb -s SERIAL shell 'run-as com.local.voicenotes.litert sh -c "cp /data/local/tmp/decoder-fixtures/* files/decoder-fixtures/"'
adb -s SERIAL shell am instrument -w -e dumpPcm true com.local.voicenotes.litert.test/com.local.voicenotes.audio.AudioDecoderChecks
```

Use real `.opus` notes plus WAV/MP3 fallback fixtures. Filenames starting with
`reject-` must fail; `reject-too-long` must report the 15-minute limit; `reject-work-limit` must report parser safety limits. No fixture
recordings or transcripts belong in git. The runner reports per-file timings and
writes JSON to app-private `cache/decoder-checks/results.json`; optional little-endian
float PCM dumps permit full-corpus ASR regression testing without UI automation.
Instrumentation stops the target app process; don't run UI tests concurrently.

## Measured on devices

One sample of a 121.58 s German WhatsApp note, using the 30 s Ultra encoder:

| Phone | Old Android decode | Native decode | Old total | New total |
| --- | ---: | ---: | ---: | ---: |
| OnePlus 12 | 7.48 s | 0.44 s | 11.75 s | 4.61 s |
| Galaxy Z Fold 4 | 4.69 s | 0.45 s | 9.65 s | 5.44 s |
| Galaxy Note10+ | 13.11 s | 0.48 s | 26.16 s | 13.77 s |

App logcat times, not UI polling; individual samples, not repeated benchmark means.
All three phones passed 35 decoder fixtures (29 real notes, WAV, MP3, invalid file,
over-limit Opus, oversized continued tags, and a 14-minute 80.64 MB WAV). The large WAV
takes the Android route and produces exactly 13,440,000 output samples. The runner asserts the native route for
every valid Opus note, the Android route for WAV/MP3, and cancellation during native open. Native host decoding was also checked against ffmpeg's
libopus decoder on all 29 real notes. PCM/transcripts can differ slightly from
Android's codec; do not assume bit-identical ASR output.
