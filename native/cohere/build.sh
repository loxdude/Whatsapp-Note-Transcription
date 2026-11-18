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

if [ ! -e "$SRC/.git" ]; then
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
crisp_status=$(git -C "$SRC" status --porcelain --untracked-files=normal --ignore-submodules=none)
ggml_status=$(git -C "$SRC/ggml" status --porcelain --untracked-files=normal)
if [ -n "$crisp_status" ] || [ -n "$ggml_status" ]; then
    echo "The CrispASR checkout at $SRC has local changes" >&2
    exit 1
fi

: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK r27c}"

# Keep the pinned dependency clean. Snapshot it into a build directory keyed
# by the two upstream revisions and the source patch.
PATCH="$ROOT/native/cohere/crispasr-hybrid-encoder-repack.patch"
RECIPE_ID=$({ printf '%s\n' "$CRISPASR_COMMIT" "$GGML_COMMIT"; cat "$PATCH"; } | sha256sum | cut -d' ' -f1)
PATCHED_SRC="$ROOT/build/native-deps/CrispASR-cohere-$RECIPE_ID"
mkdir -p "$(dirname "$PATCHED_SRC")"
STAGE=$(mktemp -d "${PATCHED_SRC}.XXXXXX")
trap 'rm -rf -- "$STAGE"' EXIT
git -C "$SRC" archive "$CRISPASR_COMMIT" | tar -x -C "$STAGE"
mkdir -p "$STAGE/ggml"
git -C "$SRC/ggml" archive "$GGML_COMMIT" | tar -x -C "$STAGE/ggml"
patch -d "$STAGE" -p1 --batch --forward --fuzz=0 -i "$PATCH"
if [ ! -d "$PATCHED_SRC" ] && mv -T "$STAGE" "$PATCHED_SRC"; then
    trap - EXIT
elif ! diff -qr --no-dereference "$STAGE" "$PATCHED_SRC"; then
    echo "Unexpected changes in staged CrispASR sources at $PATCHED_SRC" >&2
    exit 1
fi
SRC="$PATCHED_SRC"
ANDROID_ARGS=(
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_static
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
  -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections"
  -DCMAKE_CXX_FLAGS="-ffunction-sections -fdata-sections"
)
# 1. CrispASR's Cohere backend, crispasr-core and ggml (CPU only, with OpenMP).
CRISP_BUILD="$ROOT/build/native-crispasr-arm64/$RECIPE_ID"
cmake -S "$SRC" -B "$CRISP_BUILD" "${ANDROID_ARGS[@]}" \
  -DBUILD_SHARED_LIBS=OFF -DCMAKE_DISABLE_FIND_PACKAGE_OpenMP=OFF \
  -DGGML_OPENMP=ON -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 \
  -DGGML_VULKAN=OFF -DGGML_OPENCL=OFF -DGGML_BACKEND_DL=OFF -DGGML_CCACHE=OFF \
  -DCRISPASR_BUILD_TESTS=OFF -DCRISPASR_BUILD_EXAMPLES=OFF -DCRISPASR_BUILD_SERVER=OFF \
  -DCRISPASR_CURL=OFF -DCRISPASR_SDL2=OFF -DCRISPASR_OPUS=OFF -DCRISPASR_OPUS_FETCH=OFF \
  -DCRISPASR_AMR=OFF -DCRISPASR_MEDIA_NDK=OFF -DCRISPASR_CPU_BLAS=OFF -DCRISPASR_MEL_BLAS=OFF \
  -DCRISPASR_C2PA_FETCH=OFF -DCRISPASR_NO_C2PA_NATIVE=ON -DCRISPASR_WITH_ESPEAK_NG=OFF
if ! grep -qx 'GGML_OPENMP_ENABLED:INTERNAL=ON' "$CRISP_BUILD/CMakeCache.txt"; then
    echo "The pinned ggml build did not enable OpenMP" >&2
    exit 1
fi
cmake --build "$CRISP_BUILD" --target cohere -j "${JOBS:-4}"
# 2. The JNI shim, linked into one stripped library (API 31, 16 KB pages).
BUILD="$ROOT/build/native-cohere-arm64"
cmake -S "$ROOT/native/cohere" -B "$BUILD" "${ANDROID_ARGS[@]}" -DCRISPASR_DIR="$SRC" -DCRISPASR_BUILD="$CRISP_BUILD"
cmake --build "$BUILD" --target voicenotes_cohere -j "${JOBS:-4}"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded \
  -o "$OUT/libvoicenotes_cohere.so" "$BUILD/libvoicenotes_cohere.so"
NEEDED=$("$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" \
  -d "$OUT/libvoicenotes_cohere.so" | grep NEEDED)
printf '%s\n' "$NEEDED"
if grep -Eq '\[(libomp|libc[+][+]_shared)\.so\]' <<< "$NEEDED"; then
    echo "The Cohere JNI library must link the runtime libraries statically" >&2
    exit 1
fi
sha256sum "$OUT/libvoicenotes_cohere.so"
