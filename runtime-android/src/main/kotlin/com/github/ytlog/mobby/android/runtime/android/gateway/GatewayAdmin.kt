package com.github.ytlog.mobby.android.runtime.android.gateway

import com.github.ytlog.mobby.android.localization.AppStrings

import com.github.ytlog.mobby.android.runtime.api.gateway.*

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
        try { AdminResult.Success(GatewayStore(context).list().flatMap { record -> record.modes().map { summary(record, it) } }) }
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
            config.endpoint, config.model, com.github.ytlog.mobby.android.runtime.api.gateway.GatewayProtocol.valueOf(config.protocol.name), config.key.isNotEmpty(),
            config.models.map { GatewayModelSummary(it.id, it.name) }, config.catalogError)
    }
    suspend fun inspectGateway(request: InspectGatewayRequest): AdminResult<GatewayInspectionSummary> = withContext(Dispatchers.IO) {
        val chars = request.credential?.consume()
        try {
            val old = request.id?.let { GatewayStore(context).load(it) }
            val key = chars?.concatToString() ?: old?.key.orEmpty()
            val inspected = GatewayDiscovery().inspect(request.addresses, request.model.trim(), key)
            AdminResult.Success(GatewayInspectionSummary(inspected.model,
                inspected.models.map { GatewayModelSummary(it.id, it.name) },
                inspected.supported.keys.map { it.productAgent() }.toSet(), inspected.catalogError))
        } catch (e: CancellationException) { throw e }
        catch (_: IllegalArgumentException) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.PROTOCOL_ERROR, true)) }
        finally { chars?.fill('\u0000') }
    }
    suspend fun saveGatewayProfile(request: SaveGatewayRequest): AdminResult<GatewayProfileSummary> = withContext(Dispatchers.IO) {
        val chars = request.credential?.consume()
        try {
            val store = GatewayStore(context)
            val id = request.id ?: java.util.UUID.randomUUID().toString()
            val old = request.id?.let { store.load(it) }
            val key = chars?.concatToString() ?: old?.key.orEmpty()
            val inspected = GatewayDiscovery().inspect(request.addresses, request.model.trim(), key)
            require(inspected.supported.isNotEmpty()) { AppStrings.noAgentPassedTheNativeProtocolProbe }
            val models = selectedCatalog(inspected.model, inspected.models, request.selectedModels)
            val routes = inspected.supported.mapKeys { it.key.gatewayProtocol() }
            val record = GatewayRecord(id, 0, routes, inspected.model, key, models, inspected.catalogError)
            record.validate()
            val saved = store.save(record)
            if (store.default() == null) store.selectDefault(GatewayChoice(saved.id, saved.modes().first()))
            AdminResult.Success(summary(saved, saved.modes().first()))
        } catch (e: CancellationException) { throw e }
        catch (_: IllegalArgumentException) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.STORAGE_FULL, true)) }
        finally { chars?.fill('\u0000') }
    }
    suspend fun deleteGatewayProfile(id: String): AdminResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val store = GatewayStore(context)
            store.delete(id)
            if (store.default() == null) store.list().firstOrNull()?.let { store.selectDefault(GatewayChoice(it.id, it.modes().first())) }
            AdminResult.Success(Unit)
        }
        catch (_: IllegalArgumentException) { AdminResult.Failed(RuntimeError(ErrorCode.INVALID_CONFIG)) }
        catch (_: Exception) { AdminResult.Failed(RuntimeError(ErrorCode.STORAGE_FULL, true)) }
    }
}
