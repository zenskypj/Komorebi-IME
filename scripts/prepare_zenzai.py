#!/usr/bin/env python3
"""Stage an explicitly supplied Zenzai model and its compatible MIT llama.cpp runtime."""
import argparse
import hashlib
import json
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="Directory containing zenzai-plus-ft-f16.gguf")
    args = parser.parse_args()
    source = args.directory.resolve()
    model = source / "zenzai-plus-ft-f16.gguf"
    runtime = source / "zenzai-plus-Hazkey-Fcitx5/hazkey-server/llama.cpp"
    if not model.is_file() or not (runtime / "include/llama.h").is_file():
        parser.error("Model or compatible llama.cpp source is missing")
    if model.open("rb").read(4) != b"GGUF":
        parser.error("Not a GGUF model")
    digest = hashlib.file_digest(model.open("rb"), "sha256").hexdigest()
    if digest != "928fc0f32dbafb4cd2b26275d1b40cefeb47aa27cd7b7144f92924363a7ec8f4":
        parser.error("Model checksum differs from the upstream zenzai-plus model")
    if not (source / "MODEL_CARD.draft.md").is_file():
        parser.error("MODEL_CARD.draft.md is required for attribution")
    vendor = ROOT / "third_party/zenzai-llama"
    shutil.copytree(runtime, vendor, dirs_exist_ok=True,
                    ignore=lambda directory, names: [name for name in names if
                        name in (".git", "__pycache__") or
                        (name.startswith("build") and (Path(directory) / name).is_dir())])
    for patch in sorted((ROOT / "patches").glob("zenzai-*.patch")):
        subprocess.run(["patch", "-d", str(vendor), "-p1", "-i", str(patch)], check=True)
    assets = ROOT / "app/src/zenzai/assets/zenzai"
    assets.mkdir(parents=True, exist_ok=True)
    shutil.copy2(model, assets / model.name)
    digest = hashlib.file_digest(model.open("rb"), "sha256").hexdigest()
    (assets / "model.json").write_text(json.dumps({
        "file": model.name, "sha256": digest, "size": model.stat().st_size,
        "format": "zenz-v2", "license": "CC-BY-SA-4.0",
    }, indent=2) + "\n")
    shutil.copy2(runtime / "LICENSE", assets / "llama-LICENSE.txt")
    shutil.copy2(source / "MODEL_CARD.draft.md", assets / "MODEL_CARD.md")
    legal = ROOT / "app/src/zenzai/assets/legal"
    legal.mkdir(parents=True, exist_ok=True)
    shutil.copy2(runtime / "LICENSE", legal / "ZENZAI_RUNTIME_LICENSE.txt")
    shutil.copy2(source / "MODEL_CARD.draft.md", legal / "ZENZAI_MODEL_CARD.txt")
    print(f"Staged {model.name} ({model.stat().st_size} bytes), SHA-256 {digest}")


if __name__ == "__main__":
    main()
