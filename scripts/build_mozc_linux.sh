#!/usr/bin/env bash
#
# Builds the patched Mozc engine as a Linux shared library and stages it for the Rust core.
#
# Outputs:
#   linux/bridge/lib/libkomorebi_mozc.so
#   linux/bridge/include/komorebi_mozc.h
#   linux/data/mozc.data
#   linux/data/runtime.sha256
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -z "${MOZC_DIR:-}" ]]; then
  if [[ -d "${ROOT}/tools/mozc-linux/src" ]]; then
    MOZC_DIR="${ROOT}/tools/mozc-linux"
  else
    MOZC_DIR="${ROOT}/third_party/mozc"
  fi
fi
MOZC_SRC="${MOZC_DIR}/src"
BRIDGE="${ROOT}/linux/bridge"
DATA="${ROOT}/linux/data"

MOZC_COMMIT="2fc7403f231fc201e9d7bdce5fc6f5bca7f534b8"

if [[ ! -d "${MOZC_SRC}" ]]; then
  git clone --no-checkout https://github.com/google/mozc.git "${MOZC_DIR}"
  git -C "${MOZC_DIR}" checkout --detach "${MOZC_COMMIT}"
fi

if [[ -e "${MOZC_DIR}/.git" ]]; then
  if [[ "$(git -C "${MOZC_DIR}" rev-parse HEAD)" != "${MOZC_COMMIT}" ]]; then
    echo "error: Mozc revision differs from ${MOZC_COMMIT}; use MOZC_DIR for a separate checkout" >&2
    exit 1
  fi
else
  echo "warning: ${MOZC_DIR} is not a git checkout; the Mozc revision cannot be verified" >&2
fi

BAZEL="${BAZEL:-$(command -v bazelisk || command -v bazel || true)}"
if [[ -z "${BAZEL}" && -x "${ROOT}/tools/bazelisk" ]]; then
  BAZEL="${ROOT}/tools/bazelisk"
fi
PYTHON="${PYTHON:-python3}"
if [[ -z "${BAZEL_STARTUP_ARGS:-}" && -d "${ROOT}/tools/bazel-root" ]]; then
  BAZEL_STARTUP_ARGS="--output_user_root=${ROOT}/tools/bazel-root"
fi
read -r -a BAZEL_STARTUP <<< "${BAZEL_STARTUP_ARGS:-}"
read -r -a BAZEL_EXTRA <<< "${BAZEL_ARGS:-}"

if [[ -z "${BAZEL}" ]]; then
  echo "error: bazelisk or bazel is required" >&2
  exit 1
fi

cd "${MOZC_DIR}"

PATCH_MARKER="${MOZC_DIR}/.komorebi-linux-patches.sha256"
PATCH_HASH="$(cat "${ROOT}"/patches/[0-9][0-9][0-9][0-9]-*.patch | sha256sum | cut -d' ' -f1)"

if [[ -f "${PATCH_MARKER}" && "$(cat "${PATCH_MARKER}")" == "${PATCH_HASH}" &&
      -f "${MOZC_SRC}/linux/komorebi_mozc.cc" &&
      -f "${MOZC_SRC}/engine/typing_correction_model.cc" ]]; then
  echo "==> Mozc patches already applied"
else
  if [[ -e "${MOZC_DIR}/.git" ]] &&
     [[ -n "$(git -C "${MOZC_DIR}" status --porcelain --untracked-files=no)" ]]; then
    echo "error: ${MOZC_DIR} has tracked changes; reset it before applying patches" >&2
    exit 1
  fi
  for patch in "${ROOT}"/patches/[0-9][0-9][0-9][0-9]-*.patch; do
    [[ -e "${patch}" ]] || continue
    name="$(basename "${patch}")"
    if git -C "${MOZC_DIR}" apply --check "${patch}"; then
      echo "==> Applying ${name}"
      git -C "${MOZC_DIR}" apply "${patch}"
    else
      echo "error: ${name} does not apply to a clean tree" >&2
      exit 1
    fi
  done
  printf '%s\n' "${PATCH_HASH}" > "${PATCH_MARKER}"
fi

if [[ -f "${ROOT}/patches/flick_geometry.inc" ]]; then
  install -m 644 "${ROOT}/patches/flick_geometry.inc" "${MOZC_SRC}/engine/flick_geometry.inc"
fi

"${PYTHON}" "${ROOT}/scripts/gen_system_dictionary.py" > "${MOZC_SRC}/data/dictionary_oss/dictionary10.txt"

cd "${MOZC_SRC}"

echo "==> Building libkomorebi_mozc.so"
"${BAZEL}" "${BAZEL_STARTUP[@]}" build //linux:komorebi_mozc \
  --config oss_linux \
  --config release_build \
  --copt=-DKOMOREBI_INJECTED_PASSWORD \
  --curses=no \
  --show_progress_rate_limit=30 \
  "${BAZEL_EXTRA[@]}"

echo "==> Building mozc.data"
"${BAZEL}" "${BAZEL_STARTUP[@]}" build //data_manager/oss:mozc.data \
  --config oss_linux \
  --config release_build \
  --curses=no \
  --show_progress_rate_limit=30 \
  "${BAZEL_EXTRA[@]}"

echo "==> Staging the engine"
mkdir -p "${BRIDGE}/lib" "${BRIDGE}/include" "${DATA}"
install -m 755 bazel-bin/linux/libkomorebi_mozc.so "${BRIDGE}/lib/libkomorebi_mozc.so"
install -m 644 "${MOZC_SRC}/linux/komorebi_mozc.h" "${BRIDGE}/include/komorebi_mozc.h"
install -m 644 bazel-bin/data_manager/oss/mozc.data "${DATA}/mozc.data"

"${ROOT}/scripts/stage_linux_assets.sh"

(
  cd "${ROOT}"
  sha256sum linux/data/mozc.data linux/bridge/lib/libkomorebi_mozc.so \
    > linux/data/runtime.sha256
)

echo
echo "Done."
ls -lh "${BRIDGE}/lib/libkomorebi_mozc.so" "${DATA}/mozc.data"
