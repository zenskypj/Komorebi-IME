#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
: "${LLAMA_SOURCE:?Set LLAMA_SOURCE to llama.cpp revision 66afb885a8ddcc0511798aec9fb5a01017941018}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME}"
revision="$(git -C "$LLAMA_SOURCE" rev-parse HEAD)"
if [[ "$revision" != "66afb885a8ddcc0511798aec9fb5a01017941018" ]]; then
  printf 'Unexpected llama.cpp revision: %s\n' "$revision" >&2
  exit 1
fi
cmake -S "$root/native/proofread" -B "$root/build/proofread-arm64" -G Ninja -DLLAMA_SOURCE="$LLAMA_SOURCE" -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release
cmake --build "$root/build/proofread-arm64" --parallel "${JOBS:-4}"
mkdir -p "$root/app/src/main/jniLibs/arm64-v8a"
cp "$root/build/proofread-arm64/libkomorebi_proofread.so" "$root/app/src/main/jniLibs/arm64-v8a/"
