"""Generate surname/given-name evidence from Mozc's BSD-licensed OSS dictionary."""
import argparse
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("source", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
labels = {"姓": set(), "名": set()}
for line in (args.source / "id.def").read_text().splitlines():
    ident, pos = line.split(maxsplit=1)
    for label, ids in labels.items():
        if pos.startswith(f"名詞,固有名詞,人名,{label},"):
            ids.add(ident)
assert all(labels.values())
words = {("ふじよし", "藤吉", "姓"), ("かりん", "夏鈴", "名")}
for path in sorted(args.source.glob("dictionary[0-9][0-9].txt")):
    for line in path.read_text().splitlines():
        reading, left, right, _, word = line.split("\t")[:5]
        for label, ids in labels.items():
            if left in ids and right in ids and word and all("ぁ" <= c <= "ゖ" or c == "ー" for c in reading):
                words.add((reading, word, label))
args.output.write_text("# Mozc OSS surname and given-name evidence; see MOZC_LICENSE.txt.\n" +
    "".join("\t".join(row) + "\n" for row in sorted(words)), encoding="utf-8")
