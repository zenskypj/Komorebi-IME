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
