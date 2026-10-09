#!/usr/bin/env bash
# P02: capture the shipped wrapper's argv from outside compiler/, without a JVM or port.
set -euo pipefail
COMPILER_DIR="$(cd "$(dirname "$0")/../../.." && pwd)"
python3 - "$COMPILER_DIR/bin/cxc" "$COMPILER_DIR" <<'PY'
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

wrapper, compiler = map(Path, sys.argv[1:])
with tempfile.TemporaryDirectory(prefix='cxc-cli-e2e-') as directory:
    caller = (Path(directory) / 'caller with spaces').resolve()
    caller.mkdir()
    trace = caller / '烏.trace.jsonl'
    trace.write_text('{}\n')
    source = caller / 'main.cxc'
    source.touch()
    stub = Path(directory) / 'clj'
    stub.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
Path(os.environ['CXC_ARGV_CAPTURE']).write_text(json.dumps({'cwd': os.getcwd(), 'argv': sys.argv[1:]}))
''')
    stub.chmod(0o755)
    capture = Path(directory) / 'argv.json'
    env = {**os.environ, 'PATH': f'{directory}:{os.environ["PATH"]}',
           'CXC_NO_DAEMON': '1', 'CXC_ARGV_CAPTURE': str(capture)}
    cases = [
        ([], 'cxc.core', []),
        (['trace', 'serve'], 'cxc.trace-view', []),
        (['trace', 'view'], 'cxc.core', ['view']),
        (['trace', 'serve', '--trace-dir', '.', '--port', '8769', '--no-push', '--no-accept-peer-inbound'],
         'cxc.trace-view', ['--trace-dir', str(caller), '--port', '8769', '--no-push', '--no-accept-peer-inbound']),
        (['trace', 'serve', '--port', '8769'], 'cxc.trace-view', ['--port', '8769']),
        (['trace', 'view', trace.name, '--port', '8765'], 'cxc.core', ['view', str(trace), '--port', '8765']),
        (['view', trace.name, '--shape=烏.shape.json', '--port=8765'],
         'cxc.core', ['view', str(trace), f'--shape={caller}/烏.shape.json', '--port=8765']),
        (['help', 'view'], 'cxc.core', ['help', 'view']),
        (['--help'], 'cxc.core', ['--help']),
        (['trace', 'serve', '--help'], 'cxc.trace-view', ['--help']),
        (['compile', source.name, 'new output', '--trace', '--target=macos'],
         'cxc.core', ['compile', str(source), f'{caller}/new output', '--trace', '--target=macos']),
        (['compile', '--trace', source.name, 'out'], 'cxc.core', ['compile', '--trace', str(source), f'{caller}/out']),
        ([source.name, 'out'], 'cxc.core', [str(source), f'{caller}/out']),
        (['trace', trace.name, '--top=5'], 'cxc.core', ['trace', str(trace), '--top=5']),
        (['trace-diff', trace.name, 'other.jsonl'], 'cxc.core', ['trace-diff', str(trace), f'{caller}/other.jsonl']),
        (['trace-filter', trace.name, '--seq=1-4'], 'cxc.core', ['trace-filter', str(trace), '--seq=1-4']),
        (['trace', 'annotation', 'list', '--status', 'open', '--trace', trace.name, '--format', 'json'],
         'cxc.core', ['trace', 'annotation', 'list', '--status', 'open', '--trace', str(trace), '--format', 'json']),
        (['trace', 'annotation', 'addressed', 'ann_01', '--summary', 'fixed the arm', '--commit', 'abc123'],
         'cxc.core', ['trace', 'annotation', 'addressed', 'ann_01', '--summary', 'fixed the arm', '--commit', 'abc123']),
        (['trace', 'annotate', '--from-focus', '--note', 'question?', '--out', 'out'],
         'cxc.core', ['trace', 'annotate', '--from-focus', '--note', 'question?', '--out', f'{caller}/out']),
    ]
    for args, namespace, expected in cases:
        for command in [[str(wrapper)], ['/bin/bash', str(wrapper)]]:
            subprocess.run([*command, *args], cwd=caller, env=env, check=True)
            actual = json.loads(capture.read_text())
            assert actual['argv'] == ['-M', '-m', namespace, *expected], (args, actual, expected)
            assert actual['cwd'] == str(compiler), actual
    print(f'cli-subcommand-e2e: PASSED · {len(cases)} argv cases under default and system Bash')
PY
