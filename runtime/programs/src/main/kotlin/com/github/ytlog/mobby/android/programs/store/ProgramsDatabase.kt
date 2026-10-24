package com.github.ytlog.mobby.android.programs.store

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "program")
data class ProgramRow(
    @PrimaryKey val programId: String,
    val name: String,
    val userState: String,
    val currentVersion: String,
    val installedAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "program_version", primaryKeys = ["programId", "version"])
data class ProgramVersionRow(
    val programId: String,
    val version: String,
    val checksum: String,
    val packagePath: String,
    val backgroundEntry: String?,
    val uiEntry: String?,
    val manifestJson: String,
    val installedAt: Long,
)

/** Scopes are JSON so each capability keeps its own scope shape. */
@Entity(tableName = "program_grant", primaryKeys = ["programId", "capability"])
data class GrantRow(
    val programId: String,
    val capability: String,
    val grantedScope: String,
    val requestedScope: String,
    val enabled: Boolean,
    val required: Boolean,
    val source: String,
    val grantedAt: Long,
    val expiresAt: Long?,
    val revision: Long,
)

@Entity(tableName = "permission_request", indices = [Index("programId")])
data class PermissionRequestRow(
    @PrimaryKey val requestId: String,
    val programId: String,
    val capability: String,
    val scope: String,
    val state: String,
    val createdAt: Long,
)

@Entity(tableName = "program_config", primaryKeys = ["programId", "key"])
data class ConfigRow(val programId: String, val key: String, val valueJson: String, val updatedAt: Long)

@Entity(tableName = "program_secret", primaryKeys = ["programId", "name"])
data class SecretRow(
    val programId: String,
    val name: String,
    val ciphertext: ByteArray,
    val iv: ByteArray,
    val domains: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "subscription", indices = [Index("programId")])
data class SubscriptionRow(
    @PrimaryKey val subscriptionId: String,
    val programId: String,
    val version: String,
    val eventType: String,
    val filterJson: String,
    val handler: String,
    val source: String,
    val paused: Boolean,
    val lastTriggeredAt: Long?,
    val triggerCount: Long,
)

@Entity(tableName = "capability_switch")
data class SwitchRow(@PrimaryKey val capability: String, val enabled: Boolean, val updatedAt: Long)

@Entity(tableName = "run", indices = [Index("programId"), Index("queuedAt")])
data class RunRow(
    @PrimaryKey val runId: String,
    val programId: String,
    val version: String,
    val trigger: String,
    val subscriptionId: String?,
    val dryRun: Boolean,
    val queuedAt: Long,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val outcome: String? = null,
    val exitCode: Int? = null,
    val signal: Int? = null,
    val reason: String? = null,
)

@Entity(tableName = "operation", indices = [Index(value = ["caller", "requestId"], unique = true), Index("programId"), Index("acceptedAt")])
data class OperationRow(
    @PrimaryKey val operationId: String,
    val programId: String,
    /** "run:<runId>" or "ui:<uiSessionId>"; request IDs are unique per caller. */
    val caller: String,
    val requestId: String,
    val fingerprint: String,
    val capability: String,
    val grantRevision: Long,
    val acceptedAt: Long,
    val dispatchedAt: Long? = null,
    val completedAt: Long? = null,
    val status: String,
    val effect: String,
    val errorCode: String? = null,
    val errorReason: String? = null,
    val resultJson: String? = null,
    val netHost: String? = null,
    val netMethod: String? = null,
    val netStatus: Int? = null,
    val netBytesOut: Long? = null,
    val netBytesIn: Long? = null,
)

@Entity(tableName = "program_kv", primaryKeys = ["programId", "key"])
data class KvRow(val programId: String, val key: String, val value: String, val updatedAt: Long)

@Dao
interface ProgramsDao {
    @Query("SELECT * FROM program ORDER BY name") fun programsFlow(): Flow<List<ProgramRow>>
    @Query("SELECT * FROM program") suspend fun programs(): List<ProgramRow>
    @Query("SELECT * FROM program WHERE programId = :id") suspend fun program(id: String): ProgramRow?
    @Upsert suspend fun upsertProgram(row: ProgramRow)
    @Query("UPDATE program SET userState = :state, updatedAt = :at WHERE programId = :id") suspend fun setUserState(id: String, state: String, at: Long): Int
    @Query("UPDATE program SET userState = :state, updatedAt = :at") suspend fun setAllUserState(state: String, at: Long)

    @Query("SELECT * FROM program_version WHERE programId = :id AND version = :version") suspend fun version(id: String, version: String): ProgramVersionRow?
    @Query("SELECT * FROM program_version WHERE programId = :id") suspend fun versions(id: String): List<ProgramVersionRow>
    @Upsert suspend fun upsertVersion(row: ProgramVersionRow)

    @Query("SELECT * FROM program_grant") fun grantsFlow(): Flow<List<GrantRow>>
    @Query("SELECT * FROM program_grant WHERE programId = :id") suspend fun grants(id: String): List<GrantRow>
    @Query("SELECT * FROM program_grant") suspend fun allGrants(): List<GrantRow>
    @Upsert suspend fun upsertGrant(row: GrantRow)
    @Query("DELETE FROM program_grant WHERE programId = :id AND capability NOT IN (:keep)") suspend fun deleteGrantsExcept(id: String, keep: List<String>)

    @Query("SELECT * FROM permission_request WHERE state = 'pending'") fun pendingRequestsFlow(): Flow<List<PermissionRequestRow>>
    @Insert suspend fun insertRequest(row: PermissionRequestRow)
    @Query("UPDATE permission_request SET state = :state WHERE requestId = :id") suspend fun setRequestState(id: String, state: String): Int

    @Query("SELECT * FROM program_config WHERE programId = :id") suspend fun config(id: String): List<ConfigRow>
    @Query("SELECT * FROM program_config") fun configFlow(): Flow<List<ConfigRow>>
    @Upsert suspend fun upsertConfig(row: ConfigRow)
    @Query("DELETE FROM program_config WHERE programId = :id AND `key` = :key") suspend fun deleteConfig(id: String, key: String)

    @Query("SELECT * FROM program_secret WHERE programId = :id AND name = :name") suspend fun secret(id: String, name: String): SecretRow?
    @Query("SELECT * FROM program_secret WHERE programId = :id") suspend fun secrets(id: String): List<SecretRow>
    @Upsert suspend fun upsertSecret(row: SecretRow)
    @Query("DELETE FROM program_secret WHERE programId = :id AND name = :name") suspend fun deleteSecret(id: String, name: String): Int

    @Query("SELECT * FROM subscription ORDER BY subscriptionId") fun subscriptionsFlow(): Flow<List<SubscriptionRow>>
    @Query("SELECT * FROM subscription") suspend fun allSubscriptions(): List<SubscriptionRow>
    @Query("SELECT * FROM subscription WHERE programId = :id") suspend fun subscriptions(id: String): List<SubscriptionRow>
    @Upsert suspend fun upsertSubscription(row: SubscriptionRow)
    @Query("DELETE FROM subscription WHERE subscriptionId = :id") suspend fun deleteSubscription(id: String): Int
    @Query("UPDATE subscription SET paused = :paused WHERE subscriptionId = :id") suspend fun setSubscriptionPaused(id: String, paused: Boolean): Int
    @Query("UPDATE subscription SET lastTriggeredAt = :at, triggerCount = triggerCount + 1 WHERE subscriptionId = :id") suspend fun noteTriggered(id: String, at: Long)

    @Query("SELECT * FROM capability_switch") fun switchesFlow(): Flow<List<SwitchRow>>
    @Query("SELECT * FROM capability_switch") suspend fun switches(): List<SwitchRow>
    @Upsert suspend fun upsertSwitch(row: SwitchRow)

    @Query("SELECT * FROM run ORDER BY queuedAt DESC LIMIT :limit") fun recentRunsFlow(limit: Int): Flow<List<RunRow>>
    @Query("SELECT * FROM run WHERE programId = :id ORDER BY queuedAt DESC LIMIT :limit") suspend fun runs(id: String, limit: Int): List<RunRow>
    @Query("SELECT * FROM run WHERE runId = :id") suspend fun run(id: String): RunRow?
    @Insert suspend fun insertRun(row: RunRow)
    @Update suspend fun updateRun(row: RunRow)
    @Query("SELECT * FROM run WHERE endedAt IS NULL") suspend fun unfinishedRuns(): List<RunRow>

    @Query("SELECT * FROM operation WHERE caller = :caller AND requestId = :requestId") suspend fun operation(caller: String, requestId: String): OperationRow?
    @Query("SELECT * FROM operation WHERE programId = :id ORDER BY acceptedAt DESC LIMIT :limit") suspend fun operations(id: String, limit: Int): List<OperationRow>
    @Insert suspend fun insertOperation(row: OperationRow)
    @Update suspend fun updateOperation(row: OperationRow)
    @Query("SELECT * FROM operation WHERE completedAt IS NULL") suspend fun unfinishedOperations(): List<OperationRow>

    @Query("SELECT * FROM program_kv WHERE programId = :id AND `key` = :key") suspend fun kv(id: String, key: String): KvRow?
    @Query("SELECT COALESCE(SUM(LENGTH(`key`) + LENGTH(value)), 0) FROM program_kv WHERE programId = :id") suspend fun kvBytes(id: String): Long
    @Upsert suspend fun upsertKv(row: KvRow)
    @Query("DELETE FROM program_kv WHERE programId = :id AND `key` = :key") suspend fun deleteKv(id: String, key: String): Int

    @Query("DELETE FROM run WHERE runId NOT IN (SELECT runId FROM run ORDER BY queuedAt DESC LIMIT :keep) OR queuedAt < :before") suspend fun pruneRuns(keep: Int, before: Long)
    @Query("DELETE FROM operation WHERE operationId NOT IN (SELECT operationId FROM operation ORDER BY acceptedAt DESC LIMIT :keep) OR acceptedAt < :before") suspend fun pruneOperations(keep: Int, before: Long)

    @Query("DELETE FROM program WHERE programId = :id") suspend fun deleteProgramRow(id: String)
    @Query("DELETE FROM program_version WHERE programId = :id") suspend fun deleteVersions(id: String)
    @Query("DELETE FROM program_grant WHERE programId = :id") suspend fun deleteGrants(id: String)
    @Query("DELETE FROM permission_request WHERE programId = :id") suspend fun deleteRequests(id: String)
    @Query("DELETE FROM program_config WHERE programId = :id") suspend fun deleteConfigs(id: String)
    @Query("DELETE FROM program_secret WHERE programId = :id") suspend fun deleteSecrets(id: String)
    @Query("DELETE FROM subscription WHERE programId = :id") suspend fun deleteSubscriptions(id: String)
    @Query("DELETE FROM run WHERE programId = :id") suspend fun deleteRuns(id: String)
    @Query("DELETE FROM operation WHERE programId = :id") suspend fun deleteOperations(id: String)
    @Query("DELETE FROM program_kv WHERE programId = :id") suspend fun deleteKvs(id: String)

    /** Removes everything mobby holds for a program; private data rows only when [withData]. */
    @Transaction suspend fun deleteProgram(id: String, withData: Boolean) {
        deleteSubscriptions(id); deleteRequests(id); deleteGrants(id); deleteConfigs(id); deleteSecrets(id)
        deleteRuns(id); deleteOperations(id); deleteVersions(id); deleteProgramRow(id)
        if (withData) deleteKvs(id)
    }
}

@Database(entities = [ProgramRow::class, ProgramVersionRow::class, GrantRow::class, PermissionRequestRow::class, ConfigRow::class,
    SecretRow::class, SubscriptionRow::class, SwitchRow::class, RunRow::class, OperationRow::class, KvRow::class],
    version = 1, exportSchema = false)
abstract class ProgramsDatabase : RoomDatabase() {
    abstract fun dao(): ProgramsDao

    companion object {
        /** Development schema: structural changes recreate this database instead of migrating it. */
        fun open(context: Context): ProgramsDatabase = Room.databaseBuilder(context, ProgramsDatabase::class.java, "programs.db")
            .fallbackToDestructiveMigration(true).build()
    }
}
