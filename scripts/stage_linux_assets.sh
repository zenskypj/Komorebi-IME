#!/usr/bin/env bash
#
# Stages the Android asset tree into linux/data so the Linux frontends and tests read one copy.
# The Android assets stay the source of truth; this script only copies and records their hashes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="${ROOT}/app/src/main/assets"
MOZC_ASSETS="${ROOT}/mozc/src/main/assets"
OUT="${ROOT}/linux/data"

mkdir -p "${OUT}"

for name in layouts themes meaning_dictionaries unicode; do
  if [[ -d "${ASSETS}/${name}" ]]; then
    rm -rf "${OUT:?}/${name}"
    cp -a "${ASSETS}/${name}" "${OUT}/${name}"
  fi
done

install -m 644 "${ASSETS}/zenzai/romaji.tsv" "${OUT}/romaji.tsv"
install -m 644 "${ASSETS}/zenzai/flick.tsv" "${OUT}/flick.tsv"
install -m 644 "${ASSETS}/orthographic-corrections.tsv" "${OUT}/orthographic-corrections.tsv"

mkdir -p "${OUT}/dictionaries"
for name in proper-nouns.txt ai-tech-nouns.txt katakana-english.txt; do
  if [[ -f "${MOZC_ASSETS}/dictionaries/${name}" ]]; then
    install -m 644 "${MOZC_ASSETS}/dictionaries/${name}" "${OUT}/dictionaries/${name}"
  fi
done

(
  cd "${OUT}"
  find layouts themes meaning_dictionaries unicode dictionaries romaji.tsv flick.tsv \
    orthographic-corrections.tsv -type f -print0 \
    | sort -z \
    | xargs -0 sha256sum > assets.sha256
)

echo "==> Staged Linux assets in ${OUT}"
