#!/usr/bin/env python3
"""Package preferred dictionary sources, transformed lexicons, scripts and notices.

Explicit allowlist: no local profiles, credentials, native build caches or .git.
"""
import gzip
import io
import pathlib
import subprocess
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORK = ROOT / "build/extra-dictionary"
COMMIT = "3f235b4eb6fcff7d14ef5f0fb8ee56de7ee4c732"


def add_file(archive, path, name):
    info = tarfile.TarInfo(name)
    info.size = path.stat().st_size
    info.mode = 0o644
    info.mtime = 0
    with path.open("rb") as source:
        archive.addfile(info, source)


def main():
    mozc = WORK / "mozc-source.tar"
    with mozc.open("wb") as output:
        subprocess.run(["git", "-C", str(ROOT / "third_party/mozc"), "archive", COMMIT],
                       stdout=output, check=True)
    target = WORK / "packs/dictionary-preferred-sources.tar.gz"
    with target.open("wb") as raw, gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0, compresslevel=6) as compressed:
        with tarfile.open(fileobj=compressed, mode="w|") as archive:
            add_file(archive, mozc, "mozc-source-" + COMMIT + ".tar")
            for directory in (WORK / "sources", ROOT / "scripts/extra_dictionary", ROOT / "docs/extra-dictionaries"):
                for path in sorted(directory.rglob("*")):
                    if path.is_file() and "__pycache__" not in path.parts:
                        prefix = "sources/" if directory == WORK / "sources" else str(directory.relative_to(ROOT)) + "/"
                        add_file(archive, path, prefix + str(path.relative_to(directory)))
            for name in ("neologd-main", "neologd-variants", "skk"):
                for path in sorted((WORK / name).rglob("*")):
                    if path.name in ("dictionary10.txt", "report.json", "unresolved.jsonl", "rejected.jsonl"):
                        add_file(archive, path, "transformed/" + name + "/" + str(path.relative_to(WORK / name)))
            for path in sorted((WORK / "packs").iterdir()):
                if path.suffix in (".json", ".log") or path.name.endswith("NOTICE.txt"):
                    add_file(archive, path, "pack-metadata/" + path.name)
            for filename in ("NOTICE", "LICENSE"):
                add_file(archive, ROOT / filename, filename)
    print(target, target.stat().st_size)


if __name__ == "__main__":
    main()
