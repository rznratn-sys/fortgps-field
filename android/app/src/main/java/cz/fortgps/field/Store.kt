package cz.fortgps.field

import android.app.Application
import android.content.Context
import androidx.room.*
import androidx.work.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.decodeFromString
import java.util.concurrent.TimeUnit

@Entity(tableName = "sessions") data class SessionRow(@PrimaryKey val id: String, val status: String, val updated: String, val payload: String)
@Entity(tableName = "outbox", indices = [Index(value = ["sessionId", "revision"], unique = true)])
data class OutboxRow(@PrimaryKey val key: String, val sessionId: String, val revision: Int, val payload: String,
    val state: String = "pending", val remoteId: String? = null, val folderId: String? = null,
    val error: String? = null, val confirmedAt: String? = null)

@Entity(tableName = "xmp_operations")
data class XmpRow(@PrimaryKey val sessionId: String, val state: String = "pending",
    val message: String = "Čeká na zápis XMP", val uri: String? = null, val name: String? = null,
    val beforeHash: String? = null, val afterHash: String? = null, val protectedHash: String? = null,
    val backupPath: String? = null, val selectionMethod: String? = null)
@Dao interface FieldDao {
    @Query("SELECT xmp_operations.* FROM xmp_operations JOIN sessions ON sessions.id=xmp_operations.sessionId ORDER BY sessions.updated ASC") fun observeXmp(): Flow<List<XmpRow>>
    @Query("SELECT * FROM xmp_operations") suspend fun xmpAll(): List<XmpRow>
    @Query("SELECT * FROM xmp_operations WHERE sessionId=:id") suspend fun xmp(id: String): XmpRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putXmp(row: XmpRow)
    @Query("SELECT * FROM sessions ORDER BY updated DESC") fun observe(): Flow<List<SessionRow>>
    @Query("SELECT * FROM outbox ORDER BY sessionId, revision") fun observeQueue(): Flow<List<OutboxRow>>
    @Query("SELECT * FROM sessions ORDER BY updated DESC") suspend fun all(): List<SessionRow>
    @Query("SELECT * FROM sessions WHERE id=:id") suspend fun get(id: String): SessionRow?
    @Query("SELECT * FROM sessions WHERE status='open' LIMIT 1") suspend fun active(): SessionRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(s: SessionRow)
    @Insert suspend fun enqueue(o: OutboxRow)
    @Query("SELECT * FROM outbox WHERE state!='confirmed' ORDER BY sessionId, revision") suspend fun pending(): List<OutboxRow>
    @Query("SELECT * FROM outbox WHERE `key`=:key") suspend fun item(key: String): OutboxRow
    @Update suspend fun update(o: OutboxRow)
}
@Database(entities = [SessionRow::class, OutboxRow::class, XmpRow::class], version = 2, exportSchema = true)
abstract class FieldDb : RoomDatabase() {
    abstract fun dao(): FieldDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1,2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `xmp_operations` (`sessionId` TEXT NOT NULL, `state` TEXT NOT NULL, `message` TEXT NOT NULL, `uri` TEXT, `name` TEXT, `beforeHash` TEXT, `afterHash` TEXT, `protectedHash` TEXT, `backupPath` TEXT, `selectionMethod` TEXT, PRIMARY KEY(`sessionId`))")
            }
        }
        @Volatile private var instance: FieldDb? = null
        fun get(c: Context): FieldDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(c.applicationContext, FieldDb::class.java, "field2.db")
                .addMigrations(MIGRATION_1_2).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build().also { instance = it }
        }
    }
}
class Repository(private val db: FieldDb) {
    suspend fun create(s: FieldSession) = db.withTransaction {
        s.validate(); require(db.dao().get(s.session_id) == null) { "UUID už existuje" }
        if (s.session_status == "open") require(db.dao().active() == null) { "Jiná relace je otevřená" }
        save(s)
    }
    suspend fun change(id: String, transform: (FieldSession) -> FieldSession) = db.withTransaction {
        val old = wire.decodeFromString<FieldSession>(requireNotNull(db.dao().get(id)).payload)
        val s = transform(old); s.validate()
        require(s.session_id == old.session_id && s.revision == old.revision + 1)
        save(s)
    }
    private suspend fun save(s: FieldSession) {
        val p = s.json()
        db.dao().put(SessionRow(s.session_id, s.session_status, s.updated_at, p))
        db.dao().enqueue(OutboxRow("${s.session_id}:${s.revision}", s.session_id, s.revision, p))
    }
}
class FieldApp : Application() {
    override fun onCreate() { super.onCreate(); scheduleSync(this); XmpWorker.resume(this) }
}
fun scheduleSync(c: Context, immediate: Boolean = false) {
    val constraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    val wm = WorkManager.getInstance(c)
    wm.enqueueUniquePeriodicWork("field-periodic", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraint).build())
    if (immediate) wm.enqueueUniqueWork("field-upload", ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
}
