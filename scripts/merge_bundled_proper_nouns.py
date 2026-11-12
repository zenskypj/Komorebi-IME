#!/usr/bin/env python3
"""Merge supplied JSONL into the bundled proper-noun dictionary, never a network pack.

Keep the original JSONL (including costs) as source. Mozc's existing user-dictionary
TSV importer supports POS labels, not per-entry word costs; do not claim to apply them.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
POS = {
    "名詞,固有名詞,地名": "地名",
    "名詞,固有名詞,人名": "人名",
    "名詞,固有名詞,組織": "組織",
    "名詞,固有名詞,一般": "固有名詞",
}


def reading(text):
    normalized = unicodedata.normalize("NFKC", text.strip())
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in normalized)


def pair(key, value):
    if not isinstance(key, str) or not isinstance(value, str):
        raise ValueError("reading/surface must be strings")
    key, value = reading(key), unicodedata.normalize("NFC", value)
    if not key or not value.strip() or any(c in key + value for c in "\t\r\n\0"):
        raise ValueError("empty reading/surface or control characters")
    return key, value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, default=ROOT / "data/dictionaries/proper-nouns-base.tsv")
    parser.add_argument("--extra", type=Path, default=ROOT / "data/dictionaries/proper-nouns-extra.jsonl")
    parser.add_argument("--vtuber", type=Path, default=ROOT / "data/dictionaries/vtuber-names.tsv")
    parser.add_argument("--output", type=Path, default=ROOT / "mozc/src/main/assets/dictionaries/proper-nouns.txt")
    parser.add_argument("--report", type=Path, default=ROOT / "data/dictionaries/proper-nouns-merge.json")
    args = parser.parse_args()
    words = {}
    base_rows = 0
    for number, line in enumerate(args.base.read_text().splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) < 3:
            raise ValueError(f"base line {number}: expected three TSV columns")
        key = pair(fields[0], fields[1])
        words.setdefault(key, fields[2])
        base_rows += 1
    base_keys = set(words)
    added_keys = set()
    source_pos = collections.Counter()
    refined = 0
    extra_rows = 0
    costs = []
    for number, line in enumerate(args.extra.read_text().splitlines(), 1):
        if not line.strip():
            continue
        row = json.loads(line)
        key = pair(row["reading"], row["surface"])
        label = POS[row["pos"]]
        cost = row["cost"]
        if not isinstance(cost, int) or isinstance(cost, bool):
            raise ValueError(f"extra line {number}: cost must be an integer")
        costs.append(cost)
        source_pos[row["pos"]] += 1
        if key not in words:
            words[key] = label
        elif words[key] == "固有名詞" and label != "固有名詞":
            words[key] = label
            refined += 1
        added_keys.add(key)
        extra_rows += 1
    before_vtuber = set(words)
    vtuber_keys = set()
    vtuber_groups = collections.Counter()
    vtuber_rows = 0
    for number, line in enumerate(args.vtuber.read_text().splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 4:
            raise ValueError(f"VTuber line {number}: expected four TSV columns")
        key = pair(fields[0], fields[1])
        if any(not ("ぁ" <= c <= "ゖ" or c == "ー") for c in key[0]):
            raise ValueError(f"VTuber line {number}: invalid reading")
        words.setdefault(key, "人名")
        vtuber_keys.add(key)
        vtuber_groups[fields[2]] += 1
        vtuber_rows += 1
    header = (
        "# Komorebi IME 同梱固有名詞辞書\n"
        "# 従来分: CC0 (Public Domain)。追加分へCC0を自動適用しません。\n"
        "# 追加分: 利用者提供 proper-nouns-extra.jsonl (2026-11-05)。NOTICE参照。\n"
        "# VTuber名: 公式情報等で名称・読みを確認。出典はvtuber-names.tsv参照。\n"
        "# 読み・表記で重複整理。人名・地名・組織の品詞を保持。\n"
        "# costは原本JSONLへ保持。既存の3列取り込み形式では指定できません。\n"
        "# 再生成: python3 scripts/merge_bundled_proper_nouns.py\n"
    )
    result = header + "".join(f"{key}\t{value}\t{label}\n" for (key, value), label in sorted(words.items()))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(result)
    temporary.replace(args.output)
    report = {
        "sourceSha256": hashlib.sha256(args.extra.read_bytes()).hexdigest(),
        "baseRows": base_rows, "baseUnique": len(base_keys), "baseDuplicates": base_rows - len(base_keys),
        "extraRows": extra_rows, "extraUnique": len(added_keys), "overlap": len(base_keys & added_keys),
        "addedUnique": len(added_keys - base_keys), "refinedPos": refined,
        "vtuberSourceSha256": hashlib.sha256(args.vtuber.read_bytes()).hexdigest(),
        "vtuberRows": vtuber_rows, "vtuberUnique": len(vtuber_keys),
        "vtuberAdded": len(vtuber_keys - before_vtuber), "vtuberGroups": dict(vtuber_groups),
        "outputRows": len(words), "sourcePos": dict(source_pos),
        "outputPos": dict(collections.Counter(words.values())),
        "sourceCostMin": min(costs), "sourceCostMax": max(costs), "costApplied": False,
        "outputSha256": hashlib.sha256(result.encode()).hexdigest(),
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
