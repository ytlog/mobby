package com.github.ytlog.mobby.android.programs.base.ai

import android.content.Context
import android.content.SharedPreferences
import com.github.ytlog.mobby.android.programs.base.storage.invalid
import com.github.ytlog.mobby.android.programs.base.storage.schema
import com.github.ytlog.mobby.android.programs.base.storage.stringType
import com.github.ytlog.mobby.android.programs.base.storage.text
import com.github.ytlog.mobby.android.programs.capability.*
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.ZoneId

/** The gateway and model the user chose for one program, with its daily limits. The program cannot change any of it. */
data class AiBinding(
    val gatewayId: String,
    val model: String,
    val callsPerDay: Int = DEFAULT_CALLS_PER_DAY,
    val tokensPerDay: Long = DEFAULT_TOKENS_PER_DAY,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
) {
    init {
        require(gatewayId.isNotBlank() && model.isNotBlank()) { "gateway and model are required" }
        require(callsPerDay in 1..10_000 && tokensPerDay in 1..100_000_000 && maxOutputTokens in 1..64_000) { "limits out of range" }
    }

    companion object {
        const val DEFAULT_CALLS_PER_DAY = 50
        const val DEFAULT_TOKENS_PER_DAY = 100_000L
        const val DEFAULT_MAX_OUTPUT_TOKENS = 2_000
    }
}

data class AiUsage(val calls: Int, val tokens: Long)

data class AiMessage(val role: String, val text: String)
data class AiRequest(val system: String?, val messages: List<AiMessage>, val maxOutputTokens: Int)
/** Token counts are null when the gateway did not report them. */
data class AiReply(val text: String, val inputTokens: Long?, val outputTokens: Long?)

/** Failures a gateway call can end in; the reason is a fixed code, never an upstream message that could quote credentials. */
class ModelCallException(val reason: String, val effect: Effect) : RuntimeException(reason)

/** Choices for the install and details pages. */
data class ModelGateway(val id: String, val label: String, val models: List<String>)

/** The host's configured gateways. Implementations call the gateway themselves; keys never reach the program. */
interface ModelGatewayPort {
    fun gateways(): List<ModelGateway>
    /** The gateway and model conversations use by default, offered first when a program asks for `ai.call`. */
    fun preferred(): Pair<String, String>?
    suspend fun complete(gatewayId: String, model: String, request: AiRequest): AiReply
}

/** Per-program bindings and today's usage, kept with the program platform's own preferences. */
class AiSettings(context: Context, private val today: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) }) {
    private val bindings: SharedPreferences = context.getSharedPreferences("program-ai", Context.MODE_PRIVATE)
    private val usage: SharedPreferences = context.getSharedPreferences("program-ai-usage", Context.MODE_PRIVATE)

    fun binding(programId: String): AiBinding? = bindings.getString(programId, null)?.let { raw ->
        val value = Json.parseToJsonElement(raw).jsonObject
        AiBinding(value.getValue("gatewayId").jsonPrimitive.content, value.getValue("model").jsonPrimitive.content,
            value.getValue("callsPerDay").jsonPrimitive.int, value.getValue("tokensPerDay").jsonPrimitive.long,
            value.getValue("maxOutputTokens").jsonPrimitive.int)
    }

    fun bind(programId: String, binding: AiBinding) {
        bindings.edit().putString(programId, buildJsonObject {
            put("gatewayId", binding.gatewayId); put("model", binding.model); put("callsPerDay", binding.callsPerDay)
            put("tokensPerDay", binding.tokensPerDay); put("maxOutputTokens", binding.maxOutputTokens)
        }.toString()).apply()
    }

    fun forget(programId: String) {
        bindings.edit().remove(programId).apply()
        usage.edit().apply { usage.all.keys.filter { it.startsWith("$programId|") }.forEach(::remove) }.apply()
    }

    fun usage(programId: String): AiUsage {
        val key = "$programId|${today()}"
        return AiUsage(usage.getInt("$key|calls", 0), usage.getLong("$key|tokens", 0))
    }

    @Synchronized fun record(programId: String, tokens: Long) {
        val key = "$programId|${today()}"
        // Earlier days are dropped as today's counters are written; only today's count matters for the limits.
        val stale = usage.all.keys.filter { it.startsWith("$programId|") && !it.startsWith("$key|") }
        usage.edit().apply {
            stale.forEach(::remove)
            putInt("$key|calls", usage.getInt("$key|calls", 0) + 1)
            putLong("$key|tokens", usage.getLong("$key|tokens", 0) + tokens)
        }.apply()
    }
}

/**
 * `ai.call`: one text-only, non-streaming model request through the gateway the user chose for this program.
 * Images, tools and any other field are refused by the schema rather than dropped.
 */
class AiCallCapability(private val port: ModelGatewayPort, private val settings: AiSettings) : CapabilityHandler {
    override val descriptor = CapabilityDescriptor("ai.call", 1, CapabilityKind.COMMAND,
        schema(listOf("messages"),
            "system" to stringType(MAX_TEXT),
            "messages" to buildJsonObject {
                put("type", "array"); put("minItems", 1); put("maxItems", MAX_MESSAGES)
                put("items", schema(listOf("role", "text"), "role" to buildJsonObject {
                    put("type", "string"); put("enum", JsonArray(listOf(JsonPrimitive("user"), JsonPrimitive("assistant"))))
                }, "text" to stringType(MAX_TEXT)))
            },
            "maxOutputTokens" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 64_000) }),
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("text", buildJsonObject { put("type", "string") }); put("model", buildJsonObject { put("type", "string") })
                put("usage", buildJsonObject { put("type", "object") })
            })
        },
        ScopeKind.NONE, setOf(SideEffect.SEND, SideEffect.NETWORK), emptyList(), setOf(Surface.BACKGROUND, Surface.UI),
        120_000, true, CapabilityLimits(256 * 1024, 10))

    override fun availability(): Availability =
        if (port.gateways().isNotEmpty()) Availability.Available else Availability.Unavailable(AvailabilityReason.CONDITION_UNMET)

    override suspend fun invoke(call: CapabilityCall): CapabilityOutcome {
        val programId = call.context.programId
        val binding = settings.binding(programId)
            ?: throw CapabilityException(ProgramErrorCode.CONDITION_UNMET, "no_model_chosen_for_program")
        val messages = (call.args["messages"] as? JsonArray ?: invalid("messages_required")).map { item ->
            val value = item as? JsonObject ?: invalid("message_must_be_object")
            val role = value.text("role")?.takeIf { it == "user" || it == "assistant" } ?: invalid("message_role_must_be_user_or_assistant")
            AiMessage(role, value.text("text") ?: invalid("message_text_required"))
        }
        if (messages.isEmpty() || messages.size > MAX_MESSAGES) invalid("messages_needs_1_to_$MAX_MESSAGES")
        if (messages.last().role != "user") invalid("last_message_must_be_from_user")
        val asked = (call.args["maxOutputTokens"] as? JsonPrimitive)?.intOrNull
        if (asked != null && asked > binding.maxOutputTokens) invalid("maxOutputTokens_above_limit_${binding.maxOutputTokens}")
        val used = settings.usage(programId)
        if (used.calls >= binding.callsPerDay) throw CapabilityException(ProgramErrorCode.QUOTA_EXCEEDED, "daily_ai_calls_used")
        if (used.tokens >= binding.tokensPerDay) throw CapabilityException(ProgramErrorCode.QUOTA_EXCEEDED, "daily_ai_tokens_used")
        val request = AiRequest(call.args.text("system"), messages, asked ?: binding.maxOutputTokens)
        val reply = try { port.complete(binding.gatewayId, binding.model, request) }
            catch (failure: ModelCallException) {
                // A request that may have reached the gateway still counts, so retries cannot slip past the daily limit.
                if (failure.effect != Effect.NONE) settings.record(programId, request.maxOutputTokens.toLong())
                throw CapabilityException(ProgramErrorCode.FAILED, failure.reason, failure.effect)
            }
        // Without reported usage, charge a conservative estimate so the daily token limit still holds.
        val estimated = reply.inputTokens == null || reply.outputTokens == null
        val tokens = if (estimated) estimate(request) else reply.inputTokens!! + reply.outputTokens!!
        settings.record(programId, tokens)
        return CapabilityOutcome(Effect.CONFIRMED, buildJsonObject {
            put("text", reply.text); put("model", binding.model)
            put("usage", buildJsonObject {
                put("inputTokens", reply.inputTokens?.let(::JsonPrimitive) ?: JsonNull)
                put("outputTokens", reply.outputTokens?.let(::JsonPrimitive) ?: JsonNull)
                put("countedTokens", tokens); put("estimated", estimated)
            })
        })
    }

    private fun estimate(request: AiRequest): Long =
        ((request.system.orEmpty().length + request.messages.sumOf { it.text.length }) / 2 + request.maxOutputTokens).toLong()

    companion object {
        private const val MAX_TEXT = 100_000
        private const val MAX_MESSAGES = 50
    }
}
