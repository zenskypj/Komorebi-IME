#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
out=${1:?Specify an absolute output directory}
compiler=${CXX:-x86_64-w64-mingw32-clang++}
mkdir -p "$out"
python3 windows/generate_tables.py "$out/tables.inc"
flags=(-std=c++20 -O2 -Wall -Wextra -static "-I$out")
"$compiler" "${flags[@]}" -shared windows/tip.cpp windows/tip.def -lole32 -loleaut32 -ladvapi32 -luuid -o "$out/KomorebiDesktop.dll"
"$compiler" "${flags[@]}" windows/smoke.cpp -luuid -o "$out/Smoke.exe"
