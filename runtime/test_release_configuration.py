"""Check that hosted release builds install the SDKs required by project modules."""
import pathlib
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
        for build in ROOT.glob('*/build.gradle.kts'):
            required.update(re.findall(r'compileSdk\s*=\s*(\d+)', build.read_text()))
        self.assertTrue(required)
        self.assertEqual(set(), required - installed,
                         'Release runner is missing SDK platforms used by Android modules')


if __name__ == '__main__':
    unittest.main()
