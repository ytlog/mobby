import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from release_sources import download_source, validate_inventory


class ReleaseSourcesTest(unittest.TestCase):
    def fixture(self, directory):
        bootstrap = Path(directory) / 'bootstrap.zip'
        with zipfile.ZipFile(bootstrap, 'w') as archive:
            archive.writestr('var/lib/dpkg/status', 'Package: bash\nVersion: 5.3.9\n\nPackage: git\nVersion: 1.0\n')
        agents = {'packages': [{'name': 'git', 'version': '2.55.0'}]}
        raw = json.dumps(agents).encode()
        digest = hashlib.sha256(bootstrap.read_bytes()).hexdigest()
        manifest = {'bootstrap_sha256': digest, 'agents_lock_sha256': hashlib.sha256(raw).hexdigest(),
                    'packages': [{'name': 'bash', 'version': '5.3.9', 'license': 'GPL-3.0', 'source_inputs': ['bash-source']},
                                 {'name': 'git', 'version': '2.55.0', 'license': 'GPL-2.0', 'source_inputs': ['git-source']}],
                    'inputs': [{'name': 'bash-source'}, {'name': 'git-source'}]}
        return manifest, bootstrap, {'sha256': digest}, agents, raw

    def test_overlay_versions_replace_bootstrap_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            validate_inventory(*self.fixture(directory))

    def test_stale_source_inventory_cannot_accompany_new_binary(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.fixture(directory)
            args[0]['packages'][1]['version'] = '1.0'
            with self.assertRaisesRegex(ValueError, 'does not match the APK'):
                validate_inventory(*args)

    def test_missing_copyleft_sources_block_release(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.fixture(directory)
            del args[0]['packages'][0]['source_inputs']
            with self.assertRaisesRegex(ValueError, 'Missing copyleft'):
                validate_inventory(*args)

    def test_modified_cached_source_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            url = 'https://example.invalid/source.tar.gz'
            (cache / (hashlib.sha256(url.encode()).hexdigest()[:12] + '-source.tar.gz')).write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'integrity mismatch'):
                download_source({'name': 'source', 'url': url, 'sha256': 'a' * 64}, cache)

    def test_insecure_source_url_is_rejected_before_network(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'HTTPS'):
                download_source({'name': 'source', 'url': 'http://example.invalid/src', 'sha256': 'a' * 64}, Path(directory))


if __name__ == '__main__':
    unittest.main()
