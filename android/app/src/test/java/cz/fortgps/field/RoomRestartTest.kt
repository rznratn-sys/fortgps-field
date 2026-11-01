package cz.fortgps.field

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class RoomRestartTest {
    @Test fun mainActivityStartsWithoutAuthorization() {
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNotNull(controller.get().window.decorView)
        controller.pause().stop().destroy()
    }
    @Test fun storedSessionsAndOutboxSurviveDatabaseReopen() = runBlocking {
        val c = ApplicationProvider.getApplicationContext<Context>()
        val name = "room-restart.db"; c.deleteDatabase(name)
        fun open() = Room.databaseBuilder(c, FieldDb::class.java, name).build()
        var db = open(); var repo = Repository(db)
        val t = "2026-01-01T10:00:00Z"
        val m = FieldMeasurement(session_id = uuid(), measurement_role = "first_contact", measured_at = t,
            created_at = t, latitude = 0.0, longitude = 0.0, horizontal_accuracy_m = null)
        val s = SessionEngine.start(m); repo.create(s)
        repo.change(s.session_id) { SessionEngine.cancel(it, t) }
        val second = SessionEngine.start(m.copy(session_id = uuid(), measurement_id = uuid())); repo.create(second)
        val q = db.dao().pending().first()
        db.dao().update(q.copy(state = "sending", remoteId = "persisted-id", folderId = "persisted-folder"))
        db.close(); db = open(); repo = Repository(db)
        assertEquals(2, db.dao().all().size)
        assertEquals(second.session_id, db.dao().active()!!.id)
        assertEquals("persisted-id", db.dao().item(q.key).remoteId)
        assertEquals("sending", db.dao().item(q.key).state)
        try { repo.create(second); fail("Duplicate accepted") } catch (_: IllegalArgumentException) { }
        // Failed insertion must not change either table.
        assertEquals(2, db.dao().all().size); assertEquals(3, db.dao().pending().size)
        db.close(); c.deleteDatabase(name); Unit
    }
}
