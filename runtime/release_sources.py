"""Package pinned corresponding sources next to the release APK, failing closed on drift."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
from urllib.parse import urlsplit
import zipfile

ROOT = Path(__file__).resolve().parent


def final_termux_packages(bootstrap, agents):
    with zipfile.ZipFile(bootstrap) as archive:
        status = archive.read('var/lib/dpkg/status').decode()
    packages = {}
    for block in status.split('\n\n'):
        fields = dict(line.split(': ', 1) for line in block.splitlines() if ': ' in line and not line.startswith(' '))
        if 'Package' in fields:
            packages[fields['Package']] = fields['Version']
    packages.update({item['name']: item['version'] for item in agents['packages']})
    return packages


def validate_inventory(manifest, bootstrap, bootstrap_lock, agents, agents_bytes):
    if hashlib.sha256(bootstrap.read_bytes()).hexdigest() != bootstrap_lock['sha256']:
        raise ValueError('Bootstrap integrity mismatch')
    if manifest['bootstrap_sha256'] != bootstrap_lock['sha256'] or manifest['agents_lock_sha256'] != hashlib.sha256(agents_bytes).hexdigest():
        raise ValueError('Dependency locks changed; corresponding-source inventory must be reviewed')
    packages = {item['name']: item['version'] for item in manifest['packages']}
    if len(packages) != len(manifest['packages']) or packages != final_termux_packages(bootstrap, agents):
        raise ValueError('Termux component inventory does not match the APK')
    inputs = {item['name'] for item in manifest['inputs']}
    if len(inputs) != len(manifest['inputs']):
        raise ValueError('Duplicate source input')
    for package in manifest['packages']:
        if 'GPL' in package['license'] and 'source_inputs' not in package:
            raise ValueError('Missing copyleft source coverage: ' + package['name'])
        if not set(package.get('source_inputs', [])).issubset(inputs):
            raise ValueError('Missing referenced source input')


def download_source(item, cache):
    url = item['url']
    if urlsplit(url).scheme != 'https' or len(item['sha256']) != 64:
        raise ValueError('Source requires HTTPS and pinned SHA-256')
    name = hashlib.sha256(url.encode()).hexdigest()[:12] + '-' + urlsplit(url).path.rsplit('/', 1)[-1]
    target = cache / name
    cache.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as stream:
            temporary = Path(stream.name)
        try:
            subprocess.run(['curl', '-fL', '--proto', '=https', '--proto-redir', '=https', '--retry', '2',
                            '--max-time', '1800', url, '-o', str(temporary)], check=True)
            if hashlib.sha256(temporary.read_bytes()).hexdigest() != item['sha256']:
                raise ValueError('Source integrity mismatch: ' + item['name'])
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
    if hashlib.sha256(target.read_bytes()).hexdigest() != item['sha256']:
        raise ValueError('Cached source integrity mismatch: ' + item['name'])
    return target


def webkit_source(item, cache):
    """GitHub refuses full WebKit snapshots. Include build sources/tools, excluding test sites."""
    checkout = cache / 'webkit-checkout'
    commit = item['commit']
    if len(commit) != 40 or any(char not in '0123456789abcdef' for char in commit):
        raise ValueError('WebKit requires an immutable commit')
    if not (checkout / '.git').is_dir():
        subprocess.run(['git', 'init', str(checkout)], check=True)
        subprocess.run(['git', '-C', str(checkout), 'remote', 'add', 'origin', 'https://github.com/oven-sh/WebKit.git'], check=True)
        subprocess.run(['git', '-C', str(checkout), 'config', 'remote.origin.promisor', 'true'], check=True)
        subprocess.run(['git', '-C', str(checkout), 'config', 'remote.origin.partialclonefilter', 'blob:none'], check=True)
        subprocess.run(['git', '-C', str(checkout), 'sparse-checkout', 'init', '--cone'], check=True)
        subprocess.run(['git', '-C', str(checkout), 'sparse-checkout', 'set', 'Source', 'Tools', 'WebKitLibraries', '.github'], check=True)
    subprocess.run(['git', '-C', str(checkout), 'fetch', '--depth=1', '--filter=blob:none', 'origin', commit], check=True)
    subprocess.run(['git', '-C', str(checkout), 'checkout', '--detach', commit], check=True)
    actual = subprocess.check_output(['git', '-C', str(checkout), 'rev-parse', 'HEAD'], text=True).strip()
    if actual != commit or not (checkout / 'Source/JavaScriptCore/COPYING.LIB').is_file():
        raise ValueError('WebKit checkout incomplete')
    target = cache / ('webkit-' + commit + '.tar.gz')
    subprocess.run(['git', '-C', str(checkout), 'diff', '--exit-code', commit], check=True)
    tracked = subprocess.check_output(['git', '-C', str(checkout), 'ls-files', '-z']).decode().split('\0')
    with tarfile.open(target, 'w:gz') as archive:
        for name in sorted(filter(None, tracked)):
            path = checkout / name
            if path.is_file() or path.is_symlink():
                archive.add(path, arcname='WebKit/' + name, recursive=False)
    return target


def build_bundle(output, cache):
    manifest_path = ROOT / 'release-sources.lock.json'
    manifest = json.loads(manifest_path.read_text())
    agents_bytes = (ROOT / 'agents.lock.json').read_bytes()
    validate_inventory(manifest, ROOT / 'cache/bootstrap-aarch64.zip',
                       json.loads((ROOT / 'bootstrap.lock.json').read_text()), json.loads(agents_bytes), agents_bytes)
    with ThreadPoolExecutor(max_workers=4) as pool:
        paths = list(pool.map(lambda item: download_source(item, cache), manifest['inputs']))
    webkit = webkit_source(manifest['webkit'], cache)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as directory:
        stage = Path(directory)
        project = stage / 'mobby-source.tar'
        subprocess.run(['git', 'archive', '--format=tar', '--prefix=mobby/', 'HEAD', '-o', str(project)], cwd=ROOT.parent, check=True)
        temporary = stage / 'release-sources.tar.gz'
        with tarfile.open(temporary, 'w:gz') as archive:
            archive.add(project, arcname='mobby-source.tar')
            archive.add(manifest_path, arcname='release-sources.lock.json')
            archive.add(ROOT.parent / 'docs/third-party-sources.md', arcname='README.md')
            for item, path in zip(manifest['inputs'], paths):
                archive.add(path, arcname='upstream/' + path.name)
            archive.add(webkit, arcname='upstream/' + webkit.name)
        temporary.replace(output)
    print('Corresponding sources prepared:', output.name, output.stat().st_size, 'bytes')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cache', type=Path, default=ROOT / 'cache/release-sources')
    args = parser.parse_args()
    build_bundle(args.output, args.cache)
