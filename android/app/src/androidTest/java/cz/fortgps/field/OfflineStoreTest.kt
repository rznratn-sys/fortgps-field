package cz.fortgps.field

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class) class OfflineStoreTest {
    @Test fun restartAndDuplicateAndCancelledSession() = runBlocking {
        val c = ApplicationProvider.getApplicationContext<Context>(); val name = "offline-test.db"; c.deleteDatabase(name)
        fun open() = Room.databaseBuilder(c, FieldDb::class.java, name).build()
        var db = open(); val repo = Repository(db)
        fun measurement(id: String = uuid()) = FieldMeasurement(session_id = id, measurement_role = "first_contact",
            measured_at = "2026-01-01T10:00:00Z", created_at = "2026-01-01T10:00:00Z", latitude = 50.0, longitude = 16.0, horizontal_accuracy_m = null)
        val s = SessionEngine.start(measurement()); repo.create(s)
        repo.change(s.session_id) { SessionEngine.cancel(it, "2026-01-01T10:01:00Z") }
        val other = SessionEngine.start(measurement()); repo.create(other)
        db.close(); db = open()
        assertEquals(2, db.dao().all().size); assertEquals(other.session_id, db.dao().active()!!.id)
        assertEquals(3, db.dao().pending().size)
        try { Repository(db).create(other); fail("Duplicate UUID accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(3, db.dao().pending().size)
        val item = db.dao().pending().first(); db.dao().update(item.copy(state = "sending", remoteId = "stable-test-id", folderId = "test-folder"))
        db.close(); db = open()
        assertEquals("stable-test-id", db.dao().item(item.key).remoteId)
        assertTrue(db.dao().pending().any { it.key == item.key })
        db.close(); c.deleteDatabase(name); Unit
    }
}
