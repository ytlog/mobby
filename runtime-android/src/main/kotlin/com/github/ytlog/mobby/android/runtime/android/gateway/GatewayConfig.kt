package com.github.ytlog.mobby.android.runtime.android.gateway

import com.github.ytlog.mobby.android.runtime.engine.AgentMode

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.*
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
            uri.userInfo == null && uri.fragment == null && uri.query == null) { "请输入有效的网关 URL，不要在地址中放密钥或查询参数" }
        require(model.isNotBlank() && model.length <= 200 && model.none { it.isISOControl() }) { "请填写有效的模型名称" }
        require(key.length <= 8192 && key.none { it.isISOControl() }) { "密钥格式不正确" }
        require(models.size <= 400 && models.all { item ->
            item.id.isNotBlank() && item.id.length <= 200 && item.id.none { it.isISOControl() } &&
                item.name.isNotBlank() && item.name.length <= 120 && item.name.none { it.isISOControl() }
        }) { "模型列表无效" }
        require(catalogError == null || (catalogError.length <= 200 && catalogError.none { it.isISOControl() })) { "模型列表说明无效" }
    }
    fun validateFor(mode: AgentMode) {
        validate()
        require(protocol == mode.gatewayProtocol()) {
            "网关协议必须与 Agent 原生协议一致；暂不提供转换"
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
                }?.distinctBy { it.id }?.take(400)
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
    private val apiSuffix = Regex("/(chat/completions|responses|messages)$")
    fun url(endpoint: String, path: String): java.net.URL {
        val base = URI(endpoint)
        val prefix = base.rawPath.orEmpty().trimEnd('/').replace(apiSuffix, "").ifEmpty { "/v1" }
        return java.net.URL("${base.scheme}://${base.rawAuthority}$prefix$path")
    }
}

/** No plaintext configuration or API key is written to the CLI home or workspace. */
class GatewayStore(context: Context) {
    private companion object {
        const val KEY_ALIAS = "mobby.gateway"
        val writeLock = Any()
    }
    private val prefs = context.getSharedPreferences("gateway_profiles", Context.MODE_PRIVATE)
    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    fun ids(): List<String> = prefs.getString("index", null)?.let { encrypted ->
        Json.parseToJsonElement(decrypt(encrypted)).jsonArray.map { it.jsonPrimitive.content }
    }.orEmpty()
    fun load(id: String, version: Long? = null): GatewayRecord {
        require(id in ids()) { "网关不存在" }
        val current = GatewayRecord.parse(decrypt(requireNotNull(prefs.getString(id, null)) { "网关不存在" }))
        if (version == null || version == current.version) return current
        val encoded = requireNotNull(prefs.getString("$id:$version", null)) { "网关版本不存在" }
        return GatewayRecord.parse(decrypt(encoded))
    }
    fun list(): List<GatewayRecord> = ids().map(::load)
    fun default(): GatewayChoice? = prefs.getString("default", null)?.let { GatewayChoice.parse(decrypt(it)) }
    fun selectDefault(choice: GatewayChoice) = synchronized(writeLock) {
        require(load(choice.id).endpoints.containsKey(choice.mode)) { "网关不支持此 Agent" }
        check(prefs.edit().putString("default", encrypt(choice.json())).commit()) { "保存默认网关失败" }
    }
    fun save(record: GatewayRecord): GatewayRecord = synchronized(writeLock) {
        record.validate()
        val ids = ids()
        val existing = if (record.id in ids) load(record.id) else null
        val saved = record.copy(version = (existing?.version ?: 0) + 1)
        val edit = prefs.edit()
        if (existing != null) edit.putString("${record.id}:${existing.version}", prefs.getString(record.id, null))
        if (default()?.id == record.id && default()?.mode !in saved.endpoints) edit.remove("default")
        val updated = if (existing == null) ids + record.id else ids
        check(edit.putString(record.id, encrypt(saved.json()))
            .putString("index", encrypt(JsonArray(updated.map(::JsonPrimitive)).toString()))
            .commit()) { "保存网关失败" }
        saved
    }
    fun delete(id: String) = synchronized(writeLock) {
        require(id in ids())
        val updated = ids() - id
        val edit = prefs.edit().remove(id).putString("index", encrypt(JsonArray(updated.map(::JsonPrimitive)).toString()))
        prefs.all.keys.filter { it.startsWith("$id:") }.forEach(edit::remove)
        if (default()?.id == id) edit.remove("default")
        check(edit.commit()) { "删除网关失败" }
    }
}

data class GatewayChoice(val id: String, val mode: AgentMode) {
    fun json(): String = buildJsonObject { put("id", id); put("mode", mode.name) }.toString()
    companion object { fun parse(value: String): GatewayChoice = Json.parseToJsonElement(value).jsonObject.let {
        GatewayChoice(it.getValue("id").jsonPrimitive.content, AgentMode.valueOf(it.getValue("mode").jsonPrimitive.content))
    } }
}

data class GatewayRecord(
    val id: String, val version: Long, val endpoints: Map<AgentMode, String>, val model: String, val key: String,
    val models: List<GatewayModel> = emptyList(), val catalogError: String? = null,
) {
    override fun toString() = "GatewayRecord(id=$id, version=$version, agents=${endpoints.keys}, credentials=[redacted])"
    fun config(mode: AgentMode) = GatewayConfig(requireNotNull(endpoints[mode]) { "网关不支持此 Agent" }, model, key,
        mode.gatewayProtocol(), models, catalogError)
    fun validate() {
        require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) && endpoints.isNotEmpty())
        endpoints.forEach { (mode, _) -> config(mode).validateFor(mode) }
    }
    fun json(): String = buildJsonObject {
        put("id", id); put("version", version); put("model", model); put("key", key)
        putJsonObject("endpoints") { endpoints.forEach { (mode, endpoint) -> put(mode.name, endpoint) } }
        putJsonArray("models") { models.forEach { addJsonObject { put("id", it.id); put("name", it.name) } } }
        catalogError?.let { put("catalogError", it) }
    }.toString()
    companion object { fun parse(value: String): GatewayRecord {
        val obj = Json.parseToJsonElement(value).jsonObject
        val endpoints = obj.getValue("endpoints").jsonObject.mapKeys { AgentMode.valueOf(it.key) }.mapValues { it.value.jsonPrimitive.content }
        val first = endpoints.keys.first()
        val config = GatewayConfig.parse(buildJsonObject {
            put("endpoint", endpoints.getValue(first)); put("model", obj.getValue("model")); put("key", obj.getValue("key"))
            put("protocol", first.gatewayProtocol().name.lowercase()); obj["models"]?.let { put("models", it) }
            obj["catalogError"]?.let { put("catalogError", it) }
        }.toString())
        return GatewayRecord(obj.getValue("id").jsonPrimitive.content, obj.getValue("version").jsonPrimitive.long,
            endpoints, config.model, config.key, config.models, config.catalogError)
    } }
}
