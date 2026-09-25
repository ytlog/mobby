package com.github.ytlog.mobby.android.localmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileGgufPolicyTest {
    @Test fun latestMobileModelsAppearWithoutTheirAuxiliaryWeights() {
        assertTrue(MobileGgufPolicy.supports("ggml-org/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q4_0.gguf", 563_036_064))
        assertTrue(MobileGgufPolicy.supports("ggml-org/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q8_0.gguf", 833_592_096))
        assertTrue(MobileGgufPolicy.supports("ggml-org/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q4_0.gguf", 2_841_481_184))
        assertFalse(MobileGgufPolicy.supports("ggml-org/gemma-4-E2B-it-GGUF", "mtp-gemma-4-E2B-it-Q4_0.gguf", 59_235_872))
        assertFalse(MobileGgufPolicy.supports("ggml-org/gemma-4-E2B-it-GGUF", "mmproj-gemma-4-E2B-it-Q8_0.gguf", 557_368_064))
        assertFalse(MobileGgufPolicy.supports("ggml-org/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q8_0.gguf", 4_967_497_152))
    }
}
