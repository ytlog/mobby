package com.mobby.interaction.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

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
@Entity(tableName = "selection")
internal data class SelectionRow(@PrimaryKey val key: String = "current", val conversationId: String)

/** Sidebar projection excludes user text, frozen drafts, expanded steps and historic snapshots. */
internal data class ConversationActivityRow(val conversationId: String, val snapshot: String?, val occupied: Boolean)

internal data class TurnWithChunks(
    @Embedded val turn: TurnRow,
    @Relation(parentColumn = "runId", entityColumn = "runId") val chunks: List<ChunkRow>
)
@Dao internal interface InteractionDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") fun conversations(): Flow<List<ConversationRow>>
    @Transaction
    @Query("SELECT * FROM (SELECT * FROM turns WHERE conversationId=:id ORDER BY createdAt DESC,id DESC LIMIT :limit) ORDER BY createdAt,id")
    fun timeline(id: String, limit: Int): Flow<List<TurnWithChunks>>
    @Query("SELECT COUNT(*) FROM turns WHERE conversationId=:id") fun turnCount(id: String): Flow<Int>
    @Query("SELECT COUNT(*) FROM turns WHERE conversationId=:id AND (createdAt > :time OR (createdAt = :time AND id >= :turnId))")
    suspend fun countThrough(id: String, time: Long, turnId: String): Int
    @Query("SELECT * FROM chunks WHERE runId IN (SELECT runId FROM turns WHERE conversationId=:id)") suspend fun historyChunks(id: String): List<ChunkRow>
    @Query("""SELECT c.id AS conversationId, t.snapshot AS snapshot,
        EXISTS(SELECT 1 FROM turns busy WHERE busy.conversationId=c.id AND busy.occupied=1) AS occupied
        FROM conversations c LEFT JOIN turns t ON t.id=(
            SELECT latest.id FROM turns latest WHERE latest.conversationId=c.id
            ORDER BY latest.createdAt DESC, latest.id DESC LIMIT 1
        )""") fun conversationActivities(): Flow<List<ConversationActivityRow>>
    @Query("SELECT conversationId FROM selection WHERE `key`='current'") fun selection(): Flow<String?>
    @Query("SELECT * FROM conversations WHERE id=:id") suspend fun conversation(id: String): ConversationRow?
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") suspend fun allConversations(): List<ConversationRow>
    @Query("SELECT * FROM turns WHERE conversationId=:id ORDER BY createdAt,id") suspend fun conversationTurns(id: String): List<TurnRow>
    @Query("SELECT * FROM turns WHERE conversationId=:id AND occupied=1 ORDER BY createdAt,id LIMIT 1") suspend fun earliestOccupied(id: String): TurnRow?
    @Query("SELECT * FROM turns WHERE id=:id") suspend fun turn(id: String): TurnRow?
    @Query("SELECT * FROM turns WHERE pending=1 OR occupied=1") suspend fun unfinished(): List<TurnRow>
    @Query("SELECT * FROM chunks WHERE ref=:ref") suspend fun chunk(ref: String): ChunkRow?
    @Upsert suspend fun save(row: ConversationRow)
    @Upsert suspend fun save(row: TurnRow)
    @Upsert suspend fun select(row: SelectionRow)
    @Upsert suspend fun chunks(rows: List<ChunkRow>)
}
@Database(entities = [ConversationRow::class, TurnRow::class, ChunkRow::class, SelectionRow::class], version = 3, exportSchema = true)
internal abstract class InteractionDatabase : RoomDatabase() {
    abstract fun dao(): InteractionDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context.applicationContext, InteractionDatabase::class.java, "interaction.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chunks ADD COLUMN expired INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_turns_conversationId_createdAt_id ON turns (conversationId, createdAt, id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_turns_conversationId_occupied ON turns (conversationId, occupied)")
            }
        }
    }
}
