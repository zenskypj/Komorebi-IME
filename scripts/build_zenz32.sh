#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SOURCE="${ZENZ_LLAMA_SOURCE:-${ROOT}/third_party/zenz32-llama}"
REV=66afb885a8ddcc0511798aec9fb5a01017941018
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${ROOT}/tools/android-sdk}}"
NDK="${ANDROID_NDK_HOME:-${SDK}/ndk/28.2.13676358}"
if [[ ! -d "${SOURCE}/.git" ]]; then
  git clone --no-checkout https://github.com/azooKey/llama.cpp.git "${SOURCE}"
fi
git -C "${SOURCE}" fetch --depth=1 origin "${REV}"
git -C "${SOURCE}" checkout --detach "${REV}"
for abi in ${ZENZ_ABIS:-arm64-v8a}; do
  cmake -S "${ROOT}/native/zenz32" -B "${ROOT}/build/zenz32-${abi}" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DZENZ_LLAMA_SOURCE="${SOURCE}" \
    -DCMAKE_TOOLCHAIN_FILE="${NDK}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${abi}" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static
  cmake --build "${ROOT}/build/zenz32-${abi}" --parallel "${JOBS:-4}"
  mkdir -p "${ROOT}/app/src/main/jniLibs/${abi}"
  cp "${ROOT}/build/zenz32-${abi}/libkomorebi_zenz32.so" "${ROOT}/app/src/main/jniLibs/${abi}/"
  "${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "${ROOT}/app/src/main/jniLibs/${abi}/libkomorebi_zenz32.so"
done
