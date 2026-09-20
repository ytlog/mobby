"""Locked dependency payloads. No installer lifecycle scripts run on the build host."""
import base64
import hashlib
import io
import json
import pathlib
import platform
import subprocess
import tarfile

PREFIX = 'data/data/com.termux/files/usr/'

def checked_download(item, cache):
    target = cache / item['url'].rsplit('/', 1)[1]
    if not target.exists():
        temporary = target.with_suffix(target.suffix + '.part')
        subprocess.run(['curl', '-fL', '--retry', '2', '--max-time', '180', item['url'], '-o', str(temporary)], check=True)
        temporary.replace(target)
    if 'sha256' in item:
        expected = item['sha256']
        actual = hashlib.sha256(target.read_bytes()).hexdigest()
    else:
        algorithm, expected = item['integrity'].split('-', 1)
        actual = base64.b64encode(hashlib.new(algorithm, target.read_bytes()).digest()).decode()
    if actual != expected:
        raise ValueError('Integrity mismatch: ' + item['name'])
    return target

def deb_data(path):
    data = path.read_bytes()
    if data[:8] != b'!<arch>\n':
        raise ValueError('Invalid deb archive')
    offset = 8
    while offset + 60 <= len(data):
        header = data[offset:offset+60]
        name = header[:16].decode().strip().rstrip('/')
        size = int(header[48:58])
        start = offset + 60
        if name.startswith('data.tar'):
            return io.BytesIO(data[start:start+size])
        offset = start + size + size % 2
    raise ValueError('deb is missing data.tar')

def safe_name(value):
    p = pathlib.PurePosixPath(value)
    if p.is_absolute() or '..' in p.parts:
        raise ValueError('Unsafe dependency path: ' + value)
    return str(p)

def add_agents(root, files, links, ndk):
    lock_path = root / 'agents.lock.json'
    if not lock_path.exists():
        return ''
    lock = json.loads(lock_path.read_text())
    cache = root / 'cache'
    for package in lock['packages']:
        source = checked_download(package, cache)
        with tarfile.open(fileobj=deb_data(source), mode='r:*') as archive:
            hardlinks = []
            for member in archive:
                full = safe_name(member.name.removeprefix('./'))
                if not full.startswith(PREFIX):
                    continue
                name = full[len(PREFIX):]
                if not name:
                    continue
                if member.isfile():
                    files[name] = archive.extractfile(member).read()
                    links.pop(name, None)
                elif member.issym():
                    links[name] = member.linkname
                    files.pop(name, None)
                elif member.islnk():
                    hardlinks.append((name, member.linkname.removeprefix('./').removeprefix(PREFIX)))
            for name, target in hardlinks:
                files[name] = files[safe_name(target)]
                links.pop(name, None)
    for package in lock['npm']:
        source = checked_download(package, cache)
        with tarfile.open(source, 'r:gz') as archive:
            if package['name'] == '@anthropic-ai/claude-code':
                for member in archive:
                    name = safe_name(member.name.removeprefix('package/'))
                    if member.isfile() and not name.startswith('vendor/'):
                        files['lib/node_modules/@anthropic-ai/claude-code/' + name] = archive.extractfile(member).read()
            else:
                candidates = [m for m in archive if m.isfile() and (m.name.endswith('/codex/codex') or m.name.endswith('/bin/codex')) and 'aarch64-unknown-linux-musl' in m.name]
                if len(candidates) != 1:
                    raise ValueError('Expected one official static musl Codex binary')
                files['bin/codex'] = archive.extractfile(candidates[0]).read()
                links.pop('bin/codex', None)
    if not ndk:
        raise ValueError('NDK path required to build Android CLI launchers')
    host = 'darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64'
    compiler = pathlib.Path(ndk) / 'toolchains/llvm/prebuilt' / host / 'bin/aarch64-linux-android26-clang'
    launcher = cache / 'agent-launcher'
    subprocess.run([str(compiler), '-O2', '-fPIE', '-pie', '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384', str(root / 'agent_launcher.c'), '-o', str(launcher)], check=True)
    for name in ('npm', 'npx', 'claude'):
        files['bin/' + name] = launcher.read_bytes()
        links.pop('bin/' + name, None)
    files['share/mdoer/agents.lock.json'] = lock_path.read_bytes()
    return hashlib.sha256(lock_path.read_bytes() + (root / 'agent_launcher.c').read_bytes()).hexdigest()
