"""Host regression for native pairing entropy; no device, credentials, or network."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


class ScreenAdbNativeTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('cc'), 'A host C compiler is required')
    def test_pairing_ephemeral_keys_do_not_follow_libc_prng_state(self):
        source = ROOT / 'third_party/spake2-java/android/src/main/cpp/spake2-c'
        with tempfile.TemporaryDirectory() as directory:
            executable = Path(directory) / 'entropy-test'
            subprocess.run([shutil.which('cc'), '-I' + str(source / 'include'),
                            str(ROOT / 'runtime/device-plugins/spake2_entropy_test.c'),
                            str(source / 'spake2.c'), str(source / 'sha512.c'), '-o', str(executable)],
                           check=True, timeout=30)
            subprocess.run([str(executable)], check=True, capture_output=True, timeout=10)
