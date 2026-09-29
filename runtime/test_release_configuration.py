"""Check that hosted release builds install the SDKs required by project modules."""
import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class ReleaseConfigurationTest(unittest.TestCase):
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
