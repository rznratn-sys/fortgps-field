package cz.fortgps.field
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class MigrationTest {
    @Test fun alpha1PayloadAndQueueSurviveUpgrade() = runBlocking {
        val c=ApplicationProvider.getApplicationContext<Context>();val name="alpha1-upgrade.db";c.deleteDatabase(name)
        val file=c.getDatabasePath(name);file.parentFile!!.mkdirs()
        val schema=JSONObject(javaClass.getResource("/room-v1.json")!!.readText()).getJSONObject("database")
        val s=XmpTest().session();val oldJson=JSONObject(s.json())
        listOf("xmp_result","photo_timezone_start","photo_timezone_end","photo_clock_epoch_start","photo_clock_epoch_end","photo_boundary_policy","photo_started_elapsed_nanos","photo_ended_elapsed_nanos").forEach(oldJson::remove)
        val payload=oldJson.toString()
        SQLiteDatabase.openOrCreateDatabase(file,null).use {old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val e=entities.getJSONObject(i);old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
                val indices=e.getJSONArray("indices");for(j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
            }
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES(42,?)",arrayOf(schema.getString("identityHash")))
            old.execSQL("INSERT INTO sessions VALUES(?,?,?,?)",arrayOf(s.session_id,s.session_status,s.updated_at,payload))
            old.execSQL("INSERT INTO outbox VALUES(?,?,?,?,'sending','remote-id','folder-id',NULL,NULL)",arrayOf("${s.session_id}:1",s.session_id,1,payload));old.version=1
        }
        val db=Room.databaseBuilder(c,FieldDb::class.java,name).addMigrations(FieldDb.MIGRATION_1_2).build()
        assertEquals(payload,db.dao().get(s.session_id)!!.payload);assertEquals(payload,db.dao().item("${s.session_id}:1").payload)
        assertEquals("remote-id",db.dao().item("${s.session_id}:1").remoteId)
        db.dao().putXmp(XmpRow(s.session_id,state="permission_required",message="denied"));db.close()
        val again=Room.databaseBuilder(c,FieldDb::class.java,name).addMigrations(FieldDb.MIGRATION_1_2).build()
        assertEquals("permission_required",again.dao().xmp(s.session_id)!!.state);assertEquals(payload,again.dao().get(s.session_id)!!.payload)
        again.close();c.deleteDatabase(name);Unit
    }
}
