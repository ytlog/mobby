package com.github.ytlog.mobby.android.programs

import com.github.ytlog.mobby.android.programs.base.ai.*
import com.github.ytlog.mobby.android.programs.call.CallContext
import com.github.ytlog.mobby.android.programs.call.SchemaCheck
import com.github.ytlog.mobby.android.programs.capability.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiCallCapabilityTest {
    private class Port(var reply: () -> AiReply = { AiReply("总结好了", 120, 30) }) : ModelGatewayPort {
        val requests = mutableListOf<Triple<String, String, AiRequest>>()
        override fun gateways() = listOf(ModelGateway("g1", "api.example.com", listOf("m1")))
        override fun preferred() = "g1" to "m1"
        override suspend fun complete(gatewayId: String, model: String, request: AiRequest): AiReply {
            requests += Triple(gatewayId, model, request); return reply()
        }
    }
    private var day = LocalDate.of(2026, 10, 30)
    private val settings = AiSettings(RuntimeEnvironment.getApplication()) { day }
    private val port = Port()
    private val handler = AiCallCapability(port, settings)
    private val root = Files.createTempDirectory("ai").toFile()
    private fun call(args: String) = runBlocking {
        handler.invoke(CapabilityCall(CallContext("p", "1", Surface.BACKGROUND, runId = "r"), Json.parseToJsonElement(args).jsonObject,
            ProgramSpace("p", File(root, "f"), File(root, "t"))) { false })
    }
    private fun failure(args: String) = runCatching { call(args) }.exceptionOrNull() as CapabilityException
    private val ask = """{"system":"只用一句话","messages":[{"role":"user","text":"总结今天的账"}]}"""

    @Test fun `a program without a chosen model cannot call`() {
        assertEquals(ProgramErrorCode.CONDITION_UNMET, failure(ask).code)
        assertTrue(port.requests.isEmpty())
    }

    @Test fun `the call goes to the chosen gateway and model and reports usage`() {
        settings.bind("p", AiBinding("g1", "m1"))
        val result = call(ask).result.jsonObject
        assertEquals("总结好了", result.getValue("text").jsonPrimitive.content)
        assertEquals(150, result.getValue("usage").jsonObject.getValue("countedTokens").jsonPrimitive.int)
        val (gateway, model, request) = port.requests.single()
        assertEquals("g1" to "m1", gateway to model)
        assertEquals(AiRequest("只用一句话", listOf(AiMessage("user", "总结今天的账")), AiBinding.DEFAULT_MAX_OUTPUT_TOKENS), request)
        assertEquals(AiUsage(1, 150), settings.usage("p"))
    }

    @Test fun `daily calls, daily tokens and output size are capped`() {
        settings.bind("p", AiBinding("g1", "m1", callsPerDay = 2, tokensPerDay = 1_000, maxOutputTokens = 100))
        assertEquals(ProgramErrorCode.INVALID_ARGUMENT,
            failure("""{"messages":[{"role":"user","text":"x"}],"maxOutputTokens":101}""").code)
        call(ask); call(ask)
        assertEquals("daily_ai_calls_used", failure(ask).reason)
        day = day.plusDays(1)
        assertEquals(AiUsage(0, 0), settings.usage("p"))
        port.reply = { AiReply("长", 900, 100) }
        call(ask)
        assertEquals("daily_ai_tokens_used", failure(ask).reason)
        assertEquals(ProgramErrorCode.QUOTA_EXCEEDED, failure(ask).code)
    }

    @Test fun `missing usage is charged as an estimate and a call that may have reached the gateway still counts`() {
        settings.bind("p", AiBinding("g1", "m1", maxOutputTokens = 100))
        port.reply = { AiReply("好", null, null) }
        val usage = call(ask).result.jsonObject.getValue("usage").jsonObject
        assertTrue(usage.getValue("estimated").jsonPrimitive.boolean)
        assertTrue(usage.getValue("countedTokens").jsonPrimitive.long >= 100)
        port.reply = { throw ModelCallException("gateway_timeout", Effect.UNKNOWN) }
        val failed = failure(ask)
        assertEquals(ProgramErrorCode.FAILED to Effect.UNKNOWN, failed.code to failed.effect)
        assertEquals(2, settings.usage("p").calls)
    }

    @Test fun `the platform registry accepts the schema and it refuses an empty conversation`() {
        assertSame(handler, CapabilityRegistry(listOf(handler))["ai.call"])
        assertTrue(SchemaCheck.problems(handler.descriptor.inputSchema, Json.parseToJsonElement("""{"messages":[]}""").jsonObject).isNotEmpty())
    }

    @Test fun `images, tools and other fields are refused rather than dropped`() {
        assertTrue(SchemaCheck.problems(handler.descriptor.inputSchema,
            Json.parseToJsonElement("""{"messages":[{"role":"user","text":"x","image":"a.png"}]}""").jsonObject).isNotEmpty())
        assertTrue(SchemaCheck.problems(handler.descriptor.inputSchema,
            Json.parseToJsonElement("""{"messages":[{"role":"user","text":"x"}],"tools":[]}""").jsonObject).isNotEmpty())
        assertTrue(SchemaCheck.problems(handler.descriptor.inputSchema, Json.parseToJsonElement(ask).jsonObject).isEmpty())
    }
}
