"""Locked dependency payloads. No installer lifecycle scripts run on the build host."""
import base64
import hashlib
import io
import json
import pathlib
import platform
import shutil
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

def add_pi(root, files):
    """Install the exact npm dependency tree without lifecycle scripts or host binaries."""
    source = root / 'pi-package'
    if not source.exists():
        return b''
    identity = (source / 'package.json').read_bytes() + (source / 'package-lock.json').read_bytes()
    digest = hashlib.sha256(identity).hexdigest()
    target = root / 'cache/pi-payload'
    marker = target / '.mobby-lock'
    entry = target / 'node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js'
    if not marker.exists() or marker.read_text() != digest or not entry.is_file():
        target.mkdir(parents=True, exist_ok=True)
        for name in ('package.json', 'package-lock.json'):
            shutil.copyfile(source / name, target / name)
        subprocess.run(['npm', 'ci', '--prefix', str(target), '--ignore-scripts', '--omit=optional',
                        '--no-audit', '--no-fund'], check=True)
        marker.write_text(digest)
    for file in sorted((target / 'node_modules').rglob('*')):
        relative = file.relative_to(target).as_posix()
        if '.bin' in file.relative_to(target).parts or file.is_dir():
            continue
        # Upstream pi-tui ships optional desktop TUI helpers in its npm tarball.
        if '/@earendil-works/pi-tui/native/' in relative:
            continue
        if file.is_symlink():
            raise ValueError('Unexpected Pi dependency symlink: ' + relative)
        if not file.is_file():
            raise ValueError('Expected regular Pi dependency: ' + relative)
        # Source maps are build diagnostics, not runtime dependencies.
        if file.name.endswith('.map'):
            continue
        payload = file.read_bytes()
        if file.suffix == '.node' or payload.startswith((b'\x7fELF', b'\xcf\xfa\xed\xfe', b'\xfe\xed\xfa\xcf', b'MZ')):
            raise ValueError('Unexpected native Pi dependency: ' + relative)
        files['lib/' + safe_name(relative)] = payload
    return identity

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
        if package['name'] == '@anthropic-ai/claude-code':
            if package.get('delivery') != 'device-download':
                raise ValueError('Claude Code must be downloaded by the device, not bundled')
            continue
        source = checked_download(package, cache)
        with tarfile.open(source, 'r:gz') as archive:
            if package['name'] == '@openai/codex':
                # The sandbox launcher searches PATH before package-relative resources.
                # Keep the official helper available for explicit sandboxed CLI invocations.
                for source_name, target in [('bin/codex', 'bin/codex'), ('codex-resources/bwrap', 'bin/bwrap')]:
                    name = 'package/vendor/aarch64-unknown-linux-musl/' + source_name
                    try:
                        member = archive.getmember(name)
                    except KeyError as error:
                        raise ValueError('Missing required Codex program: ' + source_name) from error
                    if not member.isfile():
                        raise ValueError('Expected regular Codex program: ' + source_name)
                    payload = archive.extractfile(member).read()
                    if not payload.startswith(b'\x7fELF'):
                        raise ValueError('Expected ELF Codex program: ' + source_name)
                    files[target] = payload
                    links.pop(target, None)
    opencode_files = {
        'opencode': 'lib/opencode/opencode',
        'ld-musl-aarch64.so.1': 'lib/opencode/ld-musl-aarch64.so.1',
        'libc.musl-aarch64.so.1': 'lib/opencode/libc.musl-aarch64.so.1',
        'libgcc_s.so.1': 'lib/opencode/libgcc_s.so.1',
        'libstdc++.so.6': 'lib/opencode/libstdc++.so.6',
        'libstdc++.so.6.0.33': 'lib/opencode/libstdc++.so.6.0.33',
    }
    for bundle in lock.get('bundles', []):
        source = checked_download(bundle, cache)
        found = set()
        with tarfile.open(source, 'r:gz') as archive:
            for member in archive:
                name = safe_name(member.name)
                if name not in opencode_files:
                    raise ValueError('Unexpected OpenCode runtime file: ' + name)
                target = opencode_files[name]
                if member.issym() or member.islnk():
                    links[target] = member.linkname
                    found.add(name)
                    continue
                if not member.isfile():
                    raise ValueError('Expected regular OpenCode runtime file: ' + name)
                payload = archive.extractfile(member).read()
                if not payload.startswith(b'\x7fELF'):
                    raise ValueError('Expected ELF OpenCode runtime file: ' + name)
                target = opencode_files[name]
                files[target] = payload
                links.pop(target, None)
                found.add(name)
        missing = set(opencode_files) - found
        if missing:
            raise ValueError('Missing OpenCode runtime file: ' + ', '.join(sorted(missing)))
    if not ndk:
        raise ValueError('NDK path required to build Android CLI launchers')
    host = 'darwin-x86_64' if platform.system() == 'Darwin' else 'linux-x86_64'
    compiler = pathlib.Path(ndk) / 'toolchains/llvm/prebuilt' / host / 'bin/aarch64-linux-android26-clang'
    launcher = cache / 'agent-launcher'
    flags = ['-O2', '-fPIE', '-pie', '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384']
    subprocess.run([str(compiler), *flags, str(root / 'agent_launcher.c'), '-o', str(launcher)], check=True)
    pi_identity = add_pi(root, files)
    for name in ('npm', 'npx', 'claude', *(['pi'] if pi_identity else [])):
        files['bin/' + name] = launcher.read_bytes()
        links.pop('bin/' + name, None)
    if lock.get('bundles'):
        opencode_launcher = cache / 'opencode-launcher'
        subprocess.run([str(compiler), *flags, str(root / 'opencode_launcher.c'), '-o', str(opencode_launcher)], check=True)
        files['bin/opencode'] = opencode_launcher.read_bytes()
        links.pop('bin/opencode', None)
    files['share/mobby/agents.lock.json'] = lock_path.read_bytes()
    identity = lock_path.read_bytes() + (root / 'agent_launcher.c').read_bytes()
    identity += pi_identity
    opencode_source = root / 'opencode_launcher.c'
    if opencode_source.exists():
        identity += opencode_source.read_bytes()
    return hashlib.sha256(identity).hexdigest()
