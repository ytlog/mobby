import unittest

from check_sherpa_jni_mapping import violations


class SherpaJniMappingTest(unittest.TestCase):
    SEED = "com.k2fsa.sherpa.onnx.OnlineRecognizerConfig: java.lang.String decodingMethod"

    def test_renamed_jni_fields_and_classes_are_rejected(self):
        mapping = """com.k2fsa.sherpa.onnx.OnlineRecognizerConfig -> a.b:
    java.lang.String decodingMethod -> c
"""
        self.assertEqual(2, len(violations(mapping, self.SEED)))

    def test_stable_jni_fields_and_classes_are_accepted(self):
        mapping = """com.k2fsa.sherpa.onnx.OnlineRecognizerConfig -> com.k2fsa.sherpa.onnx.OnlineRecognizerConfig:
    java.lang.String decodingMethod -> decodingMethod
"""
        self.assertEqual([], violations(mapping, self.SEED))

    def test_removed_jni_field_is_rejected(self):
        mapping = "com.k2fsa.sherpa.onnx.OnlineRecognizerConfig -> com.k2fsa.sherpa.onnx.OnlineRecognizerConfig:"
        self.assertEqual(1, len(violations(mapping, "")))


if __name__ == "__main__":
    unittest.main()
