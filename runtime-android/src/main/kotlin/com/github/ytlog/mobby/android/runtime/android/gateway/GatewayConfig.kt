package com.github.ytlog.mobby.android.runtime.android.gateway

import com.github.ytlog.mobby.android.localization.AppStrings

import com.github.ytlog.mobby.android.runtime.engine.AgentMode

import kotlinx.serialization.json.*
import java.net.URI

enum class GatewayProtocol(val label: String) { RESPONSES("Responses"), MESSAGES("Messages") }

/** OpenCode's built-in OpenAI provider speaks Responses, the same wire format as Codex. */
internal fun AgentMode.gatewayProtocol(): GatewayProtocol = when (this) {
    AgentMode.CLAUDE -> GatewayProtocol.MESSAGES
    AgentMode.CODEX, AgentMode.OPEN_CODE -> GatewayProtocol.RESPONSES
    AgentMode.SHELL -> error("Shell has no model protocol")
}

data class GatewayModel(val id: String, val name: String)

data class GatewayConfig(
    val endpoint: String = "", val model: String = "", val key: String = "",
    val protocol: GatewayProtocol = GatewayProtocol.RESPONSES,
    val models: List<GatewayModel> = emptyList(), val catalogError: String? = null,
) {
    override fun toString() = "GatewayConfig(protocol=$protocol, credentials=[redacted])"
    fun accepts(modelId: String) = modelId == model || models.any { it.id == modelId }
    /** The process receives only the selected model. The stored default and catalog stay unchanged. */
    fun forRun(modelId: String): GatewayConfig {
        require(accepts(modelId))
        return copy(model = modelId, models = emptyList(), catalogError = null)
    }
    fun validate() {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        require(uri != null && uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null && uri.query == null) { AppStrings.enterAValidGatewayUrlWithoutKeysOrQuery }
        require(model.isNotBlank() && model.length <= 200 && model.none { it.isISOControl() }) { AppStrings.enterAValidModelName }
        require(key.length <= 8192 && key.none { it.isISOControl() }) { AppStrings.invalidKeyFormat }
        require(models.size <= 2_000 && models.all { item ->
            item.id.isNotBlank() && item.id.length <= 200 && item.id.none { it.isISOControl() } &&
                item.name.isNotBlank() && item.name.length <= 120 && item.name.none { it.isISOControl() }
        }) { AppStrings.invalidModelList }
        require(catalogError == null || (catalogError.length <= 200 && catalogError.none { it.isISOControl() })) { AppStrings.invalidModelListDescription }
    }
    fun validateFor(mode: AgentMode) {
        validate()
        require(protocol == mode.gatewayProtocol()) {
            AppStrings.gatewayProtocolMustMatchTheAgentSNativeProtocol
        }
    }
    fun json(): String = buildJsonObject {
        put("endpoint", endpoint); put("model", model); put("key", key); put("protocol", protocol.name.lowercase())
        if (models.isNotEmpty()) putJsonArray("models") {
            models.forEach { item -> addJsonObject { put("id", item.id); put("name", item.name) } }
        }
        catalogError?.let { put("catalogError", it) }
    }.toString()
    companion object {
        fun parse(value: String): GatewayConfig {
            val obj = Json.parseToJsonElement(value).jsonObject
            val models = runCatching {
                obj["models"]?.jsonArray?.mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val id = item["id"]?.jsonPrimitive?.takeIf { it.isString }?.content ?: return@mapNotNull null
                    if (id.isBlank() || id.length > 200 || id.any { it.isISOControl() }) return@mapNotNull null
                    val name = item["name"]?.jsonPrimitive?.takeIf { it.isString }?.content?.take(120) ?: id
                    if (name.any { it.isISOControl() }) return@mapNotNull null
                    GatewayModel(id, name.ifBlank { id })
                }?.distinctBy { it.id }?.take(2_000)
            }.getOrNull().orEmpty()
            val catalogError = obj["catalogError"]?.jsonPrimitive?.takeIf { it.isString }?.content
                ?.takeIf { it.length <= 200 && it.none { char -> char.isISOControl() } }
            return GatewayConfig(obj.getValue("endpoint").jsonPrimitive.content, obj.getValue("model").jsonPrimitive.content,
                obj.getValue("key").jsonPrimitive.content, GatewayProtocol.valueOf(obj.getValue("protocol").jsonPrimitive.content.uppercase()),
                models, catalogError)
        }
    }
}

internal object GatewayEndpoint {
    private val apiSuffix = Regex("(?i)/(chat/completions|responses|messages)$")
    fun base(endpoint: String): String = endpoint.trim().trimEnd('/').replace(apiSuffix, "")
    fun url(endpoint: String, path: String): java.net.URL {
        val parsed = URI(base(endpoint))
        val prefix = parsed.rawPath.orEmpty().ifEmpty { "/v1" }
        return java.net.URL("${parsed.scheme}://${parsed.rawAuthority}$prefix$path")
    }
}

data class GatewayChoice(val id: String, val mode: AgentMode) {
    fun json(): String = buildJsonObject { put("id", id); put("mode", mode.name) }.toString()
    companion object { fun parse(value: String): GatewayChoice = Json.parseToJsonElement(value).jsonObject.let {
        GatewayChoice(it.getValue("id").jsonPrimitive.content, AgentMode.valueOf(it.getValue("mode").jsonPrimitive.content))
    } }
}

data class GatewayRecord(
    val id: String, val version: Long, val routes: Map<GatewayProtocol, String>, val model: String, val key: String,
    val models: List<GatewayModel> = emptyList(), val catalogError: String? = null,
) {
    override fun toString() = "GatewayRecord(id=$id, version=$version, protocols=${routes.keys}, credentials=[redacted])"
    fun modes(): List<AgentMode> = listOf(AgentMode.CODEX, AgentMode.OPEN_CODE, AgentMode.CLAUDE)
        .filter { it.gatewayProtocol() in routes }
    fun config(mode: AgentMode): GatewayConfig {
        val protocol = mode.gatewayProtocol()
        return GatewayConfig(requireNotNull(routes[protocol]) { AppStrings.gatewayDoesNotSupportThisAgent }, model, key,
            protocol, models, catalogError)
    }
    fun validate() {
        require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) && routes.isNotEmpty())
        routes.forEach { (protocol, endpoint) -> GatewayConfig(endpoint, model, key, protocol, models, catalogError).validate() }
    }
    fun json(): String = buildJsonObject {
        put("id", id); put("version", version); put("model", model); put("key", key)
        putJsonObject("routes") { routes.forEach { (protocol, endpoint) -> put(protocol.name, endpoint) } }
        putJsonArray("models") { models.forEach { addJsonObject { put("id", it.id); put("name", it.name) } } }
        catalogError?.let { put("catalogError", it) }
    }.toString()
    companion object { fun parse(value: String): GatewayRecord {
        val obj = Json.parseToJsonElement(value).jsonObject
        val routes = obj.getValue("routes").jsonObject.mapKeys { GatewayProtocol.valueOf(it.key) }.mapValues { it.value.jsonPrimitive.content }
        val first = routes.keys.first()
        val config = GatewayConfig.parse(buildJsonObject {
            put("endpoint", routes.getValue(first)); put("model", obj.getValue("model")); put("key", obj.getValue("key"))
            put("protocol", first.name.lowercase()); obj["models"]?.let { put("models", it) }
            obj["catalogError"]?.let { put("catalogError", it) }
        }.toString())
        return GatewayRecord(obj.getValue("id").jsonPrimitive.content, obj.getValue("version").jsonPrimitive.long,
            routes, config.model, config.key, config.models, config.catalogError)
    } }
}
