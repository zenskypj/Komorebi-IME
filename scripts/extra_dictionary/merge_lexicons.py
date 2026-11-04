#!/usr/bin/env python3
"""Merge several Mozc five-column lexicons, keeping the lowest cost per entry.

Inputs are Tab-separated "reading lid rid cost surface" files produced by
convert_neologd.py / convert_skk.py. Each of those already removes words that the
standard dictionary carries; this step only collapses duplicates that appear
across sources so a combined dataset does not repeat the same (reading, lid,
surface) token. Entries are never dropped for size: only exact repeats are kept
once at their minimum cost.
"""
import argparse
import pathlib
import sqlite3


def insert(db, rows):
    db.executemany("""INSERT INTO words VALUES (?,?,?,?)
        ON CONFLICT(reading,lid,surface) DO UPDATE SET cost=min(cost,excluded.cost)""", rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("inputs", type=pathlib.Path, nargs="+")
    args = parser.parse_args()

    database = args.output.with_suffix(".sqlite3")
    if database.exists():
        database.unlink()
    db = sqlite3.connect(database)
    db.executescript("""PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
        CREATE TABLE words (reading TEXT, lid INTEGER, surface TEXT, cost INTEGER,
                            PRIMARY KEY(reading,lid,surface)) WITHOUT ROWID;""")

    counts = {}
    for path in args.inputs:
        pending = []
        rows = 0
        with path.open() as source:
            for line in source:
                fields = line.rstrip("\n").split("\t")
                if len(fields) < 5:
                    continue
                pending.append((fields[0], int(fields[1]), fields[4], int(fields[3])))
                rows += 1
                if len(pending) >= 10000:
                    insert(db, pending)
                    pending.clear()
        insert(db, pending)
        db.commit()
        counts[str(path.parent.name)] = rows

    total = db.execute("SELECT count(*) FROM words").fetchone()[0]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w") as stream:
        for reading, lid, surface, cost in db.execute(
                "SELECT reading,lid,surface,cost FROM words ORDER BY reading,lid,surface"):
            stream.write(f"{reading}\t{lid}\t{lid}\t{cost}\t{surface}\n")
    db.close()
    database.unlink()
    print({"inputs": counts, "outputRows": total}, flush=True)


if __name__ == "__main__":
    main()
