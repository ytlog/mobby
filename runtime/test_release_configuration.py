"""Check that hosted release builds install the SDKs required by project modules."""
import pathlib
import json
import sys
import re
import os
import subprocess
import tempfile
import textwrap
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class ReleaseConfigurationTest(unittest.TestCase):
    def test_release_checkout_includes_required_native_submodules(self):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        checkout = workflow.split('      - uses: actions/checkout@', 1)[1].split('\n      - ', 1)[0]
        self.assertIn('path = third_party/llama.cpp', (ROOT / '.gitmodules').read_text())
        self.assertRegex(checkout, r'submodules:\s*recursive',
                         'A cold release build needs the pinned llama.cpp submodule sources')

    def test_sdk_installation_uses_runner_sdk_without_path_entry(self):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        block = workflow.split('      - name: Install Android build dependencies\n', 1)[1].split('\n      - ', 1)[0]
        script = textwrap.dedent(block.split('        run: |\n', 1)[1])
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            sdkmanager = root / 'sdk/cmdline-tools/latest/bin/sdkmanager'
            sdkmanager.parent.mkdir(parents=True)
            sdkmanager.write_text('#!/bin/bash\nprintf "%s\\n" "$*" >> "$SDK_CALL_LOG"\n')
            sdkmanager.chmod(0o755)
            log = root / 'sdk-calls.txt'
            result = subprocess.run(['bash', '-c', script], capture_output=True, text=True,
                                    env={**os.environ, 'PATH': '/usr/bin:/bin',
                                         'ANDROID_HOME': str(root / 'sdk'), 'SDK_CALL_LOG': str(log)})
            self.assertEqual(0, result.returncode, result.stderr)
            calls = log.read_text().splitlines()
            self.assertEqual('--licenses', calls[0])
            self.assertIn('--install platforms;android-35 platforms;android-36', calls[1])

    def test_workflow_installs_required_compile_sdks(self):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        installed = set(re.findall(r'platforms;android-(\d+)', workflow))
        required = set()
        for build in (path for group in ('app', 'runtime', 'conversation', 'model', 'shared') for path in (ROOT / group).rglob('build.gradle.kts') if 'build' not in path.parts):
            required.update(re.findall(r'compileSdk\s*=\s*(\d+)', build.read_text()))
        self.assertTrue(required)
        self.assertEqual(set(), required - installed,
                         'Release runner is missing SDK platforms used by Android modules')

    def test_build_runner_cannot_access_signing_secrets(self):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        build, publish = workflow.split('  publish:\n', 1)
        self.assertNotIn('secrets.', build)
        self.assertNotIn('mobby-release.jks', build)
        self.assertIn('needs: build', publish)
        self.assertNotIn('./gradlew', publish)
        self.assertIn('--ks-pass env:MOBBY_RELEASE_STORE_PASSWORD', publish)
        self.assertIn('trap', publish)

    def test_mapping_upload_contains_only_recipient_encrypted_output(self):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        block = workflow.split('      - name: Save encrypted R8 mapping for crash analysis', 1)[1].split('\n      - ', 1)[0]
        self.assertIn('mapping.txt.age', block)
        self.assertNotIn('path: app/build/outputs/mapping', block)
        self.assertIn('-R .github/r8-mapping-recipient.txt', workflow)
        recipient = (ROOT / '.github/r8-mapping-recipient.txt').read_text().strip()
        self.assertRegex(recipient, r'^age1[a-z0-9]+$')
        self.assertNotIn('AGE-SECRET-KEY', recipient)

    def test_third_party_actions_are_pinned_and_credentials_are_not_persisted(self):
        for file in (ROOT / '.github/workflows').glob('*.yml'):
            workflow = file.read_text()
            for action in re.findall(r'uses:\s*(\S+)', workflow):
                self.assertRegex(action, r'@[^\s]{40}$', action)
                self.assertRegex(action.split('@')[1], r'^[0-9a-f]{40}$')
            self.assertIn('persist-credentials: false', workflow)
        checks = (ROOT / '.github/workflows/runtime-checks.yml').read_text()
        self.assertIn('fetch-depth: 0', checks)
        self.assertIn('git --log-opts=--all --redact', checks)

    def test_replacement_publishes_assets_then_moves_tag_and_preserves_notes(self):
        result, calls, _ = self.run_publish("old", "old")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["api", "release", "release", "api", "release"], [call[0] for call in calls])
        self.assertEqual("upload", calls[2][1])
        self.assertIn("--clobber", calls[2])
        self.assertIn("PATCH", calls[3])
        self.assertIn("sha=new", calls[3])
        self.assertEqual(["release", "edit", "v0.1.0", "--target", "new"], calls[4],
                         "Rebuilding must leave authored release notes unchanged")

    def test_changed_tag_aborts_without_upload_or_tag_mutation(self):
        result, calls, _ = self.run_publish("old", "changed")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, len(calls))
        self.assertIn("Release tag changed", result.stdout)

    def test_new_version_creates_release_without_replacement(self):
        result, calls, _ = self.run_publish("", "unused")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, len(calls))
        self.assertEqual(["release", "create", "v0.1.0"], calls[0][:3])
        self.assertNotIn("--clobber", calls[0])

    def run_publish(self, previous_sha, current_sha):
        workflow = (ROOT / '.github/workflows/release.yml').read_text()
        block = workflow.split('      - name: Publish GitHub Release\n', 1)[1].split('\n      - ', 1)[0]
        script = textwrap.dedent(block.split('        run: |\n', 1)[1])
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            (root / 'release-assets').mkdir()
            (root / 'release-assets/app.apk').write_bytes(b'fixture')
            log = root / 'calls.jsonl'
            gh = root / 'gh'
            gh.write_text(f"#!{sys.executable}\n" + textwrap.dedent("""
                import json, os, sys
                args = sys.argv[1:]
                with open(os.environ['GH_CALL_LOG'], 'a') as output:
                    output.write(json.dumps(args) + '\\n')
                if args[0] == 'api' and '--method' not in args:
                    print(os.environ['CURRENT_TAG_SHA'])
                elif args[:2] == ['release', 'view']:
                    print('Existing authored release notes')
            """))
            gh.chmod(0o755)
            result = subprocess.run(['bash', '-c', script], cwd=root, text=True, capture_output=True,
                env={**os.environ, 'PATH': str(root) + os.pathsep + os.environ['PATH'],
                    'GH_CALL_LOG': str(log), 'CURRENT_TAG_SHA': current_sha,
                    'PREVIOUS_TAG_SHA': previous_sha, 'REPLACE_EXISTING': 'true' if previous_sha else 'false',
                    'GITHUB_SHA': 'new', 'GITHUB_REPOSITORY': 'owner/repo',
                    'VERSION': '0.1.0', 'VERSION_CODE': '2', 'RUNNER_TEMP': str(root)})
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            notes_file = root / 'release-notes.md'
            return result, calls, notes_file.read_text() if notes_file.exists() else ''


if __name__ == '__main__':
    unittest.main()
