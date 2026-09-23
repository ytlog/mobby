package com.github.ytlog.mobby.android.runtime.android.gateway

import android.content.Context
import com.github.ytlog.mobby.android.runtime.api.*
import com.github.ytlog.mobby.android.runtime.engine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class GatewayAdmin(private val context: Context) {
    suspend fun validateGateway(profile: GatewayProfileRef, agent: AgentId): AdminResult<GatewayCheck> {
        val config = try {
            withContext(Dispatchers.IO) {
                val record = GatewayStore(context).load(profile.id, profile.version)
                record.config(agent.launchMode()).also { it.validateFor(agent.launchMode()) }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        return try { AdminResult.Success(GatewayProbe().check(profile, config)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.PROTOCOL_ERROR, true)) }
    }
    suspend fun listGatewayProfiles(): AdminResult<List<GatewayProfileSummary>> = withContext(Dispatchers.IO) {
        try { AdminResult.Success(GatewayStore(context).list().flatMap { record -> record.endpoints.keys.map { summary(record, it) } }) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
    }
    suspend fun defaultGateway(): AdminResult<GatewaySelection?> = withContext(Dispatchers.IO) {
        try { AdminResult.Success(GatewayStore(context).default()?.let { GatewaySelection(it.mode.productAgent(), GatewayProfileRef(it.id, GatewayStore(context).load(it.id).version)) }) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
    }
    suspend fun selectDefaultGateway(selection: GatewaySelection): AdminResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val mode = selection.agent.launchMode()
            val store = GatewayStore(context)
            require(store.load(selection.profile.id).version == selection.profile.version)
            store.selectDefault(GatewayChoice(selection.profile.id, mode))
            AdminResult.Success(Unit)
        } catch (_: IllegalArgumentException) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.STORAGE_FULL, true)) }
    }
    private fun summary(record: GatewayRecord, mode: AgentMode): GatewayProfileSummary {
        val config = record.config(mode)
        return GatewayProfileSummary(GatewayProfileRef(record.id, record.version),
            mode.productAgent(),
            config.endpoint, config.model, com.github.ytlog.mobby.android.runtime.api.GatewayProtocol.valueOf(config.protocol.name), config.key.isNotEmpty(),
            config.models.map { GatewayModelSummary(it.id, it.name) }, config.catalogError)
    }
    suspend fun saveGatewayProfile(request: SaveGatewayRequest): AdminResult<GatewayProfileSummary> = withContext(Dispatchers.IO) {
        val chars = request.credential?.consume()
        try {
            val store = GatewayStore(context)
            val id = request.id ?: java.util.UUID.randomUUID().toString()
            val old = request.id?.let { store.load(it) }
            val endpoints = request.endpoints.mapKeys { it.key.launchMode() }
            val record = GatewayRecord(id, 0, endpoints, request.model, chars?.concatToString() ?: old?.key.orEmpty())
            record.validate()
            val draft = record.config(endpoints.keys.first())
            val fetched = GatewayCatalog().fetch(draft)
            val models = when (fetched) {
                is CatalogResult.Ready -> mergeCatalog(draft.model, fetched.models)
                is CatalogResult.Unavailable -> listOf(GatewayModel(draft.model, draft.model))
            }
            val catalogError = (fetched as? CatalogResult.Unavailable)?.message
            val saved = store.save(record.copy(models = models, catalogError = catalogError))
            if (store.default() == null) store.selectDefault(GatewayChoice(saved.id, endpoints.keys.first()))
            AdminResult.Success(summary(saved, endpoints.keys.first()))
        } catch (e: CancellationException) { throw e }
        catch (_: IllegalArgumentException) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.STORAGE_FULL, true)) }
        finally { chars?.fill('\u0000') }
    }
    suspend fun deleteGatewayProfile(id: String): AdminResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val store = GatewayStore(context)
            store.delete(id)
            if (store.default() == null) store.list().firstOrNull()?.let { store.selectDefault(GatewayChoice(it.id, it.endpoints.keys.first())) }
            AdminResult.Success(Unit)
        }
        catch (_: IllegalArgumentException) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.STORAGE_FULL, true)) }
    }
}
