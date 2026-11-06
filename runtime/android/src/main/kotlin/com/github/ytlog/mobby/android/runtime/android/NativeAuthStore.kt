package com.github.ytlog.mobby.android.runtime.android

import com.github.ytlog.mobby.android.runtime.engine.NativeCredentialRedactor
import android.content.Context
import android.os.FileObserver
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.util.Base64
import com.github.ytlog.mobby.android.runtime.api.*
import kotlinx.serialization.json.*
import java.io.Closeable
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Independent vault: existing mobby.gateway ciphertext and all conversation rows stay untouched. */
internal class NativeAuthStore(private val context: Context, private val key: () -> SecretKey = ::nativeKey) {
    companion object {
        private val lock = Any()
        private const val ALIAS = "mobby.native-agents"
        private fun nativeKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
    }
    private val prefs = context.getSharedPreferences("native-agent-connections", Context.MODE_PRIVATE)
    private fun encrypt(value: JsonObject): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toString().toByteArray()), Base64.NO_WRAP)
    }
    private fun read(name: String): JsonObject? {
        val bytes = prefs.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return Json.parseToJsonElement(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)).jsonObject
    }
    private fun write(name: String, value: JsonObject) { check(prefs.edit().putString(name, encrypt(value)).commit()) }
    fun settings(): List<AgentConnectionSetting> = synchronized(lock) {
        AgentId.values().map { agent ->
            val value = read("setting:${agent.name}")
            AgentConnectionSetting(agent, value?.get("mode")?.jsonPrimitive?.content?.let(AgentConnectionMode::valueOf) ?: AgentConnectionMode.AUTO,
                value?.get("gateway")?.jsonPrimitive?.contentOrNull)
        }
    }
    fun save(setting: AgentConnectionSetting) = synchronized(lock) {
        require(setting.gatewayId?.startsWith("native:") != true)
        write("setting:${setting.agent.name}", buildJsonObject {
            put("mode", setting.mode.name); setting.gatewayId?.let { put("gateway", it) }
        })
    }
    fun preferredAgent(): AgentId? = synchronized(lock) {
        read("preferred-agent")?.get("agent")?.jsonPrimitive?.content?.let(AgentId::valueOf)
    }
    fun prefer(agent: AgentId) = synchronized(lock) { write("preferred-agent", buildJsonObject { put("agent", agent.name) }) }
    private fun account(agent: AgentId) = read("account:${agent.name}")
    fun accounts(): List<NativeAccountSummary> = synchronized(lock) {
        AgentId.values().mapNotNull { agent -> account(agent)?.let { value ->
            NativeAccountSummary(agent, value.getValue("provider").jsonPrimitive.content, value.getValue("label").jsonPrimitive.content,
                value.getValue("generation").jsonPrimitive.long, nativeModels(value), value["expired"]?.jsonPrimitive?.booleanOrNull ?: false)
        } }
    }
    fun saveLogin(agent: AgentId, provider: String, label: String, models: List<NativeModelSummary>, credential: String, supplemental: Map<String, String> = emptyMap()) = synchronized(lock) {
        require(provider.isNotBlank() && models.isNotEmpty() && models.size <= 2000 && models.all { it.id.isNotBlank() })
        require(credential.length in 2..262144)
        Json.parseToJsonElement(credential).jsonObject
        require(supplemental.keys.all { it in setOf(".claude.json", ".config.json") })
        supplemental.values.forEach { require(it.length <= 262144); Json.parseToJsonElement(it).jsonObject }
        val generation = (read("generation:${agent.name}")?.get("value")?.jsonPrimitive?.long ?: 0) + 1
        val edit = prefs.edit()
        val value = buildJsonObject {
            put("generation", generation); put("provider", provider); put("label", label); put("credential", credential)
            put("supplemental", JsonObject(supplemental.mapValues { JsonPrimitive(it.value) }))
            put("models", encodeNativeModels(models))
        }
        check(edit.putString("account:${agent.name}", encrypt(value))
            .putString("generation:${agent.name}", encrypt(buildJsonObject { put("value", generation) })).commit())
    }
    fun refreshModels(agent: AgentId, generation: Long, provider: String, models: List<NativeModelSummary>) = synchronized(lock) {
        val current = requireNotNull(account(agent))
        require(current.getValue("generation").jsonPrimitive.long == generation)
        require(current.getValue("provider").jsonPrimitive.content == provider)
        write("account:${agent.name}", JsonObject(current + ("models" to encodeNativeModels(models))))
    }
    fun logout(agent: AgentId) = synchronized(lock) {
        // Generation is retained so an old live process cannot resurrect a logged-out/replaced account.
        check(prefs.edit().remove("account:${agent.name}").commit())
    }
    fun expire(agent: AgentId, generation: Long) = synchronized(lock) {
        val current = account(agent) ?: return@synchronized
        if (current.getValue("generation").jsonPrimitive.long == generation)
            write("account:${agent.name}", JsonObject(current + ("expired" to JsonPrimitive(true))))
    }
    private fun checkpoint(agent: AgentId, generation: Long, credential: File) = synchronized(lock) {
        val current = account(agent) ?: return@synchronized
        if (current.getValue("generation").jsonPrimitive.long != generation || !credential.isFile) return@synchronized
        require(credential.length() <= 262144)
        val text = credential.readText()
        Json.parseToJsonElement(text).jsonObject
        val extra = if (agent == AgentId.CLAUDE_CODE) listOf(".claude.json", ".config.json").mapNotNull { name ->
            File(credential.parentFile, name).takeIf { it.isFile }?.let { file ->
                require(file.length() <= 262144); val content = file.readText(); Json.parseToJsonElement(content).jsonObject
                name to JsonPrimitive(content)
            }
        }.toMap() else emptyMap()
        if (current.getValue("credential").jsonPrimitive.content != text || current["supplemental"] != JsonObject(extra))
            write("account:${agent.name}", JsonObject(current + mapOf("credential" to JsonPrimitive(text), "supplemental" to JsonObject(extra))))
    }
    fun open(agent: AgentId, home: File, login: Boolean = false, expectedGeneration: Long? = null): Lease = synchronized(lock) {
        val account = if (login) null else account(agent)
        if (expectedGeneration != null) require(account?.get("generation")?.jsonPrimitive?.long == expectedGeneration)
        val directory = File(context.cacheDir, "native-agent-auth/${UUID.randomUUID()}").apply { mkdirs(); setReadable(false, false); setReadable(true, true); setExecutable(false, false); setExecutable(true, true) }
        val data = File(directory, "data").apply { mkdirs() }
        val auth = if (agent == AgentId.OPEN_CODE) File(data, "opencode") else File(directory, "agent")
        auth.mkdirs()
        val credential = File(auth, if (agent == AgentId.CLAUDE_CODE) ".credentials.json" else "auth.json")
        account?.getValue("credential")?.jsonPrimitive?.content?.let { credential.writeText(it); credential.setReadable(false, false); credential.setReadable(true, true) }
        (account?.get("supplemental") as? JsonObject)?.forEach { (name, value) ->
            require(name in setOf(".claude.json", ".config.json"))
            File(auth, name).writeText(value.jsonPrimitive.content)
        }
        // Native session files remain in the original HOME, including sessions made via the API gateway.
        val persistent = File(home, when (agent) {
            AgentId.CODEX -> ".codex"
            AgentId.CLAUDE_CODE -> ".claude"
            AgentId.PI -> ".pi/agent"
            AgentId.OPEN_CODE -> ".local/share/opencode"
        }).apply { mkdirs() }
        val sharedDirs = when (agent) {
            AgentId.CODEX -> listOf("sessions", "archived_sessions", "skills")
            AgentId.CLAUDE_CODE -> listOf("projects", "skills", "todos")
            AgentId.PI -> listOf("sessions", "skills")
            AgentId.OPEN_CODE -> listOf("storage", "snapshot")
        }
        sharedDirs.forEach { name ->
            val target = File(persistent, name).apply { mkdirs() }
            Os.symlink(target.absolutePath, File(auth, name).absolutePath)
        }
        // OpenCode stores current sessions in SQLite; use a persistent private data directory,
        // while only auth.json lives in the transient lease.
        if (agent == AgentId.OPEN_CODE) listOf("opencode.db", "opencode.db-wal", "opencode.db-shm").forEach { name ->
            Os.symlink(File(persistent, name).absolutePath, File(auth, name).absolutePath)
        }
        val env = mapOf(when (agent) {
            AgentId.CODEX -> "CODEX_HOME"
            AgentId.CLAUDE_CODE -> "CLAUDE_CONFIG_DIR"
            AgentId.PI -> "PI_CODING_AGENT_DIR"
            AgentId.OPEN_CODE -> "XDG_DATA_HOME"
        } to (if (agent == AgentId.OPEN_CODE) data.absolutePath else auth.absolutePath)) + mapOf(
            "XDG_CONFIG_HOME" to File(directory, "config").absolutePath, "XDG_CACHE_HOME" to File(directory, "cache").absolutePath)
        Lease(directory, credential, env, agent, account?.get("generation")?.jsonPrimitive?.long)
    }
    inner class Lease internal constructor(val directory: File, val credential: File, val environment: Map<String, String>,
        private val agent: AgentId, private val generation: Long?) : Closeable {
        fun supplemental(): Map<String, String> = if (agent == AgentId.CLAUDE_CODE)
            listOf(".claude.json", ".config.json").mapNotNull { name -> File(credential.parentFile, name).takeIf { it.isFile }?.let { name to it.readText() } }.toMap()
            else emptyMap()
        val redactor = NativeCredentialRedactor(credential.takeIf { it.isFile }?.readText())
        @Volatile private var checkpointFailed = false
        @Suppress("DEPRECATION") private val observer = object : FileObserver(credential.parentFile!!.absolutePath, CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if ((path == credential.name || path in setOf(".claude.json", ".config.json")) && generation != null) try { redactor.include(credential.readText()); checkpoint(agent, generation, credential); checkpointFailed = false }
                catch (_: Exception) { checkpointFailed = true }
            }
        }.also { if (generation != null) it.startWatching() }
        fun flush() { if (generation != null) { redactor.include(credential.readText()); checkpoint(agent, generation, credential) }; check() }
        fun check() { check(!checkpointFailed) { "Native credential checkpoint failed" } }
        override fun close() {
            observer.stopWatching()
            try { if (generation != null) { checkpoint(agent, generation, credential); checkpointFailed = false }; check() }
            finally {
                // Walk without following symlinks; HOME sessions must never be removed.
                directory.toPath().let { root -> java.nio.file.Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists) } }
            }
        }
    }
    fun redactor(agent: AgentId): NativeCredentialRedactor = synchronized(lock) {
        NativeCredentialRedactor(account(agent)?.get("credential")?.jsonPrimitive?.content)
    }
    /** Cache is not a credential store. Remove abandoned leases without following session symlinks. */
    fun clearAbandonedLeases() {
        val root = File(context.cacheDir, "native-agent-auth")
        if (root.exists()) java.nio.file.Files.walk(root.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::deleteIfExists) }
    }
}

internal fun nativeModels(value: JsonObject): List<NativeModelSummary> = value.getValue("models").jsonArray.map { entry ->
    val model = entry.jsonObject
    NativeModelSummary(model.getValue("id").jsonPrimitive.content, model.getValue("name").jsonPrimitive.content,
        model["reasoningLevels"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
        model["defaultReasoning"]?.jsonPrimitive?.contentOrNull)
}

private fun encodeNativeModels(models: List<NativeModelSummary>): JsonArray {
    require(models.isNotEmpty() && models.size <= 2000 && models.all { it.id.isNotBlank() })
    return JsonArray(models.distinctBy { it.id }.map { model ->
        buildJsonObject {
            put("id", model.id)
            put("name", model.name)
            model.defaultReasoning?.let { put("defaultReasoning", it) }
            putJsonArray("reasoningLevels") { model.reasoningLevels.forEach { add(JsonPrimitive(it)) } }
        }
    })
}
