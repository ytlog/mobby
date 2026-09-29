"""Fail before a release build when a locked dependency URL is no longer available."""
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import subprocess


def check_sources(items):
    def check(item):
        result = subprocess.run(['curl', '-sSLI', '--max-time', '30', '-o', '/dev/null',
                                 '-w', '%{http_code}', item['url']], capture_output=True, text=True)
        if result.returncode or result.stdout != '200':
            return f"{item['name']}: HTTP {result.stdout}, curl exit {result.returncode}"
        return None

    with ThreadPoolExecutor(max_workers=4) as pool:
        failures = [failure for failure in pool.map(check, items) if failure]
    if failures:
        raise RuntimeError('Locked dependency sources unavailable:\n' + '\n'.join(failures))


if __name__ == '__main__':
    root = Path(__file__).resolve().parent
    lock = json.loads((root / 'agents.lock.json').read_text())
    items = lock['packages'] + lock['npm'] + lock.get('bundles', [])
    bootstrap = json.loads((root / 'bootstrap.lock.json').read_text())
    items.append({'name': 'termux-bootstrap', 'url': bootstrap['url']})
    check_sources(items)
    print(f'All {len(items)} locked dependency sources are available')
