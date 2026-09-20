import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('prepare', Path(__file__).with_name('prepare_bootstrap.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)

class BootstrapTest(unittest.TestCase):
    def fixture(self, root, contents):
        (root / 'cache').mkdir()
        archive = root / 'cache/bootstrap-aarch64.zip'
        with zipfile.ZipFile(archive, 'w') as stream:
            for name, data in contents.items():
                stream.writestr(name, data)
        (root / 'bootstrap.lock.json').write_text(json.dumps({'sha256': hashlib.sha256(archive.read_bytes()).hexdigest()}))
        prepare.ROOT = root
        return archive

    def test_preserves_assets_and_maps_elf_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'bin/bash': b'\x7fELFbash', 'lib/libc.so.1': b'\x7fELFlib', 'etc/config': b'data'})
            prepare.prepare(root / 'out')
            mapping = json.loads((root / 'out/assets/bootstrap/binaries.json').read_text())
            self.assertEqual(mapping['bin/bash'], 'libbash.so')
            self.assertEqual(len(mapping), 2)
            with zipfile.ZipFile(root / 'out/assets/bootstrap/data.zip') as stream:
                self.assertEqual(stream.read('etc/config'), b'data')
                self.assertNotIn('bin/bash', stream.namelist())

    def test_rejects_modified_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = self.fixture(root, {'bin/bash': b'\x7fELF'})
            archive.write_bytes(archive.read_bytes() + b'tampered')
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                prepare.prepare(root / 'out')

    def test_rejects_archive_traversal(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'../outside': b'bad'})
            with self.assertRaisesRegex(ValueError, 'Unsafe'):
                prepare.prepare(root / 'out')


class DependencyArchiveTest(unittest.TestCase):
    def test_dependency_paths_cannot_escape_prefix(self):
        from agent_bundle import safe_name
        for name in ('../outside', '/absolute', 'lib/../../outside'):
            with self.assertRaises(ValueError):
                safe_name(name)

    def test_locked_dependency_rejects_corruption(self):
        from agent_bundle import checked_download
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'file.deb').write_bytes(b'corrupted')
            with self.assertRaisesRegex(ValueError, 'Integrity mismatch'):
                checked_download({'name': 'test', 'url': 'https://example.invalid/file.deb', 'sha256': '0' * 64}, root)

if __name__ == '__main__':
    unittest.main()
