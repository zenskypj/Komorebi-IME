#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
out=${1:?Specify an absolute output directory}
compiler=${CXX:-x86_64-w64-mingw32-clang++}
mkdir -p "$out"
args=("$out/tables.inc")
if [[ -n ${MOZC_DICTIONARY_DIR:-} ]]; then args+=("$MOZC_DICTIONARY_DIR"); fi
python3 windows/generate_tables.py "${args[@]}"
flags=(-std=c++20 -O2 -Wall -Wextra -static "-I$out")
"$compiler" "${flags[@]}" -shared windows/tip.cpp windows/tip.def -lole32 -loleaut32 -ladvapi32 -lgdi32 -luuid -o "$out/KomorebiDesktop.dll"
"$compiler" "${flags[@]}" windows/smoke.cpp -luuid -o "$out/Smoke.exe"
"${WINDRES:-${compiler%clang++}windres}" -i windows/setup.rc -o "$out/setup-res.o" -O coff
"$compiler" "${flags[@]}" -municode -mwindows windows/setup.cpp "$out/setup-res.o" -lole32 -lshell32 -luuid -o "$out/Setup.exe"
"$compiler" "${flags[@]}" -municode -mwindows windows/settings.cpp -ladvapi32 -lgdi32 -o "$out/KomorebiSettings.exe"
