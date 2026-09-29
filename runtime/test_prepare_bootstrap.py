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
            self.fixture(root, {'bin/bash': b'\x7fELFbash', 'bin/pi': b'\x7fELFpi', 'lib/libc.so.1': b'\x7fELFlib', 'etc/config': b'data'})
            prepare.prepare(root / 'out')
            mapping = json.loads((root / 'out/assets/bootstrap/binaries.json').read_text())
            self.assertEqual(mapping['bin/bash'], 'libbash.so')
            self.assertEqual(mapping['bin/pi'], 'libpi.so')
            self.assertEqual(len(mapping), 3)
            with zipfile.ZipFile(root / 'out/assets/bootstrap/data.zip') as stream:
                self.assertEqual(stream.read('etc/config'), b'data')
                self.assertNotIn('bin/bash', stream.namelist())
                self.assertEqual(stream.read('share/mobby/licenses/Codex-LICENSE'), (prepare.PROJECT / 'third_party/codex/LICENSE').read_bytes())
                self.assertEqual(stream.read('share/mobby/licenses/bubblewrap-COPYING'), (prepare.PROJECT / 'third_party/bubblewrap/COPYING').read_bytes())

    def test_rebuild_removes_obsolete_native_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'bin/bash': b'\x7fELFbash'})
            native = root / 'out/jniLibs/arm64-v8a'
            native.mkdir(parents=True)
            (native / 'libobsolete.so').write_bytes(b'old')
            prepare.prepare(root / 'out')
            self.assertEqual({p.name for p in native.iterdir()}, {'libbash.so'})

    def test_viewer_omits_unused_termux_templates_without_changing_upstream_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'bin/bash': b'\x7fELFbash',
                                'share/LICENSES/GPL-3.0.txt': b'GPL original',
                                'share/LICENSES/AGPL-V3.txt': b'unused AGPL template'})
            prepare.prepare(root / 'out')
            notices = json.loads((root / 'out/assets/third-party/runtime.json').read_text())
            ids = {item['id'] for item in notices}
            self.assertIn('share/LICENSES/GPL-3.0.txt', ids)
            self.assertNotIn('share/LICENSES/AGPL-V3.txt', ids)
            with zipfile.ZipFile(root / 'out/assets/bootstrap/data.zip') as archive:
                self.assertEqual(archive.read('share/LICENSES/AGPL-V3.txt'), b'unused AGPL template')

    def test_offline_notices_include_static_codex_and_bun_dependencies(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, {'bin/bash': b'\x7fELFbash'})
            prepare.prepare(root / 'out')
            notices = json.loads((root / 'out/assets/third-party/runtime.json').read_text())
            codex = next(item for item in notices if item['id'] == 'codex-dependency:aws-lc-sys:0.39.0')
            self.assertIn('Copyright', codex['text'])
            polyfill = next(item for item in notices if item['id'] == 'bun-dependency:hmac-drbg:1.0.1')
            self.assertIn('Copyright Fedor Indutny', polyfill['text'])
            self.assertIn('Permission is hereby granted', polyfill['text'])

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

class PiPayloadTest(unittest.TestCase):
    def fixture(self, root):
        source = root / 'pi-package'; source.mkdir()
        for name in ('package.json', 'package-lock.json'):
            (source / name).write_text('{}')
        target = root / 'cache/pi-payload'; target.mkdir(parents=True)
        (target / '.mobby-lock').write_text(hashlib.sha256(b'{}{}').hexdigest())
        entry = target / 'node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js'
        entry.parent.mkdir(parents=True); entry.write_text('locked fixture')
        return target

    def test_portable_payload_retains_licenses_and_omits_desktop_binaries(self):
        from agent_bundle import add_pi
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); target = self.fixture(root)
            license = target / 'node_modules/@earendil-works/pi-coding-agent/LICENSE'
            license.write_text('fixture license')
            binary = target / 'node_modules/@earendil-works/pi-tui/native/linux-arm64/helper.node'
            binary.parent.mkdir(parents=True); binary.write_bytes(b'\x7fELFdesktop-fixture')
            (target / 'node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js.map').write_text('source map')
            files = {}
            with patch('agent_bundle.subprocess.run') as install:
                self.assertEqual(add_pi(root, files), b'{}{}')
                install.assert_not_called()
            self.assertEqual(files['lib/node_modules/@earendil-works/pi-coding-agent/LICENSE'], b'fixture license')
            self.assertTrue(any(path.endswith('/cli.js') for path in files))
            self.assertFalse(any('/native/' in path or path.endswith('.map') for path in files))

    def test_unexpected_native_dependency_fails_the_build(self):
        from agent_bundle import add_pi
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); target = self.fixture(root)
            (target / 'node_modules/incompatible.node').write_bytes(b'\x7fELFwrong-architecture')
            with self.assertRaisesRegex(ValueError, 'Unexpected native Pi dependency'):
                add_pi(root, {})

class ExternalClaudePayloadTest(unittest.TestCase):
    def test_claude_program_is_not_downloaded_or_redistributed_in_apk(self):
        from unittest.mock import patch
        from agent_bundle import add_agents
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = root / 'cache'; cache.mkdir()
            (cache / 'agent-launcher').write_bytes(b'\x7fELFlauncher-fixture')
            (root / 'agent_launcher.c').write_text('fixture launcher')
            (root / 'agents.lock.json').write_text(json.dumps({'packages': [], 'npm': [{
                'name': '@anthropic-ai/claude-code', 'delivery': 'device-download',
                'url': 'https://registry.npmjs.org/claude.tgz', 'integrity': 'sha512-fixture'}]}))
            files = {}
            with patch('agent_bundle.checked_download') as download, patch('agent_bundle.subprocess.run'):
                add_agents(root, files, {}, '/unused-fixture-ndk')
            download.assert_not_called()
            self.assertIn('bin/claude', files)
            self.assertFalse(any('@anthropic-ai/claude-code/' in name for name in files))

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

    def test_opencode_runtime_is_installed_beside_its_android_launcher(self):
        import io
        import tarfile
        from unittest.mock import patch
        from agent_bundle import add_agents
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            cache = root / 'cache'; cache.mkdir()
            source = cache / 'opencode-termux.tar.gz'
            names = ['opencode', 'ld-musl-aarch64.so.1', 'libc.musl-aarch64.so.1', 'libgcc_s.so.1', 'libstdc++.so.6', 'libstdc++.so.6.0.33']
            payloads = {name: b'\x7fELF' + name.encode() for name in names}
            with tarfile.open(source, 'w:gz') as archive:
                for name, data in payloads.items():
                    member = tarfile.TarInfo(name)
                    if name == 'libstdc++.so.6':
                        member.type = tarfile.SYMTYPE
                        member.linkname = 'libstdc++.so.6.0.33'
                        archive.addfile(member)
                        continue
                    member.size = len(data)
                    archive.addfile(member, io.BytesIO(data))
            (root / 'agents.lock.json').write_text(json.dumps({'packages': [], 'npm': [], 'bundles': [{
                'name': 'opencode-termux', 'url': 'https://example.invalid/opencode-termux.tar.gz',
                'sha256': hashlib.sha256(source.read_bytes()).hexdigest()}]}))
            (root / 'agent_launcher.c').write_text('fixture')
            (cache / 'agent-launcher').write_bytes(b'\x7fELFnode-launcher')
            (cache / 'opencode-launcher').write_bytes(b'\x7fELFopencode-launcher')
            files, links = {}, {}
            with patch('agent_bundle.subprocess.run'):
                add_agents(root, files, links, '/unused-fixture-ndk')
            self.assertEqual(files['lib/opencode/opencode'], payloads['opencode'])
            self.assertEqual(files['lib/opencode/libstdc++.so.6.0.33'], payloads['libstdc++.so.6.0.33'])
            self.assertEqual(links['lib/opencode/libstdc++.so.6'], 'libstdc++.so.6.0.33')
            self.assertNotIn('lib/opencode/libstdc++.so.6', files)
            self.assertEqual(files['lib/opencode/ld-musl-aarch64.so.1'], payloads['ld-musl-aarch64.so.1'])
            self.assertEqual(files['bin/opencode'], b'\x7fELFopencode-launcher')
            with tarfile.open(source, 'w:gz') as archive:
                member = tarfile.TarInfo('opencode')
                member.size = len(payloads['opencode'])
                archive.addfile(member, io.BytesIO(payloads['opencode']))
            lock = json.loads((root / 'agents.lock.json').read_text())
            lock['bundles'][0]['sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
            (root / 'agents.lock.json').write_text(json.dumps(lock))
            with patch('agent_bundle.subprocess.run'), self.assertRaisesRegex(ValueError, 'Missing OpenCode runtime file'):
                add_agents(root, {}, {}, '/unused-fixture-ndk')

if __name__ == '__main__':
    unittest.main()
