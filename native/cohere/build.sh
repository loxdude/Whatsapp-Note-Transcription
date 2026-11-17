#!/usr/bin/env bash
# Explicit, reproducible rebuild of libvoicenotes_cohere.so (Cohere Transcribe Arabic via
# CrispASR/ggml). Gradle uses the checked-in arm64 .so; no NDK needed for normal builds.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CRISPASR_URL=https://github.com/CrispStrobe/CrispASR
CRISPASR_COMMIT=bae18989da572cacfb8ab7a186f086ebe0932e2a
# ggml is a submodule of CrispASR; its commit is fixed by the CrispASR commit above.
GGML_COMMIT=1b972bb754aa5f83e2d872ccf37a335717f6658d
SRC="${CRISPASR_SRC:-$ROOT/build/native-deps/CrispASR}"

if [ ! -d "$SRC/.git" ]; then
    mkdir -p "$SRC"
    git -C "$SRC" init -q
    git -C "$SRC" fetch -q --depth 1 "$CRISPASR_URL" "$CRISPASR_COMMIT"
    git -C "$SRC" checkout -q FETCH_HEAD
fi
if [ ! -f "$SRC/ggml/CMakeLists.txt" ]; then
    git -C "$SRC" submodule update -q --init --depth 1 ggml
fi
check() {
    local name=$1 actual=$2 expected=$3
    if [ "$actual" != "$expected" ]; then
        echo "$name is at $actual, expected $expected" >&2
        exit 1
    fi
}
check CrispASR "$(git -C "$SRC" rev-parse HEAD)" "$CRISPASR_COMMIT"
check ggml "$(git -C "$SRC/ggml" rev-parse HEAD)" "$GGML_COMMIT"
if ! git -C "$SRC" diff --quiet HEAD -- || ! git -C "$SRC/ggml" diff --quiet HEAD --; then
    echo "The CrispASR checkout at $SRC has local changes" >&2
    exit 1
fi

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r27c}"
ANDROID_ARGS=(
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_static
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
  -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections"
  -DCMAKE_CXX_FLAGS="-ffunction-sections -fdata-sections"
)
# 1. CrispASR's Cohere backend, crispasr-core and ggml (CPU only, no OpenMP, no extras).
CRISP_BUILD="$ROOT/build/native-crispasr-arm64"
cmake -S "$SRC" -B "$CRISP_BUILD" "${ANDROID_ARGS[@]}" \
  -DBUILD_SHARED_LIBS=OFF -DCMAKE_DISABLE_FIND_PACKAGE_OpenMP=ON \
  -DGGML_OPENMP=OFF -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 \
  -DGGML_VULKAN=OFF -DGGML_OPENCL=OFF -DGGML_BACKEND_DL=OFF -DGGML_CCACHE=OFF \
  -DCRISPASR_BUILD_TESTS=OFF -DCRISPASR_BUILD_EXAMPLES=OFF -DCRISPASR_BUILD_SERVER=OFF \
  -DCRISPASR_CURL=OFF -DCRISPASR_SDL2=OFF -DCRISPASR_OPUS=OFF -DCRISPASR_OPUS_FETCH=OFF \
  -DCRISPASR_AMR=OFF -DCRISPASR_MEDIA_NDK=OFF -DCRISPASR_CPU_BLAS=OFF -DCRISPASR_MEL_BLAS=OFF \
  -DCRISPASR_C2PA_FETCH=OFF -DCRISPASR_NO_C2PA_NATIVE=ON -DCRISPASR_WITH_ESPEAK_NG=OFF
cmake --build "$CRISP_BUILD" --target cohere -j "${JOBS:-4}"
# 2. The JNI shim, linked into one stripped library (API 31, 16 KB pages).
BUILD="$ROOT/build/native-cohere-arm64"
cmake -S "$ROOT/native/cohere" -B "$BUILD" "${ANDROID_ARGS[@]}" -DCRISPASR_DIR="$SRC" -DCRISPASR_BUILD="$CRISP_BUILD"
cmake --build "$BUILD" --target voicenotes_cohere -j "${JOBS:-4}"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded \
  -o "$OUT/libvoicenotes_cohere.so" "$BUILD/libvoicenotes_cohere.so"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -d "$OUT/libvoicenotes_cohere.so" | grep NEEDED
sha256sum "$OUT/libvoicenotes_cohere.so"
