#!/usr/bin/env python3
"""Build complete, independently selectable datasets and the APK's trusted catalog.

Run fetch_sources.py, convert_neologd.py (main + variants), convert_skk.py first.
Uses an isolated Mozc checkout archived from 3f235b4; never edits third_party/mozc
or the APK's standard data. No conversion benchmarks are run by this command.
"""
import argparse
import gzip
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
MOZC_COMMIT = "3f235b4eb6fcff7d14ef5f0fb8ee56de7ee4c732"
BASE_SHA = "fbb9a23419d677a33968145cfe1979dac6e7d3c28712d469854b4707a5965ed1"
RELEASE = "v0.3.0-rc.33-dev"
GROUPS = [
    ("neologd-main", "NEologd 新語・固有名詞", "neologd-main", "Apache-2.0", "komorebi-neologd-abc61e33-v1"),
    ("neologd-variants", "NEologd 表記ゆれ・活用語", "neologd-variants", "Apache-2.0", "komorebi-neologd-variants-v1"),
    ("skk-gpl", "SKK 総合・人名・地名・専門語", "skk/gpl", "GPL-2.0（原典の条件を保持）", "komorebi-skk-gpl-v1"),
    ("skk-cc", "SKK 英和（edict・edict2）", "skk/cc", "CC-BY-SA-3.0", "komorebi-skk-cc-v1"),
    ("skk-unicode", "SKK 絵文字・異体字", "skk/unicode", "Unicode License", "komorebi-skk-unicode-v1"),
    ("skk-permissive", "SKK 沖縄・郵便番号・異体字・pubdic+", "skk/permissive", "Public Domain / Pubdic+", "komorebi-skk-permissive-v1"),
]


def sha(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def runtime_fingerprint():
    paths = ["src/main/assets/mozc.data"] + [f"src/main/jniLibs/{abi}/libmozc.so" for abi in (
        "arm64-v8a", "armeabi-v7a", "x86", "x86_64")]
    identity = "\n".join(f"{p}:{sha(ROOT / 'mozc' / p)}" for p in paths)
    return hashlib.sha256(identity.encode()).hexdigest()


def notice(source_root, group, name, license_name):
    text = f"{name}\nライセンス: {license_name}\n\n"
    text += ("標準Mozcと追加語彙を結合したデータ全体を切り替えます。別データセットとの同時使用はできません。\n"
             "変換品質・端末動作の追加検証は実施していない試験版です。\n"
             "NEologdは全12 seedを新語・固有名詞と表記ゆれ・活用語の2データセットで提供します。\n"
             "SKKは公式個別辞書も全件処理します。動的Lisp・数値テンプレート・活用が未解決の行は生成報告へ保持し、候補にはしません。\n"
             "誤登録検査用wrong/wrong.annotated/noregistは候補にしません。\n"
             "品詞情報のない静的語は名詞を基本とし、人名・地名などは原典の種類に応じて扱います。\n"
             "個人辞書・学習履歴を大量辞書の保存先として使用しません。\n\n")
    text += f"Mozc source: https://github.com/google/mozc/tree/{MOZC_COMMIT}\n"
    if group.startswith("neologd"):
        text += "NEologd source: https://github.com/neologd/mecab-ipadic-neologd/tree/abc61e33d8be3d0ead202e6b1df064c72d5ccf11\n\n"
        text += (source_root / "neologd/COPYING").read_text()
        apache = ROOT / "app/src/main/assets/legal/KARUKAN_LICENSE_APACHE.txt"
        text += "\n\n" + apache.read_text()
    else:
        from convert_skk import decode, PD, CC, UNICODE, NON_LEXICONS
        text += "SKK source: https://github.com/skk-dev/dict/tree/0a164e6b990c5eb5b59eb7d8789f08865dc2f644\n\n"
        for path in sorted((source_root / "skk").rglob("SKK-JISYO.*")):
            if path.suffix == ".header" or path.name in NON_LEXICONS:
                continue
            selected = "skk-cc" if path.name in CC else "skk-unicode" if path.name in UNICODE else "skk-permissive" if path.name in PD else "skk-gpl"
            if group != selected:
                continue
            decoded, _ = decode(path)
            header = []
            for line in decoded.splitlines():
                if not line.startswith(";"):
                    break
                header.append(line)
            text += f"\n--- {path.relative_to(source_root / 'skk')} ---\n" + "\n".join(header) + "\n"
        if group == "skk-gpl":
            text += "\n" + (ROOT / "scripts/extra_dictionary/licenses/GPL-2.txt").read_text()
        if group == "skk-cc":
            text += "\n" + (ROOT / "scripts/extra_dictionary/licenses/CC-BY-SA-3.0.txt").read_text()
    for filename in ("MOZC_LICENSE.txt", "MOZC_DICTIONARY_LICENSES.txt"):
        text += f"\n\n--- {filename} ---\n" + (ROOT / "app/src/main/assets/legal" / filename).read_text()
    text += f"\n\n再生成用ソース・全件処理報告: https://github.com/zenskypj/Komorebi-IME/releases/tag/{RELEASE}\n"
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=pathlib.Path, default=ROOT / "build/extra-dictionary")
    parser.add_argument("--only", choices=[g[0] for g in GROUPS])
    args = parser.parse_args()
    work = args.work.resolve()
    checkout = work / "checkout/src"
    output = work / "packs"
    output.mkdir(exist_ok=True)
    if sha(ROOT / "mozc/src/main/assets/mozc.data") != BASE_SHA:
        raise ValueError("Standard dataset changed; re-establish matching Mozc source first")
    fingerprint = runtime_fingerprint()
    buildfile = checkout / "data/dictionary_oss/BUILD.bazel"
    content = buildfile.read_text()
    if '"dictionary10.txt"' not in content:
        buildfile.write_text(content.replace('        "dictionary09.txt",', '        "dictionary09.txt",\n        "dictionary10.txt",'))
    tagfile = checkout / "data_manager/oss/BUILD.bazel"
    packs = []
    for pack_id, name, input_dir, license_name, tag in GROUPS:
        metadata = output / (pack_id + ".json")
        if args.only and args.only != pack_id:
            if metadata.exists():
                packs.append(json.loads(metadata.read_text()))
            continue
        started = time.monotonic()
        shutil.copyfile(work / input_dir / "dictionary10.txt", checkout / "data/dictionary_oss/dictionary10.txt")
        import re
        tagfile.write_text(re.sub(r'tag = "[^"]+"', f'tag = "{tag}"', tagfile.read_text()))
        env = dict(os.environ, USE_BAZEL_VERSION="9.0.2")
        with (output / (pack_id + ".build.log")).open("w") as log:
            subprocess.run([str(ROOT / "tools/bazelisk"), "build", "//data_manager/oss:mozc.data",
                            "--config", "oss_linux", "--config", "release_build", "--jobs=2", "--curses=no"],
                           cwd=checkout, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        data = output / (pack_id + ".data")
        shutil.copyfile(checkout / "bazel-bin/data_manager/oss/mozc.data", data)
        archive = output / (pack_id + ".data.gz")
        with data.open("rb") as source, archive.open("wb") as raw:
            with gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0, compresslevel=9) as compressed:
                shutil.copyfileobj(source, compressed)
        notice_path = f"legal/system-dictionaries/{pack_id}.txt"
        target = ROOT / "app/src/main/assets" / notice_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(notice(work / "sources", pack_id, name, license_name))
        shutil.copyfile(target, output / (pack_id + ".NOTICE.txt"))
        pack = {"id": pack_id, "name": name, "version": "2026-11-04 / v1", "license": license_name,
                "source": "NEologd abc61e33" if pack_id.startswith("neologd") else "SKK 0a164e6b",
                "url": f"https://github.com/zenskypj/Komorebi-IME/releases/download/{RELEASE}/{archive.name}",
                "compressedBytes": archive.stat().st_size, "compressedSha256": sha(archive),
                "dataBytes": data.stat().st_size, "dataSha256": sha(data),
                "dataVersion": "24.11." + tag, "runtime": fingerprint, "notice": notice_path}
        metadata.write_text(json.dumps(pack, ensure_ascii=False, indent=2) + "\n")
        (output / (pack_id + ".metrics.json")).write_text(json.dumps({
            "buildAndCompressSeconds": round(time.monotonic() - started, 3),
            "peakMemory": "not measured; Bazel server runs out-of-process", **pack}, ensure_ascii=False, indent=2) + "\n")
        packs.append(pack)
        print(pack_id, data.stat().st_size, archive.stat().st_size, flush=True)
    if len(packs) == len(GROUPS):
        catalog = {"schemaVersion": 1, "packs": packs}
        (ROOT / "mozc/src/main/assets/system-dictionaries.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
        (output / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
