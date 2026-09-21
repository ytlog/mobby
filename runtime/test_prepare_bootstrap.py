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

    def test_rebuild_removes_obsolete_native_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'bin/bash': b'\x7fELFbash'})
            native = root / 'out/jniLibs/arm64-v8a'
            native.mkdir(parents=True)
            (native / 'libobsolete.so').write_bytes(b'old')
            prepare.prepare(root / 'out')
            self.assertEqual({p.name for p in native.iterdir()}, {'libbash.so'})

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

class CodexPayloadTest(unittest.TestCase):
    def test_codex_sandbox_program_is_installed_from_the_locked_archive(self):
        import io
        import tarfile
        from unittest.mock import patch
        from agent_bundle import add_agents
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = root / 'cache'; cache.mkdir()
            source = cache / 'codex.tgz'
            codex, bwrap = b'\x7fELFcodex-fixture', b'\x7fELFsandbox-fixture'
            with tarfile.open(source, 'w:gz') as archive:
                for path, data in [('bin/codex', codex), ('codex-resources/bwrap', bwrap)]:
                    member = tarfile.TarInfo('package/vendor/aarch64-unknown-linux-musl/' + path)
                    member.size = len(data)
                    archive.addfile(member, io.BytesIO(data))
            (root / 'agents.lock.json').write_text(json.dumps({'packages': [], 'npm': [{
                'name': '@openai/codex', 'url': 'https://example.invalid/codex.tgz',
                'sha256': hashlib.sha256(source.read_bytes()).hexdigest()}]}))
            (root / 'agent_launcher.c').write_text('fixture launcher')
            (cache / 'agent-launcher').write_bytes(b'\x7fELFlauncher-fixture')
            files, links = {}, {}
            with patch('agent_bundle.subprocess.run'):
                add_agents(root, files, links, '/unused-fixture-ndk')
            self.assertEqual(files['bin/codex'], codex)
            self.assertEqual(files.get('bin/bwrap'), bwrap, 'Codex must find its official sandbox helper on PATH')
            # A package update that loses the helper must fail at build time, not on a user's phone.
            with tarfile.open(source, 'w:gz') as archive:
                member = tarfile.TarInfo('package/vendor/aarch64-unknown-linux-musl/bin/codex')
                member.size = len(codex)
                archive.addfile(member, io.BytesIO(codex))
            lock = json.loads((root / 'agents.lock.json').read_text())
            lock['npm'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
            (root / 'agents.lock.json').write_text(json.dumps(lock))
            with patch('agent_bundle.subprocess.run'), self.assertRaisesRegex(ValueError, 'Missing required Codex program: codex-resources/bwrap'):
                add_agents(root, {}, {}, '/unused-fixture-ndk')

if __name__ == '__main__':
    unittest.main()
