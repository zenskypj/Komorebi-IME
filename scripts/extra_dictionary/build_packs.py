#!/usr/bin/env python3
"""Build complete, independently selectable datasets and the APK's trusted catalog.

Run fetch_sources.py, convert_neologd.py (main + variants), convert_skk.py first.
Uses an isolated Mozc checkout archived from 3f235b4; never edits third_party/mozc
or the APK's standard data. Applies patches/dictionary-generation/*.patch to that
checkout only, which lifts the upstream 22-bit value-trie limit so a bundle can
combine several sources into one dataset. No conversion benchmarks are run.
"""
import argparse
import gzip
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
MOZC_COMMIT = "3f235b4eb6fcff7d14ef5f0fb8ee56de7ee4c732"
BASE_SHA = "fbb9a23419d677a33968145cfe1979dac6e7d3c28712d469854b4707a5965ed1"
RELEASE = "v0.3.0-rc.33-dev"

# (id, name, input dir under work/, license, dataVersion tag)
SOURCES = [
    ("neologd-main", "NEologd 新語・固有名詞", "neologd-main", "Apache-2.0", "komorebi-neologd-abc61e33-v1"),
    ("neologd-variants", "NEologd 表記ゆれ・活用語", "neologd-variants", "Apache-2.0", "komorebi-neologd-variants-v1"),
    ("skk-gpl", "SKK 総合・人名・地名・専門語", "skk/gpl", "GPL-2.0（原典の条件を保持）", "komorebi-skk-gpl-v1"),
    ("skk-cc", "SKK 英和（edict・edict2）", "skk/cc", "CC-BY-SA-3.0", "komorebi-skk-cc-v1"),
    ("skk-unicode", "SKK 絵文字・異体字", "skk/unicode", "Unicode License", "komorebi-skk-unicode-v1"),
    ("skk-permissive", "SKK 沖縄・郵便番号・異体字・pubdic+", "skk/permissive", "Public Domain / Pubdic+", "komorebi-skk-permissive-v1"),
]

# (id, name, source ids, license, dataVersion tag)
BUNDLES = [
    ("neologd", "NEologd 統合（新語・固有名詞＋表記ゆれ・活用語）",
     ["neologd-main", "neologd-variants"], "Apache-2.0（原典の条件を保持）", "komorebi-neologd-bundle-v1"),
    ("skk", "SKK 統合（総合・英和・絵文字・その他）",
     ["skk-gpl", "skk-cc", "skk-unicode", "skk-permissive"],
     "GPL-2.0 / CC-BY-SA-3.0 / Unicode License / Public Domain（原典の条件を保持）", "komorebi-skk-bundle-v1"),
    ("all", "NEologd＋SKK 全部入り",
     [source[0] for source in SOURCES],
     "各原典の条件を保持（Apache-2.0 / GPL-2.0 / CC-BY-SA-3.0 / Unicode License / Public Domain）",
     "komorebi-all-bundle-v1"),
]


def jobs():
    directories = {source[0]: source[2] for source in SOURCES}
    result = [{"id": source[0], "name": source[1], "sourceIds": [source[0]],
               "lexicons": [(source[2], source[0])], "license": source[3], "tag": source[4]}
              for source in SOURCES]
    result += [{"id": bundle[0], "name": bundle[1], "sourceIds": list(bundle[2]),
                "lexicons": [(directories[sid], sid) for sid in bundle[2]],
                "license": bundle[3], "tag": bundle[4]} for bundle in BUNDLES]
    return result


def sha(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def runtime_fingerprint():
    paths = ["src/main/assets/mozc.data"] + [f"src/main/jniLibs/{abi}/libmozc.so" for abi in (
        "arm64-v8a", "armeabi-v7a", "x86", "x86_64")]
    identity = "\n".join(f"{p}:{sha(ROOT / 'mozc' / p)}" for p in paths)
    return hashlib.sha256(identity.encode()).hexdigest()


def apply_generation_patches(checkout_root):
    """Apply patches/dictionary-generation/*.patch to the isolated checkout only.

    build_mozc.sh owns patches/[0-9][0-9][0-9][0-9]-*.patch for third_party/mozc;
    the generation-only changes live in a subdirectory so the app's libmozc never
    picks them up and the runtime fingerprint stays stable.
    """
    patches = sorted((ROOT / "patches/dictionary-generation").glob("*.patch"))
    is_git = subprocess.run(["git", "-C", str(checkout_root), "rev-parse", "--is-inside-work-tree"],
                            capture_output=True).returncode == 0
    for patch in patches:
        name = patch.name
        data = patch.read_bytes()
        if is_git:
            if subprocess.run(["git", "-C", str(checkout_root), "apply", "--check", str(patch)],
                              capture_output=True).returncode == 0:
                print(f"==> Applying {name}", flush=True)
                subprocess.run(["git", "-C", str(checkout_root), "apply", str(patch)], check=True)
            elif subprocess.run(["git", "-C", str(checkout_root), "apply", "--reverse", "--check", str(patch)],
                                capture_output=True).returncode == 0:
                print(f"==> {name} already applied", flush=True)
            else:
                raise SystemExit(f"error: {name} neither applies nor is already applied")
        elif subprocess.run(["patch", "-p1", "-d", str(checkout_root), "--dry-run", "--forward", "--silent"],
                            input=data).returncode == 0:
            print(f"==> Applying {name} (patch)", flush=True)
            subprocess.run(["patch", "-p1", "-d", str(checkout_root), "--forward"], input=data, check=True)
        elif subprocess.run(["patch", "-p1", "-d", str(checkout_root), "--dry-run", "--reverse", "--silent"],
                            input=data).returncode == 0:
            print(f"==> {name} already applied", flush=True)
        else:
            raise SystemExit(f"error: cannot apply {name} and it does not appear to be already applied")


def source_attribution(source_root, source_id):
    if source_id.startswith("neologd"):
        text = "NEologd source: https://github.com/neologd/mecab-ipadic-neologd/tree/abc61e33d8be3d0ead202e6b1df064c72d5ccf11\n\n"
        return text + (source_root / "neologd/COPYING").read_text()
    from convert_skk import decode, PD, CC, UNICODE, NON_LEXICONS
    text = "SKK source: https://github.com/skk-dev/dict/tree/0a164e6b990c5eb5b59eb7d8789f08865dc2f644\n\n"
    for path in sorted((source_root / "skk").rglob("SKK-JISYO.*")):
        if path.suffix == ".header" or path.name in NON_LEXICONS:
            continue
        selected = "skk-cc" if path.name in CC else "skk-unicode" if path.name in UNICODE else "skk-permissive" if path.name in PD else "skk-gpl"
        if selected != source_id:
            continue
        decoded, _ = decode(path)
        header = []
        for line in decoded.splitlines():
            if not line.startswith(";"):
                break
            header.append(line)
        text += f"\n--- {path.relative_to(source_root / 'skk')} ---\n" + "\n".join(header) + "\n"
    return text


def notice(source_root, job):
    text = f"{job['name']}\nライセンス: {job['license']}\n\n"
    text += ("標準Mozcと追加語彙を結合したデータ全体を切り替えます。統合データセットは内部に複数の辞書を含みます。\n"
             "変換品質・端末動作の追加検証は実施していない試験版です。\n"
             "NEologdは全12 seedを新語・固有名詞と表記ゆれ・活用語の2データセットで提供します。\n"
             "SKKは公式個別辞書も全件処理します。動的Lisp・数値テンプレート・活用が未解決の行は生成報告へ保持し、候補にはしません。\n"
             "誤登録検査用wrong/wrong.annotated/noregistは候補にしません。\n"
             "品詞情報のない静的語は名詞を基本とし、人名・地名などは原典の種類に応じて扱います。\n"
             "個人辞書・学習履歴を大量辞書の保存先として使用しません。\n\n")
    text += f"Mozc source: https://github.com/google/mozc/tree/{MOZC_COMMIT}\n"
    for _, source_id in job["lexicons"]:
        text += "\n\n" + source_attribution(source_root, source_id)
    if any(sid.startswith("neologd") for _, sid in job["lexicons"]):
        text += "\n\n" + (ROOT / "app/src/main/assets/legal/KARUKAN_LICENSE_APACHE.txt").read_text()
    if any(sid == "skk-gpl" for _, sid in job["lexicons"]):
        text += "\n\n" + (ROOT / "scripts/extra_dictionary/licenses/GPL-2.txt").read_text()
    if any(sid == "skk-cc" for _, sid in job["lexicons"]):
        text += "\n\n" + (ROOT / "scripts/extra_dictionary/licenses/CC-BY-SA-3.0.txt").read_text()
    for filename in ("MOZC_LICENSE.txt", "MOZC_DICTIONARY_LICENSES.txt"):
        text += f"\n\n--- {filename} ---\n" + (ROOT / "app/src/main/assets/legal" / filename).read_text()
    text += f"\n\n再生成用ソース・全件処理報告: https://github.com/zenskypj/Komorebi-IME/releases/tag/{RELEASE}\n"
    return text


def lexicon_for(work, output, job):
    if len(job["lexicons"]) == 1:
        return work / job["lexicons"][0][0] / "dictionary10.txt"
    merged = output / (job["id"] + ".dictionary10.txt")
    subprocess.run([sys.executable, str(ROOT / "scripts/extra_dictionary/merge_lexicons.py"),
                    "--output", str(merged)] + [str(work / d / "dictionary10.txt") for d, _ in job["lexicons"]],
                   check=True)
    return merged


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=pathlib.Path, default=ROOT / "build/extra-dictionary")
    parser.add_argument("--only", choices=[job["id"] for job in jobs()])
    args = parser.parse_args()
    work = args.work.resolve()
    checkout = work / "checkout/src"
    output = work / "packs"
    output.mkdir(exist_ok=True)
    if sha(ROOT / "mozc/src/main/assets/mozc.data") != BASE_SHA:
        raise ValueError("Standard dataset changed; re-establish matching Mozc source first")
    if not checkout.is_dir():
        raise SystemExit(f"error: {checkout} not found. Extract the pinned Mozc {MOZC_COMMIT} checkout there first.")
    apply_generation_patches(checkout.parent)
    fingerprint = runtime_fingerprint()
    buildfile = checkout / "data/dictionary_oss/BUILD.bazel"
    content = buildfile.read_text()
    if '"dictionary10.txt"' not in content:
        buildfile.write_text(content.replace('        "dictionary09.txt",', '        "dictionary09.txt",\n        "dictionary10.txt",'))
    tagfile = checkout / "data_manager/oss/BUILD.bazel"
    packs = []
    for job in jobs():
        metadata = output / (job["id"] + ".json")
        if args.only and args.only != job["id"]:
            if metadata.exists():
                packs.append(json.loads(metadata.read_text()))
            continue
        started = time.monotonic()
        shutil.copyfile(lexicon_for(work, output, job), checkout / "data/dictionary_oss/dictionary10.txt")
        tagfile.write_text(re.sub(r'tag = "[^"]+"', f'tag = "{job["tag"]}"', tagfile.read_text()))
        env = dict(os.environ, USE_BAZEL_VERSION="9.0.2")
        with (output / (job["id"] + ".build.log")).open("w") as log:
            subprocess.run([str(ROOT / "tools/bazelisk"), "build", "//data_manager/oss:mozc.data",
                            "--config", "oss_linux", "--config", "release_build", "--jobs=2", "--curses=no"],
                           cwd=checkout, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
        data = output / (job["id"] + ".data")
        shutil.copyfile(checkout / "bazel-bin/data_manager/oss/mozc.data", data)
        archive = output / (job["id"] + ".data.gz")
        with data.open("rb") as source, archive.open("wb") as raw:
            with gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0, compresslevel=9) as compressed:
                shutil.copyfileobj(source, compressed)
        notice_path = f"legal/system-dictionaries/{job['id']}.txt"
        target = ROOT / "app/src/main/assets" / notice_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(notice(work / "sources", job))
        shutil.copyfile(target, output / (job["id"] + ".NOTICE.txt"))
        pack = {"id": job["id"], "name": job["name"], "version": "2026-11-04 / v1", "license": job["license"],
                "source": "NEologd abc61e33" if job["id"].startswith("neologd") else "SKK 0a164e6b" if job["id"].startswith("skk") else f"NEologd abc61e33 + SKK 0a164e6b ({len(job['sourceIds'])} データセット)",
                "sourceIds": job["sourceIds"],
                "url": f"https://github.com/zenskypj/Komorebi-IME/releases/download/{RELEASE}/{archive.name}",
                "compressedBytes": archive.stat().st_size, "compressedSha256": sha(archive),
                "dataBytes": data.stat().st_size, "dataSha256": sha(data),
                "dataVersion": "24.11." + job["tag"], "runtime": fingerprint, "notice": notice_path}
        metadata.write_text(json.dumps(pack, ensure_ascii=False, indent=2) + "\n")
        (output / (job["id"] + ".metrics.json")).write_text(json.dumps({
            "buildAndCompressSeconds": round(time.monotonic() - started, 3),
            "peakMemory": "not measured; Bazel server runs out-of-process", **pack}, ensure_ascii=False, indent=2) + "\n")
        packs.append(pack)
        print(job["id"], data.stat().st_size, archive.stat().st_size, flush=True)
    if len(packs) == len(jobs()):
        catalog = {"schemaVersion": 1, "packs": packs}
        (ROOT / "mozc/src/main/assets/system-dictionaries.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
        (output / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
