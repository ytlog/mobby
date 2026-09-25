package com.github.ytlog.mobby.android.runtime.android.gateway

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Host-side, process-local gateway view of the independent HTTP model service. */
object LocalModelGateway {
    const val ID = "00000000-0000-4000-8000-000000000001"
    private const val BASE = "http://127.0.0.1:11435/v1"
    @Volatile private var credential: String? = null
    @Volatile private var selected = false
    @Volatile private var selectedVersion: Long? = null
    @Volatile internal var healthReader: (String) -> String? = ::readHealth

    fun configure(inferenceToken: String) { credential = inferenceToken }

    internal fun select(mode: com.github.ytlog.mobby.android.runtime.engine.AgentMode) {
        val record = requireNotNull(liveRecord())
        require(mode in record.modes())
        selected = true
        selectedVersion = record.version
        selectedMode = mode
    }

    @Volatile private var selectedMode = com.github.ytlog.mobby.android.runtime.engine.AgentMode.CODEX

    internal fun choice(): GatewayChoice? {
        if (!selected) return null
        if (liveRecord()?.version != selectedVersion) { clearSelection(); return null }
        return GatewayChoice(ID, selectedMode)
    }

    internal fun clearSelection() { selected = false; selectedVersion = null }

    internal fun liveRecord(): GatewayRecord? {
        val key = credential ?: return null
        return try {
            val body = healthReader(key) ?: return null
            val health = Json.parseToJsonElement(body).jsonObject
            if (health["status"]?.jsonPrimitive?.content != "LISTENING") return null
            val model = health["loadedModel"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
            val instance = health["instanceId"]?.jsonPrimitive?.content ?: return null
            val version = (instance.hashCode().toLong() shl 32) xor model.hashCode().toLong()
            GatewayRecord(ID, version, mapOf(GatewayProtocol.RESPONSES to BASE, GatewayProtocol.MESSAGES to BASE), model, key,
                listOf(GatewayModel(model, model)))
        } catch (_: Exception) { null }
    }

    private fun readHealth(key: String): String? {
        val connection = URL("http://127.0.0.1:11435/local/v1/health").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 600
            connection.readTimeout = 600
            connection.setRequestProperty("Authorization", "Bearer $key")
            if (connection.responseCode != 200) return null
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }
}
