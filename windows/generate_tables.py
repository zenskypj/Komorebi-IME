import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parent.parent
paths = {
    'romaji': [root / 'app/src/main/assets/zenzai/romaji.tsv'],
    'dictionary': [root / 'windows/dictionary.tsv', root / 'data/dictionaries/proper-nouns-base.tsv'],
}
out = ['#pragma once', '#include <string_view>', 'struct Rule { std::wstring_view key, value; };']
for name, sources in paths.items():
    out.append(f'inline constexpr Rule {name}[] = {{')
    seen = set()
    for path in sources:
        for line in path.read_text(encoding='utf-8').splitlines():
            cols = line.split('\t')
            if line.startswith('#') or len(cols) < 2 or not all(cols[:2]):
                continue
            pair = tuple(cols[:2])
            if pair not in seen:
                out.append('    {' + ', '.join('L' + json.dumps(c, ensure_ascii=False) for c in pair) + '},')
                seen.add(pair)
    out.append('};')
Path(sys.argv[1]).write_text('\n'.join(out) + '\n', encoding='utf-8')

# The dictionary build can also run without the external checkout (small test fixture).
if len(sys.argv) > 2:
    source = Path(sys.argv[2])
    entries = {}
    files = sorted(source.glob('dictionary[0-9][0-9].txt'))
    if not files:
        raise SystemExit(f'No Mozc dictionary shards in {source}')
    for file in files:
        for line in file.read_text(encoding='utf-8').splitlines():
            cols = line.split('\t')
            if len(cols) != 5:
                continue
            key, _, _, cost, value = cols
            if not 1 <= len(key) <= 16 or key == value or not all('ぁ' <= c <= 'ゖ' or c == 'ー' for c in key):
                continue
            pair = (key, value)
            entries[pair] = min(entries.get(pair, 100000), int(cost))
    ranked = sorted(entries.items(), key=lambda item: (item[1], item[0]))[:60000]
else:
    ranked = []
with Path(sys.argv[1]).open('a', encoding='utf-8') as output:
    output.write('struct Lexeme { std::wstring_view key, value; int cost; };\n')
    output.write('inline constexpr Lexeme lexicon[] = {{L"", L"", 0},\n')
    for (key, value), cost in sorted(ranked, key=lambda item: (item[0][0], item[1], item[0][1])):
        output.write('    {' + ', '.join('L' + json.dumps(c, ensure_ascii=False) for c in (key, value)) + f', {cost}}},\n')
    output.write('};\n')
