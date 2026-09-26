package com.github.ytlog.mobby.android.runtime.android.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewaySpeechFormatterTest {
    @Test fun `responses extracts text without reasoning or tool content`() {
        val response = """{"object":"response","output":[{"type":"reasoning","content":[{"type":"text","text":"hidden"}]},{"type":"message","content":[{"type":"output_text","text":"你好，世界。"}]}]}"""
        assertEquals("你好，世界。", parseFormattedSpeech(GatewayProtocol.RESPONSES, response))
    }

    @Test fun `messages extracts text and rejects error`() {
        assertEquals("你好，世界。", parseFormattedSpeech(GatewayProtocol.MESSAGES,
            """{"type":"message","content":[{"type":"text","text":"你好，世界。"}]}"""))
        assertNull(parseFormattedSpeech(GatewayProtocol.MESSAGES, """{"error":{"message":"failed"}}"""))
    }
}
