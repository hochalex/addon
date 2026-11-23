package at.hochalex.assistent.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

object MailCategory {
    const val PERSONAL = "PERSONAL"      // persönliche Mail, evtl. Antwort nötig
    const val INFO = "INFO"              // Info/Bestätigung/Benachrichtigung
    const val NEWSLETTER = "NEWSLETTER"  // Newsletter/Werbung
    const val UNKNOWN = "UNKNOWN"
}

@Entity(tableName = "mails", indices = [Index(value = ["accountId", "messageId"], unique = true)])
data class MailEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val uid: Long,
    val messageId: String,
    val fromAddress: String,
    val fromName: String,
    val toAddress: String,
    val subject: String,
    val receivedAt: Long,
    val body: String,
    val references: String,
    val isPrivate: Boolean = false,
    val category: String = MailCategory.UNKNOWN,
    val important: Boolean = false,
    val needsReply: Boolean = false,
    val summary: String = "",
    val processed: Boolean = false,
) {
    val senderLabel get() = fromName.ifBlank { fromAddress }
}

object DraftStatus {
    const val PENDING = "PENDING"
    const val SENT = "SENT"
    const val DISCARDED = "DISCARDED"
    const val FAILED = "FAILED"
}

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 0 = neue Mail (keine Antwort) */
    val mailId: Long,
    val accountId: String,
    val toAddress: String,
    val toName: String,
    val subject: String,
    val body: String,
    val inReplyTo: String = "",
    val references: String = "",
    val status: String = DraftStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val generatedLocally: Boolean = false,
    val error: String = "",
)

object SuggestionType {
    const val TODO = "TODO"
    const val EVENT = "EVENT"
}

@Entity(tableName = "suggestions")
data class SuggestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mailId: Long,
    val type: String,
    val title: String,
    /** ISO-Datum (Todo-Fälligkeit) */
    val dueDate: String? = null,
    /** ISO-Datum-Zeit (Termin) */
    val start: String? = null,
    val end: String? = null,
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface MailDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(mail: MailEntity): Long

    @Update
    suspend fun update(mail: MailEntity)

    @Query("SELECT * FROM mails WHERE id = :id")
    suspend fun get(id: Long): MailEntity?

    @Query("SELECT * FROM mails WHERE processed = 0 ORDER BY receivedAt ASC")
    suspend fun unprocessed(): List<MailEntity>

    @Query("SELECT * FROM mails ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MailEntity>

    @Query("SELECT * FROM mails WHERE important = 1 OR needsReply = 1 ORDER BY receivedAt DESC LIMIT :limit")
    fun observeRelevant(limit: Int = 30): Flow<List<MailEntity>>

    @Query("SELECT * FROM mails WHERE receivedAt > :since AND (important = 1 OR needsReply = 1) ORDER BY receivedAt DESC")
    suspend fun relevantSince(since: Long): List<MailEntity>

    @Query("SELECT DISTINCT fromName || ' <' || fromAddress || '>' FROM mails WHERE fromName LIKE '%' || :q || '%' OR fromAddress LIKE '%' || :q || '%' LIMIT 5")
    suspend fun findContacts(q: String): List<String>

    @Query("DELETE FROM mails WHERE receivedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface DraftDao {
    @Insert
    suspend fun insert(d: DraftEntity): Long

    @Update
    suspend fun update(d: DraftEntity)

    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun get(id: Long): DraftEntity?

    @Query("SELECT * FROM drafts WHERE status = 'PENDING' OR status = 'FAILED' ORDER BY createdAt DESC")
    suspend fun pending(): List<DraftEntity>

    @Query("SELECT * FROM drafts WHERE status = 'PENDING' OR status = 'FAILED' ORDER BY createdAt DESC")
    fun observePending(): Flow<List<DraftEntity>>

    @Query("SELECT * FROM drafts WHERE mailId = :mailId AND status = 'PENDING' LIMIT 1")
    suspend fun pendingForMail(mailId: Long): DraftEntity?
}

@Dao
interface SuggestionDao {
    @Insert
    suspend fun insert(s: SuggestionEntity): Long

    @Update
    suspend fun update(s: SuggestionEntity)

    @Query("SELECT * FROM suggestions WHERE id = :id")
    suspend fun get(id: Long): SuggestionEntity?

    @Query("SELECT * FROM suggestions WHERE status = 'PENDING' ORDER BY createdAt DESC")
    suspend fun pending(): List<SuggestionEntity>

    @Query("SELECT * FROM suggestions WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun observePending(): Flow<List<SuggestionEntity>>
}

@Database(entities = [MailEntity::class, DraftEntity::class, SuggestionEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mails(): MailDao
    abstract fun drafts(): DraftDao
    abstract fun suggestions(): SuggestionDao

    companion object {
        fun create(context: Context) =
            Room.databaseBuilder(context, AppDatabase::class.java, "assistent.db")
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
