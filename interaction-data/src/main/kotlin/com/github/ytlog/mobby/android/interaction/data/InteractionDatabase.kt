package com.github.ytlog.mobby.android.interaction.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
internal data class ProjectRow(@PrimaryKey val name: String, val workspace: String, val skills: String = "[]", val rules: String = "")

@Entity(tableName = "conversations")
internal data class ConversationRow(@PrimaryKey val id: String, val body: String, val updatedAt: Long)
@Entity(tableName = "turns", indices = [Index("conversationId"), Index("runId", unique = true), Index(value = ["conversationId", "createdAt", "id"]), Index(value = ["conversationId", "occupied"])],
    foreignKeys = [ForeignKey(entity = ConversationRow::class, parentColumns = ["id"], childColumns = ["conversationId"])])
internal data class TurnRow(
    @PrimaryKey val id: String, val conversationId: String, val userText: String, val frozen: String,
    val createdAt: Long, val runId: String? = null, val snapshot: String? = null,
    val pending: Boolean = true, val occupied: Boolean = true, val error: String? = null,
    val expanded: Boolean? = null, val expandedSteps: String = "[]"
)
@Entity(tableName = "chunks", indices = [Index("runId")])
internal data class ChunkRow(@PrimaryKey val ref: String, val runId: String, val text: String, @ColumnInfo(defaultValue = "0") val expired: Boolean = false)
@Entity(tableName = "expired_output_cache")
internal data class ExpiredOutputCacheRow(@PrimaryKey val runId: String)
internal data class OutputCacheCandidate(val turnId: String, val runId: String, val snapshot: String, val bytes: Long, val createdAt: Long)
@Entity(tableName = "selection")
internal data class SelectionRow(@PrimaryKey val key: String = "current", val conversationId: String)

/** Sidebar projection excludes user text, frozen drafts, expanded steps and historic snapshots. */
internal data class ConversationActivityRow(val conversationId: String, val snapshot: String?, val occupied: Boolean, val executionId: String?)

internal data class TurnWithChunks(
    @Embedded val turn: TurnRow,
    @Relation(parentColumn = "runId", entityColumn = "runId") val chunks: List<ChunkRow>
)
@Dao internal interface InteractionDao {
    @Query("SELECT * FROM projects ORDER BY name") fun projects(): Flow<List<ProjectRow>>
    @Query("SELECT * FROM projects WHERE name=:name") suspend fun project(name: String): ProjectRow?
    @Query("SELECT * FROM projects WHERE workspace=:workspace LIMIT 1") suspend fun projectByWorkspace(workspace: String): ProjectRow?
    @Upsert suspend fun save(row: ProjectRow)
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") fun conversations(): Flow<List<ConversationRow>>
    @Transaction
    @Query("SELECT * FROM (SELECT * FROM turns WHERE conversationId=:id ORDER BY createdAt DESC,id DESC LIMIT :limit) ORDER BY createdAt,id")
    fun timeline(id: String, limit: Int): Flow<List<TurnWithChunks>>
    @Query("SELECT COUNT(*) FROM turns WHERE conversationId=:id") fun turnCount(id: String): Flow<Int>
    @Query("SELECT COUNT(*) FROM turns WHERE conversationId=:id AND (createdAt > :time OR (createdAt = :time AND id >= :turnId))")
    suspend fun countThrough(id: String, time: Long, turnId: String): Int
    @Query("SELECT * FROM chunks WHERE runId IN (SELECT runId FROM turns WHERE conversationId=:id)") suspend fun historyChunks(id: String): List<ChunkRow>
    @Query("""SELECT c.id AS conversationId, t.snapshot AS snapshot,
        EXISTS(SELECT 1 FROM turns busy WHERE busy.conversationId=c.id AND busy.occupied=1) AS occupied,
        (SELECT busy.runId FROM turns busy WHERE busy.conversationId=c.id AND busy.occupied=1 AND busy.runId IS NOT NULL ORDER BY busy.createdAt, busy.id LIMIT 1) AS executionId
        FROM conversations c LEFT JOIN turns t ON t.id=(
            SELECT latest.id FROM turns latest WHERE latest.conversationId=c.id
            ORDER BY latest.createdAt DESC, latest.id DESC LIMIT 1
        )""") fun conversationActivities(): Flow<List<ConversationActivityRow>>
    @Query("SELECT conversationId FROM selection WHERE `key`='current'") fun selection(): Flow<String?>
    @Query("SELECT * FROM conversations WHERE id=:id") suspend fun conversation(id: String): ConversationRow?
    @Query("SELECT * FROM conversations WHERE id=:id") fun observeConversation(id: String): Flow<ConversationRow?>
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") suspend fun allConversations(): List<ConversationRow>
    @Query("SELECT * FROM turns WHERE conversationId=:id ORDER BY createdAt,id") suspend fun conversationTurns(id: String): List<TurnRow>
    @Query("SELECT * FROM turns WHERE conversationId=:id AND occupied=1 ORDER BY createdAt,id LIMIT 1") suspend fun earliestOccupied(id: String): TurnRow?
    @Query("SELECT * FROM turns WHERE runId=:runId LIMIT 1") suspend fun turnByRun(runId: String): TurnRow?
    @Query("SELECT * FROM turns WHERE id=:id") suspend fun turn(id: String): TurnRow?
    @Query("SELECT * FROM turns WHERE pending=1 OR occupied=1") suspend fun unfinished(): List<TurnRow>
    @Query("SELECT * FROM chunks WHERE ref=:ref") suspend fun chunk(ref: String): ChunkRow?
    @Query("""SELECT t.id AS turnId,t.runId,t.snapshot,t.createdAt,SUM(length(CAST(c.text AS BLOB))) AS bytes
        FROM turns t JOIN chunks c ON c.runId=t.runId
        WHERE t.pending=0 AND t.occupied=0 AND t.snapshot IS NOT NULL AND c.expired=0
        AND (:conversationId IS NULL OR t.conversationId=:conversationId)
        AND (:afterTime IS NULL OR t.createdAt > :afterTime OR (t.createdAt=:afterTime AND t.id > :afterId))
        GROUP BY t.id ORDER BY t.createdAt,t.id LIMIT :limit""")
    suspend fun outputCacheCandidates(conversationId: String?, afterTime: Long?, afterId: String, limit: Int): List<OutputCacheCandidate>
    @Query("SELECT ref FROM chunks WHERE runId=:runId AND expired=0 ORDER BY ref") suspend fun availableChunkRefs(runId: String): List<String>
    @Query("SELECT ref FROM chunks WHERE runId=:runId") suspend fun chunkRefs(runId: String): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM expired_output_cache WHERE runId=:runId)") suspend fun outputCacheExpired(runId: String): Boolean
    @Query("UPDATE chunks SET text='',expired=1 WHERE runId=:runId") suspend fun expireOutputCache(runId: String)
    @Query("SELECT EXISTS(SELECT 1 FROM chunks WHERE ref=:ref AND expired=0)") suspend fun chunkAvailable(ref: String): Boolean
    @Query("UPDATE chunks SET text='',expired=1 WHERE ref=:ref") suspend fun expireChunk(ref: String)
    @Upsert suspend fun expire(row: ExpiredOutputCacheRow)
    @Upsert suspend fun save(row: ConversationRow)
    @Upsert suspend fun save(row: TurnRow)
    @Upsert suspend fun select(row: SelectionRow)
    @Upsert suspend fun chunks(rows: List<ChunkRow>)
}
@Database(entities = [ConversationRow::class, TurnRow::class, ChunkRow::class, SelectionRow::class, ExpiredOutputCacheRow::class, ProjectRow::class], version = 1, exportSchema = false)
internal abstract class InteractionDatabase : RoomDatabase() {
    abstract fun dao(): InteractionDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(
            context.applicationContext, InteractionDatabase::class.java, "interaction-current.db"
        ).build()
    }
}
