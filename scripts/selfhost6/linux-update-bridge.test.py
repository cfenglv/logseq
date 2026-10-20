"""Run the native Linux bridge components with synthetic files and an owned Xvfb."""

import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile


def run(command, **options):
    try:
        return subprocess.run(command, check=True, text=True, capture_output=True,
                              timeout=30, **options)
    except subprocess.CalledProcessError as error:
        sys.stdout.write(error.stdout)
        sys.stderr.write(error.stderr)
        raise


def main():
    if sys.platform != 'linux':
        print('SKIP native Linux bridge components require Linux')
        return
    repo = Path(__file__).resolve().parents[2]
    source = repo / 'scripts/selfhost6/linux-update-bridge.test.c'
    flags = shlex.split(run(['pkg-config', '--cflags', '--libs', 'glib-2.0', 'gtk+-3.0']).stdout)
    payloads = {
        'positive': '''#!/bin/sh
if [ -n "${APPIMAGE+x}${APPDIR+x}${ARGV0+x}${APPIMAGE_EXIT_AFTER_INSTALL+x}" ]; then
  printf 'context-not-cleared\\n' > "$BRIDGE_TEST_RESULT.tmp"
else
  printf 'clear\\n' > "$BRIDGE_TEST_RESULT.tmp"
fi
mv "$BRIDGE_TEST_RESULT.tmp" "$BRIDGE_TEST_RESULT"
''',
        'postcommit': '#!/nonexistent-logseq-update-interpreter\n',
    }
    with tempfile.TemporaryDirectory(prefix='native-update-components-') as temporary:
        root = Path(temporary)
        results = []
        for name, payload in payloads.items():
            case = root / name
            case.mkdir()
            (case / 'bridge_payload.h').write_text(
                '#define BRIDGE_PAYLOAD_NAME "Logseq-linux-arm64-2.0.2-selfhost.1.AppImage"\n'
                f'#define BRIDGE_PAYLOAD_SIZE {len(payload.encode())}ULL\n'
                f'#define BRIDGE_PAYLOAD_SHA256 "{hashlib.sha256(payload.encode()).hexdigest()}"\n')
            (case / 'bridge_test_payload.h').write_text(f'#define TEST_PAYLOAD {json.dumps(payload)}\n')
            # Synthetic test text; shipped native text comes from the language build task.
            (case / 'native_update_strings.h').write_text(
                '#define BRIDGE_ERROR_TITLE_EN "Update error"\n'
                '#define BRIDGE_ERROR_DESC_EN "Open the app again."\n'
                '#define BRIDGE_ERROR_TITLE_ZH_CN "更新错误"\n'
                '#define BRIDGE_ERROR_DESC_ZH_CN "请重新打开应用。"\n')
            command = ['cc', '-std=gnu11', '-O2', '-Wall', '-Wextra', '-Werror',
                       '-Wl,--wrap=getdelim', '-I', str(case), str(source), '-o', str(case / 'test')]
            if name == 'postcommit':
                command.append('-DTEST_POSTCOMMIT_FAILURE')
            run(command + flags)
            results.append(run([str(case / 'test')]).stdout)

        # -displayfd asks the owned X server for a free display; no host X server is used.
        read_fd, write_fd = os.pipe()
        xvfb = subprocess.Popen(['Xvfb', '-displayfd', str(write_fd), '-screen', '0',
                                 '640x480x24', '-nolisten', 'tcp', '-ac'],
                                pass_fds=(write_fd,), stdout=subprocess.DEVNULL,
                                stderr=subprocess.PIPE, text=True)
        os.close(write_fd)
        try:
            import select
            if not select.select([read_fd], [], [], 10)[0]:
                raise RuntimeError('Owned Xvfb did not start')
            display = os.read(read_fd, 32).decode().strip()
            if not display.isdecimal():
                raise RuntimeError('Invalid owned Xvfb display')
            cache = root / 'font-cache'
            cache.mkdir()
            result = run([str(root / 'positive/test'), '--failure-dialog'],
                         env={**os.environ, 'DISPLAY': f':{display}', 'XDG_CACHE_HOME': str(cache)})
            results.append(result.stdout)
        finally:
            os.close(read_fd)
            xvfb.terminate()
            xvfb.wait(timeout=5)
        print(''.join(results), end='')


if __name__ == '__main__':
    main()
