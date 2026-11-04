#!/usr/bin/env python3
"""Compare actual app proofreading prompts using a local Ollama model; no external endpoint."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', required=True)
    parser.add_argument('--format', choices=['QWEN', 'GEMMA'], default='QWEN')
    parser.add_argument('--baseline-ref', help='Read ProofreadPrompt.kt from this git ref; omit new FIX postprocessing')
    parser.add_argument('--cases', type=Path, default=ROOT / 'docs/research/proofreading-evaluation.json')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--gguf', type=Path, help='Record SHA-256 of the GGUF registered with Ollama')
    parser.add_argument('--replay', type=Path, help='Reapply processing to saved raw responses; prompt/model/cases must match')
    args = parser.parse_args()
    cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches/modules-2/files-2.1'
    kotlin = re.search(r'^kotlin\s*=\s*"([^"]+)"', (ROOT / 'gradle/libs.versions.toml').read_text(), re.M).group(1)
    def jar(group, name, version=None):
        paths = sorted((cache / group / name).glob((version or '*') + '/*/*.jar'))
        if not paths: raise SystemExit(f'Missing cached compiler dependency {name}; run Gradle first')
        return paths[-1]
    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', kotlin)
    jars = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', kotlin), stdlib,
            jar('org.jetbrains.kotlin', 'kotlin-script-runtime', kotlin), jar('org.jetbrains.kotlin', 'kotlin-reflect'),
            jar('org.jetbrains.intellij.deps', 'trove4j'), jar('org.jetbrains', 'annotations'),
            jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm')]
    java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
    work = ROOT / 'build/proofread-evaluation' / ('baseline' if args.baseline_ref else 'current') / re.sub(r'[^A-Za-z0-9_-]', '-', args.model)
    work.mkdir(parents=True, exist_ok=True)
    prompt = ROOT / 'app/src/main/java/me/zssu/ime/ime/ProofreadPrompt.kt'
    if args.baseline_ref:
        source = subprocess.check_output(['git', 'show', f'{args.baseline_ref}:app/src/main/java/me/zssu/ime/ime/ProofreadPrompt.kt'], cwd=ROOT)
        prompt = work / 'ProofreadPrompt.kt'; prompt.write_bytes(source)
    sources = [prompt, ROOT / 'app/src/main/java/me/zssu/ime/ime/ProofreadDiff.kt',
               ROOT / 'app/src/main/java/me/zssu/ime/ime/ProofreadReview.kt', ROOT / 'scripts/ProofreadEvaluation.kt']
    subprocess.run([java, '-cp', os.pathsep.join(map(str, jars)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', str(stdlib), '-jvm-target', '17', '-d', str(work / 'classes'),
                    *map(str, sources)], cwd=ROOT, check=True)
    adapter = subprocess.Popen([java, '-cp', os.pathsep.join([str(work / 'classes'), str(stdlib)]),
                                'me.zssu.ime.ime.ProofreadEvaluationKt', args.format,
                                'baseline' if args.baseline_ref else 'current'], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    def invoke(op, *texts):
        adapter.stdin.write('\t'.join([op] + [base64.b64encode(s.encode()).decode() for s in texts]) + '\n'); adapter.stdin.flush()
        status, value = adapter.stdout.readline().rstrip('\n').split('\t', 1)
        text = base64.b64decode(value).decode()
        if status != 'OK': raise ValueError(text)
        return text
    report = {'model': args.model, 'format': args.format, 'baselineRef': args.baseline_ref,
              'promptSha256': hashlib.sha256(prompt.read_bytes()).hexdigest(),
              'processingSha256': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sources[1:]}, 'runtime': 'Ollama host / raw prompt / greedy / context 2048',
              'limitation': 'Host backend, not Android. Fixed synthetic examples do not establish general accuracy.', 'cases': []}
    if args.gguf:
        h = hashlib.sha256()
        with args.gguf.open('rb') as stream:
            for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''): h.update(chunk)
        report['ggufSha256'] = h.hexdigest()
    replay = None
    if args.replay:
        replay = json.loads(args.replay.read_text())
        for key in ['model', 'format', 'promptSha256', 'ggufSha256']:
            if replay.get(key) != report.get(key):
                raise SystemExit(f'Replay mismatch: {key}')
        report['replayedFrom'] = args.replay.name
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        for case in json.loads(args.cases.read_text())['cases']:
            saved = None
            if replay:
                saved = next((c for c in replay['cases'] if c['id'] == case['id']), None)
                if not saved or any(saved[k] != case[k] for k in ['input', 'expected']):
                    raise ValueError(f"Replay case mismatch: {case['id']}")
            prompt_text = invoke('P', case['input'])
            payload = {'model': args.model, 'prompt': prompt_text, 'raw': True, 'stream': False, 'keep_alive': '10m',
                       'options': {'temperature': 0, 'top_k': 1, 'top_p': 1, 'repeat_penalty': 1, 'presence_penalty': 0,
                                   'frequency_penalty': 0, 'num_ctx': 2048, 'num_predict': 1024, 'seed': 1,
                                   'stop': ['<|im_end|>' if args.format == 'QWEN' else '<end_of_turn>']}}
            started = time.monotonic()
            if saved is not None:
                result = {'response': saved.get('raw', ''), 'done_reason': saved.get('doneReason'), 'prompt_eval_count': saved.get('promptTokens')}
            else:
                with urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:11434/api/generate', data=json.dumps(payload).encode(),
                                                                   headers={'Content-Type': 'application/json'}), timeout=300) as response:
                    result = json.load(response)
            row = dict(case, seconds=saved['seconds'] if saved else round(time.monotonic() - started, 3), doneReason=result.get('done_reason'), promptTokens=result.get('prompt_eval_count'))
            try:
                if result.get('done_reason') != 'stop': raise ValueError('Generation did not finish')
                row['raw'] = result.get('response', '')
                row['output'] = invoke('R', case['input'], row['raw'])
                row['exact'] = row['output'] == case['expected']
            except ValueError as error:
                row['error'] = str(error); row['exact'] = False
            report['cases'].append(row)
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
            print(f"{case['id']}: {'PASS' if row['exact'] else 'DIFF'}", flush=True)
    finally:
        adapter.stdin.close(); adapter.wait(timeout=10)
    print(f"Exact matches: {sum(c['exact'] for c in report['cases'])}/{len(report['cases'])}")

if __name__ == '__main__': main()
