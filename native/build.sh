#!/usr/bin/env bash
# Explicit, reproducible native rebuild. Gradle uses the checked-in arm64 .so; no NDK needed.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
DEPS="${VN_NATIVE_DEPS:-$ROOT/build/native-deps}"
mkdir -p "$DEPS"
fetch() {
    local name=$1 url=$2 hash=$3
    if [ ! -f "$DEPS/$name" ]; then curl -fL --retry 2 -o "$DEPS/$name.part" "$url"; mv "$DEPS/$name.part" "$DEPS/$name"; fi
    printf '%s  %s\n' "$hash" "$DEPS/$name" | sha256sum -c -
    tar xf "$DEPS/$name" -C "$DEPS"
}
fetch opus-1.5.2.tar.gz https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz 65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
fetch libogg-1.3.5.tar.xz https://downloads.xiph.org/releases/ogg/libogg-1.3.5.tar.xz c4d91be36fc8e54deae7575241e03f4211eb102afb3fc0775fbbc1b740016705
fetch opusfile-0.12.tar.gz https://downloads.xiph.org/releases/opus/opusfile-0.12.tar.gz 118d8601c12dd6a44f52423e68ca9083cc9f2bfe72da7a8c1acb22a80ae3550b
if [ "${1:-}" = host ]; then
    BUILD="$ROOT/build/native-host"
    cmake -S "$ROOT/native" -B "$BUILD" -DDEPS="$DEPS" -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5
    cmake --build "$BUILD" -j "${JOBS:-4}"
    python3 "$ROOT/native/tests/test_decoder.py" "$BUILD/libvoicenotes_opus.so"
else
    : "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r27c}"
    BUILD="$ROOT/build/native-arm64"
    cmake -S "$ROOT/native" -B "$BUILD" -DDEPS="$DEPS" -DCMAKE_BUILD_TYPE=Release -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
      -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
    cmake --build "$BUILD" -j "${JOBS:-4}"
    mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a"
    "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$BUILD/libvoicenotes_opus.so"
    cp "$BUILD/libvoicenotes_opus.so" "$ROOT/app/src/main/jniLibs/arm64-v8a/"
    sha256sum "$ROOT/app/src/main/jniLibs/arm64-v8a/libvoicenotes_opus.so"
fi
