#!/usr/bin/env python3
"""Compare locally captured engine results without using real user text.

Input: UTF-8 TSV, one row per case and engine with columns
case_id, engine, expected, candidates, latency_ms.
Candidates are JSON arrays; expected is a JSON array of accepted strings.
"""
import argparse
import csv
import json
import math
import statistics
import sys
from collections import defaultdict


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", help="TSV of synthetic, manually reviewed conversion cases")
    args = parser.parse_args()
    groups = defaultdict(list)
    cases = {}
    seen = set()
    with open(args.path, encoding="utf-8-sig", newline="") as file:
        for line, row in enumerate(csv.DictReader(file, delimiter="\t"), start=2):
            try:
                case = row["case_id"]
                engine = row["engine"]
                expected = json.loads(row["expected"])
                candidates = json.loads(row["candidates"])
                latency = float(row["latency_ms"])
                if not case or engine not in {"mozc", "karukan", "zenzai"}:
                    raise ValueError("unknown case or engine")
                if (case, engine) in seen:
                    raise ValueError("duplicate case/engine")
                if not isinstance(expected, list) or not expected or not all(isinstance(x, str) and x for x in expected):
                    raise ValueError("expected must be a nonempty string array")
                if not isinstance(candidates, list) or not all(isinstance(x, str) for x in candidates):
                    raise ValueError("candidates must be a string array")
                if not math.isfinite(latency) or latency < 0:
                    raise ValueError("invalid latency")
                if case in cases and cases[case] != expected:
                    raise ValueError("expected differs for this case")
            except (KeyError, ValueError, TypeError, json.JSONDecodeError) as error:
                raise SystemExit(f"line {line}: {error}")
            seen.add((case, engine))
            cases[case] = expected
            groups[engine].append((case, expected, candidates, latency))
    if not groups:
        raise SystemExit("no rows")
    common = set.intersection(*(set(row[0] for row in rows) for rows in groups.values()))
    print("engine\tcases\tmatched_cases\ttop1\ttop3\tmedian_ms\tp95_ms")
    for engine, rows in sorted(groups.items()):
        comparable = [row for row in rows if row[0] in common]
        times = sorted(row[3] for row in comparable)
        top = lambda k: sum(any(value in row[1] for value in row[2][:k]) for row in comparable)
        pct = lambda k: f"{100 * top(k) / len(comparable):.1f}%" if comparable else "n/a"
        print(f"{engine}\t{len(rows)}\t{len(comparable)}\t{pct(1)}\t{pct(3)}\t"
              f"{statistics.median(times) if times else 'n/a'}\t"
              f"{times[math.ceil(len(times)*.95)-1] if times else 'n/a'}")
    if len(groups) != 3 or any(len(rows) != len(common) for rows in groups.values()):
        print("警告: 3エンジンで同じケースが揃っていません。共通ケースだけを比較しました。", file=sys.stderr)


if __name__ == "__main__":
    main()
