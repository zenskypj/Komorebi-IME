#!/usr/bin/env python3
"""Fetch complete, pinned upstream dictionary inputs, verifying Git blob identities.

No row sampling: all NEologd seeds and all tracked SKK dictionaries (including
individual dictionaries and the two postcode dictionaries) are retained.
Acquisition does not mean that a file is suitable as a conversion lexicon:
wrong/not_wrong, annotations, Lisp and okuri require separate interpretation.
"""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import urllib.request

SOURCES = {
    "neologd": ("neologd/mecab-ipadic-neologd", "abc61e33d8be3d0ead202e6b1df064c72d5ccf11"),
    "skk": ("skk-dev/dict", "0a164e6b990c5eb5b59eb7d8789f08865dc2f644"),
}


def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Komorebi-dictionary-builder"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def wanted(name, path):
    if name == "neologd":
        return path.startswith("seed/") or path in ("COPYING", "README.md", "README.ja.md")
    return ((path.startswith("SKK-JISYO.") and "/" not in path)
            or path.startswith("meta/")
            or path in ("zipcode/SKK-JISYO.zipcode", "zipcode/SKK-JISYO.office.zipcode",
                        "zipcode/README.md", "committers.md", "README.md", "Makefile", "edict_doc.html"))


def fetch_one(job, root):
    name, repo, commit, entry = job
    path = entry["path"]
    url = f"https://raw.githubusercontent.com/{repo}/{commit}/{path}"
    target = root / name / path
    data = target.read_bytes() if target.is_file() else get(url)
    blob = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
    if blob != entry["sha"] or len(data) != entry["size"]:
        raise ValueError(f"Upstream blob mismatch: {name}/{path}")
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".part")
    temporary.write_bytes(data)
    temporary.replace(target)
    return {"source": name, "repository": repo, "commit": commit, "path": path,
            "url": url, "bytes": len(data), "gitBlob": blob,
            "sha256": hashlib.sha256(data).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--lock", type=pathlib.Path, required=True)
    args = parser.parse_args()
    jobs = []
    for name, (repo, commit) in SOURCES.items():
        tree = json.loads(get(f"https://api.github.com/repos/{repo}/git/trees/{commit}?recursive=1"))
        if tree.get("truncated"):
            raise ValueError(f"Truncated tree: {name}")
        jobs.extend((name, repo, commit, entry) for entry in tree["tree"]
                    if entry["type"] == "blob" and wanted(name, entry["path"]))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        futures = [pool.submit(fetch_one, job, args.output) for job in jobs]
        files = [future.result() for future in futures]
    result = {"schemaVersion": 1, "files": sorted(files, key=lambda f: (f["source"], f["path"]))}
    # An existing lock is a guard, not a file to silently refresh.
    if args.lock.exists() and json.loads(args.lock.read_text()) != result:
        raise ValueError("Source lock changed; inspect upstream selection before updating it")
    args.lock.parent.mkdir(parents=True, exist_ok=True)
    args.lock.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(f"Verified {len(files)} complete source files ({sum(f['bytes'] for f in files):,} bytes)")


if __name__ == "__main__":
    main()
