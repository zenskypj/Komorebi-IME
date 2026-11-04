#!/usr/bin/env python3
"""Use noun variants' explicit base field; do not turn inflected forms into lemmas.

The index only recognizes canonical candidates already returned by Mozc. It never
rewrites text, picks one homonym, changes readings or synthesizes a correction.
"""
import argparse
import csv
import lzma
import pathlib
import unicodedata
from convert_neologd import hiragana


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    pairs = set()
    rows = 0
    for path in sorted((args.sources / "neologd/seed").glob("*ortho-variant*.csv.xz")):
        with lzma.open(path, "rt", encoding="utf-8", newline="") as stream:
            for row in csv.reader(stream):
                rows += 1
                if len(row) != 13 or row[4] != "名詞" or row[8:10] != ["*", "*"]:
                    continue
                reading = hiragana(unicodedata.normalize("NFKC", row[11]))
                value = row[10]
                if value in ("", "*") or value == row[0] or not reading:
                    continue
                if not all("ぁ" <= c <= "ゖ" or c == "ー" for c in reading):
                    continue
                if not any("ァ" <= c <= "ヺ" or "一" <= c <= "龯" for c in value):
                    continue
                if any(c in value for c in "\t\r\n\0"):
                    continue
                pairs.add(reading + "\t" + value)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("# NEologd abc61e33; Apache-2.0; see legal/system-dictionaries/neologd-variants.txt\n" +
                           "\n".join(sorted(pairs)) + "\n")
    print(f"{rows} rows processed; {len(pairs)} canonical reading/surface pairs; {args.output.stat().st_size} bytes")


if __name__ == "__main__":
    main()
