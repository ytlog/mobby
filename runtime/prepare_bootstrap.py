#!/usr/bin/env python3
"""Prepare the pinned upstream bootstrap for Android's native library installer."""
import argparse
import hashlib
import json
import pathlib
import shutil
import urllib.request
import zipfile
from agent_bundle import add_agents

ROOT = pathlib.Path(__file__).resolve().parent
PROJECT = ROOT.parent

def prepare(output, ndk=None):
    lock = json.loads((ROOT / 'bootstrap.lock.json').read_text())
    archive = ROOT / 'cache/bootstrap-aarch64.zip'
    archive.parent.mkdir(exist_ok=True)
    if not archive.exists():
        temporary = archive.with_suffix('.download')
        with urllib.request.urlopen(lock['url'], timeout=60) as response, temporary.open('wb') as dest:
            shutil.copyfileobj(response, dest)
        temporary.replace(archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != lock['sha256']:
        raise ValueError('Bootstrap SHA-256 mismatch; remove runtime/cache/bootstrap-aarch64.zip and retry')
    native = output / 'jniLibs/arm64-v8a'
    assets = output / 'assets/bootstrap'
    native.mkdir(parents=True, exist_ok=True)
    assets.mkdir(parents=True, exist_ok=True)
    files, links = {}, {}
    with zipfile.ZipFile(archive) as source:
        for item in source.infolist():
            path = pathlib.PurePosixPath(item.filename)
            if path.is_absolute() or '..' in path.parts:
                raise ValueError('Unsafe archive path')
            if not item.is_dir():
                files[item.filename] = source.read(item)
    for line in files.pop('SYMLINKS.txt', b'').decode().splitlines():
        target, name = line.split('←', 1)
        links[name.removeprefix('./')] = target
    agents_version = add_agents(ROOT, files, links, ndk)
    notices = {
        'mobby-LICENSE': 'LICENSE',
        'THIRD-PARTY-NOTICE.md': 'third_party/NOTICE.md',
        'Codex-LICENSE': 'third_party/codex/LICENSE',
        'Codex-NOTICE': 'third_party/codex/NOTICE',
        'bubblewrap-COPYING': 'third_party/bubblewrap/COPYING',
        'opencode-termux-LICENSE': 'third_party/opencode-termux/LICENSE',
        'OpenCode-LICENSE': 'third_party/opencode-termux/OpenCode-LICENSE',
        'Bun-LICENSE.md': 'third_party/bun/LICENSE.md',
        'JavaScriptCore-COPYING.LIB': 'third_party/bun/JavaScriptCore-COPYING.LIB',
        'TinyCC-COPYING': 'third_party/bun/TinyCC-COPYING',
        'GCC-COPYING3': 'third_party/gcc/COPYING3',
        'GCC-COPYING.RUNTIME': 'third_party/gcc/COPYING.RUNTIME',
        'libtermux-LICENSE': 'third_party/libtermux-android/LICENSE',
        'llama.cpp-LICENSE': 'third_party/llama.cpp/LICENSE',
    }
    notices_digest = hashlib.sha256()
    for name, relative in notices.items():
        payload = (PROJECT / relative).read_bytes()
        files['share/mobby/licenses/' + name] = payload
        notices_digest.update(name.encode() + b'\0' + payload)
    # Offline App viewer: retain original copyright/license files, not just SPDX names.
    runtime_notices = []
    for path, payload in sorted(files.items()):
        leaf = pathlib.PurePosixPath(path).name.lower()
        if (path.startswith('share/LICENSES/') or path.startswith('share/mobby/licenses/') or
                leaf.startswith(('license', 'copying', 'copyright', 'notice'))):
            try:
                encoding = 'iso-8859-1' if path == 'share/LICENSES/CeCILL-2.1.txt' else 'utf-8'
                text = payload.decode(encoding)
            except UnicodeDecodeError as error:
                raise ValueError('Unreviewed license encoding: ' + path) from error
            if path.startswith('share/mobby/licenses/'):
                title = pathlib.PurePosixPath(path).name
            elif path.startswith('share/LICENSES/'):
                title = pathlib.PurePosixPath(path).stem
            else:
                title = pathlib.PurePosixPath(path).parent.name + ' — ' + pathlib.PurePosixPath(path).name
            runtime_notices.append({'id': path, 'title': title, 'license': '', 'text': text})
    notice_assets = output / 'assets/third-party'
    notice_assets.mkdir(parents=True, exist_ok=True)
    (notice_assets / 'runtime.json').write_text(json.dumps(runtime_notices, ensure_ascii=False, indent=2) + '\n')
    agents_version += notices_digest.hexdigest()
    files['SYMLINKS.txt'] = ''.join(target + '←' + name + '\n' for name, target in links.items()).encode()
    mapping = {}
    with zipfile.ZipFile(assets / 'data.zip', 'w', zipfile.ZIP_DEFLATED) as data_zip:
        for path, data in sorted(files.items()):
            if data.startswith(b'\x7fELF'):
                command = path.removeprefix('bin/')
                name = 'lib' + command + '.so' if path in ('bin/bash', 'bin/node', 'bin/npm', 'bin/npx', 'bin/claude', 'bin/codex', 'bin/opencode', 'bin/pi') else 'libbootstrap_' + hashlib.sha256(path.encode()).hexdigest()[:16] + '.so'
                (native / name).write_bytes(data)
                mapping[path] = name
            else:
                data_zip.writestr(path, data)
    # Gradle reuses this output directory; removed dependencies must not remain in the APK.
    expected = set(mapping.values())
    for previous in native.glob('*.so'):
        if previous.name not in expected:
            previous.unlink()
    (assets / 'binaries.json').write_text(json.dumps(mapping, sort_keys=True))
    (assets / 'version.txt').write_text(lock['sha256'] + agents_version)
    print(f'Prepared {len(mapping)} executable/library files; bootstrap {lock["sha256"]}')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--ndk', type=pathlib.Path)
    args = parser.parse_args()
    prepare(args.output, args.ndk)
