package com.mobby.runtime.android

import com.mobby.runtime.engine.AgentMode

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

enum class GatewayProtocol(val label: String) {
    CHAT("Chat Completions"), RESPONSES("Responses"), MESSAGES("Messages")
}

data class GatewayConfig(
    val endpoint: String = "", val model: String = "", val key: String = "",
    val protocol: GatewayProtocol = GatewayProtocol.RESPONSES
) {
    override fun toString() = "GatewayConfig(protocol=$protocol, credentials=[redacted])"
    fun validate() {
        val uri = runCatching { URI(endpoint) }.getOrNull()
        require(uri != null && uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null && uri.query == null) { "请输入有效的网关 URL，不要在地址中放密钥或查询参数" }
        require(model.isNotBlank() && model.length <= 200 && model.none { it.isISOControl() }) { "请填写有效的模型名称" }
        require(key.length <= 8192 && key.none { it.isISOControl() }) { "密钥格式不正确" }
    }
    fun json(): String = buildJsonObject {
        put("endpoint", endpoint); put("model", model); put("key", key); put("protocol", protocol.name.lowercase())
    }.toString()
    companion object {
        fun parse(value: String): GatewayConfig {
            val obj = Json.parseToJsonElement(value).jsonObject
            return GatewayConfig(obj.getValue("endpoint").jsonPrimitive.content, obj.getValue("model").jsonPrimitive.content,
                obj.getValue("key").jsonPrimitive.content, GatewayProtocol.valueOf(obj.getValue("protocol").jsonPrimitive.content.uppercase()))
        }
    }
}

/** No plaintext configuration or API key is written to the CLI home or workspace. */
class GatewayStore(context: Context) {
    private companion object {
        // Existing ciphertext is tied to this Android Keystore alias; branding must not rotate it.
        const val KEY_ALIAS = "mdoer.gateway"
        val writeLock = Any()
    }
    private val prefs = context.getSharedPreferences("gateway", Context.MODE_PRIVATE)
    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun version(mode: AgentMode): Long = prefs.getLong("${mode.name}.version", 0)
    fun load(mode: AgentMode, version: Long = version(mode)): GatewayConfig {
        val storageKey = if (version == version(mode)) mode.name else "${mode.name}:$version"
        val encoded = prefs.getString(storageKey, null) ?: return GatewayConfig(protocol =
            if (mode == AgentMode.CLAUDE) GatewayProtocol.MESSAGES else GatewayProtocol.RESPONSES)
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return GatewayConfig.parse(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }
    fun snapshot(mode: AgentMode): Pair<Long, GatewayConfig> = synchronized(writeLock) { version(mode) to load(mode) }
    fun save(mode: AgentMode, config: GatewayConfig) = synchronized(writeLock) {
        config.validate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val encrypted = cipher.iv + cipher.doFinal(config.json().toByteArray(Charsets.UTF_8))
        val oldVersion = version(mode)
        val encoded = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        val edit = prefs.edit()
        prefs.getString(mode.name, null)?.let { edit.putString("${mode.name}:$oldVersion", it) }
        check(edit.putString(mode.name, encoded).putLong("${mode.name}.version", oldVersion + 1).commit()) { "保存网关失败" }
    }
}
