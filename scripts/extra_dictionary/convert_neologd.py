#!/usr/bin/env python3
"""Convert every pinned NEologd seed to Mozc's five-column system lexicon.

MeCab numeric IDs are never copied. POS/conjugation is mapped through the target
Mozc id.def; standard reading/surface pairs are retained at their original cost.
All exclusions and POS generalizations are counted, with rejected rows retained.
"""
import argparse
import collections
import csv
import hashlib
import json
import lzma
import pathlib
import resource
import sqlite3
import time
import unicodedata


def hiragana(text):
    text = unicodedata.normalize("NFC", text)
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in text)


class PosMap:
    def __init__(self, path):
        self.ids = {}
        for line in path.read_text().splitlines():
            number, fields = line.split(" ", 1)
            self.ids[tuple(fields.split(","))] = int(number)

    def resolve(self, row):
        pos = tuple(p or "*" for p in row[4:10])
        base = row[10]
        for tail in (base, "*"):
            if pos + (tail,) in self.ids:
                return self.ids[pos + (tail,)], "exact"
        # Mozc intentionally shares its classical adjective connection across
        # conjugation classes, whereas IPAdic labels each class separately.
        if pos[0] == "形容詞" and pos[5] == "文語基本形":
            general = pos[:4] + ("*", pos[5], "*")
            if general in self.ids:
                return self.ids[general], "classical-adjective-shared-connection"
        # IPAdic's イイ has separate names for the basic/contracted spelling;
        # Mozc represents those surfaces using the イ段 basic-form connection.
        if pos[0] == "形容詞" and pos[4] == "形容詞・イイ" and pos[5] in ("基本形", "基本形-促音便"):
            lexical = pos[:4] + ("不変化型", pos[5], base)
            if lexical in self.ids:
                return self.ids[lexical], "ii-adjective-lexical-connection"
            general = pos[:4] + ("形容詞・イ段", "基本形", "*")
            if general in self.ids:
                return self.ids[general], "ii-adjective-basic-connection"
        raise ValueError("unmapped-pos:" + ",".join(pos))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=pathlib.Path, required=True)
    parser.add_argument("--mozc", type=pathlib.Path, required=True, help="Mozc src directory")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--group", choices=("all", "main", "variants"), default="all",
                        help="Complete upstream file groups, to stay within Mozc's 22-bit word trie limit")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    dictionary = args.mozc / "data/dictionary_oss"
    pos_map = PosMap(dictionary / "id.def")
    database = args.output / "conversion.sqlite3"
    if database.exists():
        database.unlink()
    db = sqlite3.connect(database)
    db.executescript("""PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
        CREATE TABLE baseline (reading TEXT, surface TEXT, PRIMARY KEY(reading,surface)) WITHOUT ROWID;
        CREATE TABLE words (reading TEXT, lid INTEGER, surface TEXT, cost INTEGER,
                            PRIMARY KEY(reading,lid,surface)) WITHOUT ROWID;""")
    for path in sorted(dictionary.glob("dictionary0[0-9].txt")):
        def baseline_rows():
            for line in path.open():
                fields = line.split("\t")
                if len(fields) >= 5:
                    yield fields[0], fields[4]
        db.executemany("INSERT OR IGNORE INTO baseline VALUES (?,?)", baseline_rows())
    db.commit()
    baseline = db.execute("SELECT count(*) FROM baseline").fetchone()[0]
    files = {}
    rejected = args.output / "rejected.jsonl"
    with rejected.open("w") as rejects:
        for path in sorted((args.sources / "neologd/seed").glob("*.csv.xz")):
            is_main = path.name.startswith("mecab-user-dict-seed.")
            if args.group == "main" and not is_main or args.group == "variants" and is_main:
                continue
            counts = collections.Counter()
            pending = []
            with lzma.open(path, "rt", encoding="utf-8", newline="") as source:
                for number, row in enumerate(csv.reader(source), 1):
                    counts["sourceRows"] += 1
                    try:
                        if len(row) != 13:
                            raise ValueError("invalid-column-count")
                        reading, surface = hiragana(row[11]), row[0]
                        if not reading or reading == "*" or not surface:
                            raise ValueError("empty-reading-or-surface")
                        if any(c in reading + surface for c in "\t\r\n\0"):
                            raise ValueError("control-character")
                        lid, mapping = pos_map.resolve(row)
                        counts["pos:" + mapping] += 1
                        # Conservative first calibration; MeCab costs are not
                        # directly comparable to Mozc costs or connection IDs.
                        cost = 9000 + max(-6000, min(12000, int(row[3]))) // 4
                        pending.append((reading, lid, surface, cost))
                    except ValueError as error:
                        counts[str(error)] += 1
                        rejects.write(json.dumps({"file": path.name, "line": number,
                                                  "reason": str(error), "row": row}, ensure_ascii=False) + "\n")
                    if len(pending) >= 10000:
                        insert(db, pending)
                        pending.clear()
            insert(db, pending)
            db.commit()
            files[path.name] = dict(counts)
            print(path.name, counts["sourceRows"], flush=True)
    before = db.execute("SELECT count(*) FROM words").fetchone()[0]
    db.execute("DELETE FROM words WHERE EXISTS (SELECT 1 FROM baseline b WHERE b.reading=words.reading AND b.surface=words.surface)")
    db.commit()
    after = db.execute("SELECT count(*) FROM words").fetchone()[0]
    target = args.output / "dictionary10.txt"
    with target.open("w") as stream:
        for reading, lid, surface, cost in db.execute("SELECT reading,lid,surface,cost FROM words ORDER BY reading,lid,surface"):
            stream.write(f"{reading}\t{lid}\t{lid}\t{cost}\t{surface}\n")
    report = {"schemaVersion": 1, "group": args.group, "files": files, "baselinePairs": baseline,
              "uniqueBeforeBaselineDedup": before, "baselineDuplicateAnalyses": before - after,
              "outputRows": after, "outputBytes": target.stat().st_size,
              "outputSha256": hashlib.sha256(target.read_bytes()).hexdigest(),
              "seconds": round(time.monotonic() - start, 3),
              "peakRssKiB": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
              "costFormula": "9000 + floor(clamp(MeCabCost, -6000, 12000) / 4)",
              "rejectedBytes": rejected.stat().st_size}
    (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: v for k, v in report.items() if k != "files"}, indent=2))


def insert(db, rows):
    db.executemany("""INSERT INTO words VALUES (?,?,?,?)
        ON CONFLICT(reading,lid,surface) DO UPDATE SET cost=min(cost,excluded.cost)""", rows)


if __name__ == "__main__":
    main()
