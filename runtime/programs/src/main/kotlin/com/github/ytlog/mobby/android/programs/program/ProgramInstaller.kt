package com.github.ytlog.mobby.android.programs.program

import androidx.room.withTransaction
import com.github.ytlog.mobby.android.programs.permission.Scope
import com.github.ytlog.mobby.android.programs.store.*
import java.io.File

enum class UserState(val wire: String) {
    ENABLED("enabled"), PAUSED("paused");
    companion object { fun of(wire: String) = values().first { it.wire == wire } }
}

/** Where mobby keeps installed copies and each program's private data. */
class ProgramLayout(val root: File) {
    fun packages(programId: String) = File(root, "packages/$programId")
    fun packageDir(programId: String, version: String, checksum: String) = File(packages(programId), "$version-${checksum.take(12)}")
    fun data(programId: String) = File(root, "data/$programId")
    fun files(programId: String) = File(data(programId), "files")
    fun temp(programId: String) = File(data(programId), "tmp")
    fun sql(programId: String) = File(data(programId), "sql/main.db")
}

/** What installing would change, shown to the user before they confirm. */
data class InstallPreview(
    val pkg: ProgramPackage,
    val installedVersion: String?,
    /** Capabilities not currently granted in full: never enabled, or requesting more than the current grant. */
    val needsNewGrant: List<String>,
)

data class InstallResult(val programId: String, val version: String, val updated: Boolean)

class ProgramInstaller(
    private val database: ProgramsDatabase,
    private val layout: ProgramLayout,
    private val reader: ProgramPackageReader,
    private val clock: () -> Long,
) {
    private val dao = database.dao()

    suspend fun preview(directory: File): InstallPreview {
        val pkg = reader.read(directory)
        val installed = dao.program(pkg.manifest.id)
        val grants = dao.grants(pkg.manifest.id).associateBy { it.capability }
        val needsNew = pkg.permissions.filter { request ->
            val old = grants[request.capability] ?: return@filter true
            !request.scope.within(Scope.fromJson(old.grantedScope)) || !old.enabled
        }.map { it.capability }
        return InstallPreview(pkg, installed?.currentVersion, needsNew)
    }

    /** Copies the validated package and records it. A new program starts paused with nothing granted. */
    suspend fun install(directory: File): InstallResult {
        val pkg = reader.read(directory)
        val manifest = pkg.manifest
        val now = clock()
        val target = layout.packageDir(manifest.id, manifest.version, pkg.checksum)
        copy(pkg, target)
        val existing = dao.program(manifest.id)
        val previousFields = existing?.let { dao.version(it.programId, it.currentVersion) }
            ?.let { ManifestJson.decodeFromString(ProgramManifest.serializer(), it.manifestJson).config }.orEmpty()
        database.withTransaction {
            dao.upsertVersion(ProgramVersionRow(manifest.id, manifest.version, pkg.checksum, target.absolutePath,
                manifest.background, manifest.ui?.entry, pkg.manifestJson, now))
            dao.upsertProgram(ProgramRow(manifest.id, manifest.name, existing?.userState ?: UserState.PAUSED.wire, manifest.version,
                existing?.installedAt ?: now, now))
            updateGrants(manifest.id, pkg.permissions, now)
            updateConfig(manifest.id, previousFields, manifest.config)
            updateSubscriptions(manifest)
        }
        removeStaleCopies(manifest.id, keep = setOfNotNull(target, existing?.let { old -> dao.version(old.programId, old.currentVersion)?.packagePath?.let(::File) }))
        return InstallResult(manifest.id, manifest.version, existing != null)
    }

    suspend fun delete(programId: String, withData: Boolean) {
        dao.deleteProgram(programId, withData)
        layout.packages(programId).deleteRecursively()
        if (withData) layout.data(programId).deleteRecursively()
    }

    /** Updates keep only what both the old grant and the new request allow; widening waits for the user. */
    private suspend fun updateGrants(programId: String, requests: List<RequestedPermission>, now: Long) {
        val old = dao.grants(programId).associateBy { it.capability }
        for (request in requests) {
            val previous = old[request.capability]
            val requested = request.scope.toJson().toString()
            if (previous == null) {
                dao.upsertGrant(GrantRow(programId, request.capability, requested, requested, enabled = false, required = request.required,
                    source = "manifest", grantedAt = now, expiresAt = null, revision = 1))
                continue
            }
            val granted = Scope.fromJson(previous.grantedScope).intersect(request.scope).toJson().toString()
            val changed = granted != previous.grantedScope || requested != previous.requestedScope || request.required != previous.required
            dao.upsertGrant(previous.copy(grantedScope = granted, requestedScope = requested, required = request.required,
                revision = previous.revision + if (changed) 1 else 0))
        }
        dao.deleteGrantsExcept(programId, requests.map { it.capability })
    }

    /** Values survive when the field keeps its key and type; anything else must be filled in again. */
    private suspend fun updateConfig(programId: String, previous: List<ConfigField>, fields: List<ConfigField>) {
        val byKey = fields.associateBy { it.key }
        val before = previous.associateBy { it.key }
        for (row in dao.config(programId)) {
            val field = byKey[row.key]
            if (field == null || before[row.key]?.type != field.type) dao.deleteConfig(programId, row.key)
        }
        // A secret field's value is the secret with the same name; it goes when the field stops being a secret field.
        before.values.filter { it.type == ConfigType.SECRET.wire && byKey[it.key]?.type != ConfigType.SECRET.wire }
            .forEach { dao.deleteSecret(programId, it.key) }
    }

    private suspend fun updateSubscriptions(manifest: ProgramManifest) {
        val old = dao.subscriptions(manifest.id).associateBy { it.subscriptionId }
        val declared = manifest.subscriptions.map { declaration ->
            val id = "${manifest.id}/${declaration.id}"
            val previous = old[id]?.takeIf { it.source == "manifest" }
            SubscriptionRow(id, manifest.id, manifest.version, declaration.event, declaration.filter.toString(), declaration.handler, "manifest",
                paused = previous?.paused ?: false, lastTriggeredAt = previous?.lastTriggeredAt, triggerCount = previous?.triggerCount ?: 0)
        }
        // Runtime subscriptions belong to the code that registered them; a new version starts without them.
        old.values.filter { row -> declared.none { it.subscriptionId == row.subscriptionId } }.forEach { dao.deleteSubscription(it.subscriptionId) }
        declared.forEach { dao.upsertSubscription(it) }
    }

    private fun copy(pkg: ProgramPackage, target: File) {
        if (target.isDirectory) return
        val staging = File(target.parentFile, ".${target.name}.staging")
        staging.deleteRecursively()
        try {
            pkg.files.forEach { (path, source) ->
                val destination = File(staging, path)
                destination.parentFile!!.mkdirs()
                source.copyTo(destination)
            }
            check(staging.renameTo(target)) { "Cannot store the program package" }
        } finally { staging.deleteRecursively() }
    }

    private fun removeStaleCopies(programId: String, keep: Set<File>) {
        val kept = keep.map { it.canonicalFile }.toSet()
        layout.packages(programId).listFiles().orEmpty().filter { it.canonicalFile !in kept }.forEach { it.deleteRecursively() }
    }
}
