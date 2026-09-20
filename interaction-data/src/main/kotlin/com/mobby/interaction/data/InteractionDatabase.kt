package com.mobby.interaction.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
internal data class ConversationRow(@PrimaryKey val id: String, val body: String, val updatedAt: Long)
@Entity(tableName = "turns", indices = [Index("conversationId"), Index("runId", unique = true)],
    foreignKeys = [ForeignKey(entity = ConversationRow::class, parentColumns = ["id"], childColumns = ["conversationId"])])
internal data class TurnRow(
    @PrimaryKey val id: String, val conversationId: String, val userText: String, val frozen: String,
    val createdAt: Long, val runId: String? = null, val snapshot: String? = null,
    val pending: Boolean = true, val occupied: Boolean = true, val error: String? = null,
    val expanded: Boolean? = null, val expandedSteps: String = "[]"
)
@Entity(tableName = "chunks", indices = [Index("runId")])
internal data class ChunkRow(@PrimaryKey val ref: String, val runId: String, val text: String)
@Entity(tableName = "selection")
internal data class SelectionRow(@PrimaryKey val key: String = "current", val conversationId: String)

@Dao internal interface InteractionDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") fun conversations(): Flow<List<ConversationRow>>
    @Query("SELECT * FROM turns ORDER BY createdAt,id") fun turns(): Flow<List<TurnRow>>
    @Query("SELECT * FROM chunks WHERE runId IN (SELECT runId FROM turns WHERE conversationId=:id)") fun chunks(id: String): Flow<List<ChunkRow>>
    @Query("SELECT conversationId FROM selection WHERE `key`='current'") fun selection(): Flow<String?>
    @Query("SELECT * FROM conversations WHERE id=:id") suspend fun conversation(id: String): ConversationRow?
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") suspend fun allConversations(): List<ConversationRow>
    @Query("SELECT * FROM turns WHERE conversationId=:id ORDER BY createdAt,id") suspend fun conversationTurns(id: String): List<TurnRow>
    @Query("SELECT * FROM turns WHERE id=:id") suspend fun turn(id: String): TurnRow?
    @Query("SELECT * FROM turns WHERE pending=1 OR occupied=1") suspend fun unfinished(): List<TurnRow>
    @Query("SELECT * FROM chunks WHERE ref=:ref") suspend fun chunk(ref: String): ChunkRow?
    @Upsert suspend fun save(row: ConversationRow)
    @Upsert suspend fun save(row: TurnRow)
    @Upsert suspend fun select(row: SelectionRow)
    @Upsert suspend fun chunks(rows: List<ChunkRow>)
}
@Database(entities = [ConversationRow::class, TurnRow::class, ChunkRow::class, SelectionRow::class], version = 1, exportSchema = true)
internal abstract class InteractionDatabase : RoomDatabase() {
    abstract fun dao(): InteractionDao
    companion object {
        fun open(context: Context) = Room.databaseBuilder(context.applicationContext, InteractionDatabase::class.java, "interaction.db").build()
    }
}
