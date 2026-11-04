#!/usr/bin/env python3
"""Process the entire pinned SKK distribution into license-separated lexicons.

Finite literal entries are supported; Lisp programs/numeric templates and
unresolved okuri are recorded verbatim instead of executed or nounified.
Known inflections reuse the target Mozc dictionary's conjugation classes.
"""
import argparse
import collections
import json
import pathlib
import re
import sqlite3
import time

from convert_neologd import hiragana, PosMap

NON_LEXICONS = {"SKK-JISYO.wrong", "SKK-JISYO.wrong.annotated", "SKK-JISYO.noregist"}
PD = {"SKK-JISYO.okinawa", "SKK-JISYO.itaiji", "SKK-JISYO.pubdic+",
      "SKK-JISYO.zipcode", "SKK-JISYO.office.zipcode"}
UNICODE = {"SKK-JISYO.emoji", "SKK-JISYO.ivd"}
CC = {"SKK-JISYO.edict", "SKK-JISYO.edict2"}


def decode(path):
    raw = path.read_bytes()
    header = raw.splitlines()[0].lower()
    encodings = (["utf-8"] if b"utf-8" in header else
                 ["euc_jis_2004"] if b"euc-jis-2004" in header else ["euc_jp", "euc_jis_2004", "utf-8"])
    for encoding in encodings:
        try:
            return raw.decode(encoding), encoding
        except UnicodeError:
            pass
    raise ValueError(f"Undecodable source: {path}")


def literal(value):
    # SKK escapes slash/semicolon using constant concat forms. Decode only
    # string literals; never evaluate Lisp, callbacks, or interpolation.
    if not value.startswith("("):
        return value
    if not re.fullmatch(r'\(concat(?:\s+"(?:[^"\\]|\\.)*")+\s*\)', value):
        raise ValueError("dynamic-lisp")
    pieces = re.findall(r'"((?:[^"\\]|\\.)*)"', value)
    def unescape(match):
        token = match.group(1)
        if re.fullmatch(r"[0-7]{1,3}", token):
            return chr(int(token, 8))
        if token in ('"', "\\"):
            return token
        raise ValueError("unsupported-string-escape")
    return "".join(re.sub(r"\\([0-7]{1,3}|.)", unescape, piece) for piece in pieces)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=pathlib.Path, required=True)
    parser.add_argument("--mozc", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    start = time.monotonic()
    args.output.mkdir(parents=True, exist_ok=True)
    ids = PosMap(args.mozc / "data/dictionary_oss/id.def").ids
    reverse = {v: k for k, v in ids.items()}
    forms = collections.defaultdict(list)
    for line in (args.mozc / "data/rules/cforms.def").read_text().splitlines():
        if line.startswith("#") or not line.strip():
            continue
        fields = line.split()
        if len(fields) >= 4:
            forms[(fields[0], fields[1])].append(tuple("" if f == "*" else f for f in fields[2:4]))
    stems = collections.defaultdict(set)
    baseline = set()
    for path in sorted((args.mozc / "data/dictionary_oss").glob("dictionary0[0-9].txt")):
        for line in path.open():
            fields = line.rstrip("\n").split("\t")
            if len(fields) < 5:
                continue
            reading, lid, surface = fields[0], int(fields[1]), fields[4]
            baseline.add((reading, surface))
            pos = reverse[lid]
            if pos[0] not in ("動詞", "形容詞"):
                continue
            for value_suffix, key_suffix in forms[(pos[4], pos[5])]:
                if reading.endswith(key_suffix) and surface.endswith(value_suffix):
                    key = (reading[:-len(key_suffix)] if key_suffix else reading,
                           surface[:-len(value_suffix)] if value_suffix else surface)
                    if key[0] and key[1]:
                        stems[key].add(pos[:5])
    words = {group: {} for group in ("gpl", "cc", "unicode", "permissive")}
    files = {}
    with (args.output / "unresolved.jsonl").open("w") as unresolved:
        sources = sorted((args.sources / "skk").glob("SKK-JISYO.*"))
        sources += sorted((args.sources / "skk/zipcode").glob("SKK-JISYO.*"))
        for path in sources:
            if path.suffix == ".header":
                continue
            text, encoding = decode(path)
            group = "cc" if path.name in CC else "unicode" if path.name in UNICODE else "permissive" if path.name in PD else "gpl"
            counts = collections.Counter()
            target = words[group]
            okuri = False
            for number, line in enumerate(text.splitlines(), 1):
                if line.startswith(";; okuri-ari entries."):
                    okuri = True
                if line.startswith(";; okuri-nasi entries."):
                    okuri = False
                if line.startswith(";") or not line.strip():
                    continue
                counts["sourceRows"] += 1
                if path.name in NON_LEXICONS:
                    counts["inspectionOnlyRows"] += 1
                    continue
                try:
                    reading, candidates = line.split(" ", 1)
                    if not candidates.startswith("/") or not candidates.endswith("/"):
                        raise ValueError("invalid-candidate-list")
                    if "#" in reading:
                        raise ValueError("numeric-template")
                    for rank, raw in enumerate(candidates[1:-1].split("/")):
                        if not raw or raw.startswith("[") or raw.endswith("]"):
                            counts["okuri-block-marker"] += 1
                            continue
                        counts["sourceCandidates"] += 1
                        try:
                            surface = literal(raw.split(";", 1)[0])
                            if not surface.strip() or any(c in reading + surface for c in "\t\r\n\0"):
                                raise ValueError("empty-or-control-character")
                            key = hiragana(reading)
                            cost = min(12500, 9500 + rank * 40)
                            analyses = []
                            if okuri and re.search("[a-z]$", key):
                                stem = key[:-1]
                                classes = stems.get((stem, surface), set())
                                if not classes:
                                    raise ValueError("unresolved-okuri")
                                for pos in sorted(classes):
                                    for (ctype, form), endings in forms.items():
                                        if ctype != pos[4]:
                                            continue
                                        for value_suffix, key_suffix in endings:
                                            expanded = stem + key_suffix, surface + value_suffix
                                            lid = ids.get(pos + (form, surface + value_suffix))
                                            if lid is None:
                                                lid = ids.get(pos + (form, "*"))
                                            if lid is not None and expanded[0] and expanded[1]:
                                                analyses.append((expanded[0], lid, expanded[1]))
                                if not analyses:
                                    raise ValueError("unresolved-inflection-id")
                                counts["knownOkuriCandidates"] += 1
                            else:
                                pos = ("名詞", "固有名詞", "人名", "一般", "*", "*", "*") if path.name in ("SKK-JISYO.jinmei", "SKK-JISYO.fullname") else (
                                    ("名詞", "固有名詞", "地域", "一般", "*", "*", "*") if path.name in ("SKK-JISYO.geo", "SKK-JISYO.station", "SKK-JISYO.zipcode", "SKK-JISYO.office.zipcode", "SKK-JISYO.okinawa") else
                                    ("名詞", "固有名詞", "一般", "*", "*", "*", "*") if path.name == "SKK-JISYO.propernoun" else
                                    ("記号", "一般", "*", "*", "*", "*", "*") if group == "unicode" else
                                    ("名詞", "一般", "*", "*", "*", "*", "*"))
                                analyses.append((key, ids[pos], surface))
                                counts["staticCandidates"] += 1
                            for key, lid, value in analyses:
                                if (key, value) in baseline:
                                    counts["baselineDuplicateAnalyses"] += 1
                                    continue
                                identity = key, lid, value
                                if identity in target:
                                    counts["duplicateAnalyses"] += 1
                                target[identity] = min(cost, target.get(identity, cost))
                        except ValueError as error:
                            counts[str(error)] += 1
                            unresolved.write(json.dumps({"file": path.name, "line": number, "reading": reading,
                                                         "candidate": raw, "reason": str(error)}, ensure_ascii=False) + "\n")
                except ValueError as error:
                    counts[str(error)] += 1
                    unresolved.write(json.dumps({"file": path.name, "line": number, "raw": line,
                                                 "reason": str(error)}, ensure_ascii=False) + "\n")
            files[str(path.relative_to(args.sources / "skk"))] = {"encoding": encoding, "group": group, **dict(counts)}
            print(path.name, counts["sourceRows"], flush=True)
    groups = {}
    for group, entries in words.items():
        directory = args.output / group
        directory.mkdir(exist_ok=True)
        path = directory / "dictionary10.txt"
        with path.open("w") as stream:
            for (key, lid, value), cost in sorted(entries.items()):
                stream.write(f"{key}\t{lid}\t{lid}\t{cost}\t{value}\n")
        groups[group] = {"outputRows": len(entries), "outputBytes": path.stat().st_size}
    report = {"schemaVersion": 1, "files": files, "groups": groups,
              "seconds": round(time.monotonic() - start, 3),
              "limitations": ["Dynamic Lisp and numeric templates cannot be finite system words.",
                              "Okuri without a verified Mozc conjugation class is retained in unresolved.jsonl.",
                              "Static entries without POS metadata use noun/symbol classes; source-specific names use proper noun classes.",
                              "wrong/wrong.annotated/noregist are inspection inputs, not conversion words."]}
    (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(groups, indent=2))


if __name__ == "__main__":
    main()
