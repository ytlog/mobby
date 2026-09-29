import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import maven_notices


class MavenNoticesTest(unittest.TestCase):
    def fixture(self, root):
        notices = root / 'third_party/maven'
        notices.mkdir(parents=True)
        (notices / 'dependencies.lock.json').write_text(json.dumps({'example:library:1': ['MIT']}))
        artifact = root / 'library.jar'
        original = 'Copyright Example Authors\nPermission and disclaimer from the upstream fixture.'
        with zipfile.ZipFile(artifact, 'w') as archive:
            archive.writestr('META-INF/LICENSE', original)
            archive.writestr('META-INF/NOTICE', 'Original additional attribution')
        item = {'group': 'example', 'name': 'library', 'version': '1', 'file': str(artifact)}
        return item, original

    def test_original_license_and_notice_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); item, original = self.fixture(root)
            with patch.object(maven_notices, 'PROJECT', root), patch.object(maven_notices, 'pom_licenses', return_value=['MIT License']):
                maven_notices.generate([item], root, root / 'output.json')
            text = json.loads((root / 'output.json').read_text())[0]['text']
            self.assertIn(original, text)
            self.assertIn('Original additional attribution', text)

    def test_duplicate_resolution_does_not_create_duplicate_ui_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); item, _ = self.fixture(root)
            with patch.object(maven_notices, 'PROJECT', root), patch.object(maven_notices, 'pom_licenses', return_value=['MIT License']):
                maven_notices.generate([item, item], root, root / 'output.json')
            rows = json.loads((root / 'output.json').read_text())
            self.assertEqual(1, len(rows))

    def test_added_dependency_cannot_silently_receive_project_license(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); item, _ = self.fixture(root)
            item['version'] = '2'
            with patch.object(maven_notices, 'PROJECT', root), patch.object(maven_notices, 'pom_licenses', return_value=['MIT License']):
                with self.assertRaisesRegex(ValueError, 'must be reviewed'):
                    maven_notices.generate([item], root, root / 'output.json')


if __name__ == '__main__':
    unittest.main()
