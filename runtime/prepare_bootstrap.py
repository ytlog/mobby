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
    files['SYMLINKS.txt'] = ''.join(target + '←' + name + '\n' for name, target in links.items()).encode()
    mapping = {}
    with zipfile.ZipFile(assets / 'data.zip', 'w', zipfile.ZIP_DEFLATED) as data_zip:
        for path, data in sorted(files.items()):
            if data.startswith(b'\x7fELF'):
                command = path.removeprefix('bin/')
                name = 'lib' + command + '.so' if path in ('bin/bash', 'bin/node', 'bin/npm', 'bin/npx', 'bin/claude', 'bin/codex') else 'libbootstrap_' + hashlib.sha256(path.encode()).hexdigest()[:16] + '.so'
                (native / name).write_bytes(data)
                mapping[path] = name
            else:
                data_zip.writestr(path, data)
    (assets / 'binaries.json').write_text(json.dumps(mapping, sort_keys=True))
    (assets / 'version.txt').write_text(lock['sha256'] + agents_version)
    print(f'Prepared {len(mapping)} executable/library files; bootstrap {lock["sha256"]}')

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--ndk', type=pathlib.Path)
    args = parser.parse_args()
    prepare(args.output, args.ndk)
