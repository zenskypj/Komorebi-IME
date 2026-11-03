#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${ROOT}/tools/android-sdk}}"
NDK="${ANDROID_NDK_HOME:-${SDK}/ndk/28.2.13676358}"
cmake -S "${ROOT}/native/zenzai" -B "${ROOT}/build/zenzai-android" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="${NDK}/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
  -DZINNA_LLAMA_SOURCE="${ROOT}/third_party/zenzai-llama"
cmake --build "${ROOT}/build/zenzai-android" --parallel "${JOBS:-4}"
mkdir -p "${ROOT}/app/src/zenzai/jniLibs/arm64-v8a"
cp "${ROOT}/build/zenzai-android/libzinna_zenzai.so" \
  "${ROOT}/app/src/zenzai/jniLibs/arm64-v8a/"
