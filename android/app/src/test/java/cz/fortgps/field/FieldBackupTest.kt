package cz.fortgps.field

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class FieldBackupTest {
    @Test fun privateAlpha2FieldBackupRoundTripAndOfflineReopen() = runBlocking {
        val path=System.getenv("FIELD_ALPHA2_BACKUP");assumeNotNull(path)
        val original=Legacy.read(java.io.File(path!!).readText())
        assertEquals(24,original.size)
        val day=original.filter {it.started_at?.startsWith("2026-11-01")==true}
        assertEquals(21,day.size);assertEquals(15,day.flatMap {it.measurements}.count {it.location_provider=="network"})
        assertEquals(3,day.count {it.xmp_result?.state=="failed"})
        val c=ApplicationProvider.getApplicationContext<Context>();val name="alpha2-field-backup.db";c.deleteDatabase(name)
        fun open()=Room.databaseBuilder(c,FieldDb::class.java,name).addMigrations(FieldDb.MIGRATION_1_2).build()
        var db=open();val repo=Repository(db)
        for(s in original) {assertEquals(s,Legacy.read(s.json()).single());repo.create(s)}
        val raw=db.dao().all().associate {it.id to it.payload}
        val queued=db.dao().pending();assertEquals(24,queued.size)
        db.dao().putXmp(XmpRow(day.first().session_id,state="permission_required",message="preserved",backupPath="private-backup"))
        db.close();db=open()
        assertEquals(raw,db.dao().all().associate {it.id to it.payload})
        assertEquals(queued,db.dao().pending())
        assertEquals("private-backup",db.dao().xmp(day.first().session_id)!!.backupPath)
        val exported="""{"format":"FortGPS Field 2.0 backup","sessions":[${db.dao().all().joinToString(",") {it.payload}}]}"""
        assertEquals(original.toSet(),Legacy.read(exported).toSet())
        db.close();c.deleteDatabase(name);Unit
    }
}
