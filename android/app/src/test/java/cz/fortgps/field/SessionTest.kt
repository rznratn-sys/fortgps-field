package cz.fortgps.field

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*

class SessionTest {
    @Test fun privateOriginalField11AuditWhenProvided() {
        val path = System.getenv("FIELD_LEGACY_FIXTURE") ?: return
        val original = java.io.File(path).readText()
        val sessions = Legacy.read(original)
        val records = wire.parseToJsonElement(original).jsonObject["records"]!!.jsonArray
        assertEquals(records.size, sessions.size)
        sessions.zip(records).forEach { (s, raw) ->
            assertEquals(raw.jsonObject["id"]!!.jsonPrimitive.content, s.session_id)
            assertEquals("legacy_observer", s.measurements.single().measurement_role)
            assertNull(s.measurements.single().altitude_m)
            assertEquals(s, Legacy.read(s.json()).single())
        }
    }
    private val t = "2026-01-01T10:00:00Z"
    private fun m(id: String = uuid(), time: String = t, lat: Double = 50.0) = FieldMeasurement(session_id = id,
        measurement_role = "first_contact", measured_at = time, created_at = time, latitude = lat,
        longitude = 16.0, horizontal_accuracy_m = 14.8)
    @Test fun roundTripKeepsIdsAndNulls() {
        val s = SessionEngine.start(m())
        assertEquals(s, wire.decodeFromString<FieldSession>(s.json()))
        assertTrue(s.json().contains("\"altitude_m\":null"))
        assertEquals(s.measurements[0].session_id, s.session_id)
    }
    @Test fun independentFixHundredsOfMetersAwayAllowed() {
        val first = m(); val s = SessionEngine.start(first)
        val end = m(first.session_id, "2026-01-01T10:30:00Z", 50.02)
        val done = SessionEngine.finish(s, end, "velky_bunkr", "at_entrance", "", end.created_at)
        assertEquals(2, done.measurements.size)
        assertNotEquals(first.measurement_id, done.measurements[1].measurement_id)
        assertEquals("object_survey", done.measurements[1].measurement_role)
        assertEquals("completed", done.session_status)
    }
    @Test fun missingGpsClosePreservesFirst() {
        val s = SessionEngine.start(m())
        val done = SessionEngine.finish(s, null, "jednoduchy_bunkr", "safe_nearby", "Nelze", t)
        assertEquals("incomplete", done.session_status); assertEquals(s.measurements, done.measurements)
        assertTrue(done.warnings.contains("no_safe_object_survey"))
    }
    @Test fun cancelRetainsEvidence() {
        val s = SessionEngine.start(m()); val c = SessionEngine.cancel(s, t)
        assertEquals(s.measurements, c.measurements); assertEquals("cancelled", c.session_status)
    }
    @Test fun correctionDoesNotMoveMeasurement() {
        val s = SessionEngine.standalone(m(), "jednoduchy_bunkr", "on_roof", "")
        val r = SessionEngine.revise(s, "velky_bunkr", "oprava", t)
        assertEquals(s.session_id, r.session_id); assertEquals(s.measurements, r.measurements)
        assertEquals(s.revision + 1, r.revision)
    }
    @Test fun manySessionsUniqueIds() {
        assertEquals(1000, (0..999).map { SessionEngine.start(m()).session_id }.toSet().size)
    }
    @Test fun reversedClockFlaggedWithoutLosingMeasurement() {
        val s = SessionEngine.start(m())
        val r = SessionEngine.finish(s, m(s.session_id, "2026-01-01T09:59:00Z"), "velky_bunkr", "safe_nearby", "", t)
        assertTrue("clock_order_conflict" in r.warnings); assertEquals(2, r.measurements.size)
    }
    @Test fun longSessionWarns() {
        val s = SessionEngine.start(m())
        assertTrue("long_session_over_2h_review" in SessionEngine.finish(s, null, "velky_bunkr", "safe_nearby", "", "2026-01-01T14:00:00Z").warnings)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidCoordinateRejected() { SessionEngine.start(m(lat = Double.NaN)) }
    @Test(expected = IllegalArgumentException::class) fun closedSessionCannotFinishAgain() {
        val s = SessionEngine.standalone(m(), "velky_bunkr", "safe_nearby", "")
        SessionEngine.finish(s, null, "velky_bunkr", "safe_nearby", "", t)
    }
    @Test(expected = IllegalArgumentException::class) fun missingHeightReferenceRejected() {
        SessionEngine.start(m().copy(altitude_m = 10.0))
    }
    @Test fun zeroAltitudeIsValidMeasurement() {
        assertEquals(0.0, SessionEngine.start(m().copy(altitude_m = 0.0, altitude_reference = "wgs84_ellipsoid")).measurements[0].altitude_m!!, 0.0)
    }
    private fun legacy() = buildJsonObject {
        put("id", "12345678-1234-4234-8234-123456789abc"); put("recorded_at", t); put("position_time", t)
        put("latitude", 50.0); put("longitude", 16.0); put("accuracy_m", 7.0); put("type", "jednoduchy_bunkr")
        put("note", "synthetic test"); put("position_role", "observer_device")
    }
    @Test fun legacyRetainsUuidAndMeaning() {
        val s = Legacy.migrate(legacy())
        assertEquals(legacy()["id"]!!.jsonPrimitive.content, s.session_id)
        assertEquals("legacy_observer", s.measurements[0].measurement_role)
        assertNull(s.started_at); assertNull(s.ended_at)
        assertEquals(s, Legacy.migrate(legacy()))
    }
    @Test fun legacyEnvelopeAndV2Backup() {
        val a = Legacy.read(buildJsonObject { put("records", JsonArray(listOf(legacy(), legacy().let { JsonObject(it + ("id" to JsonPrimitive(uuid()))) }))) }.toString())
        assertEquals(2, a.size)
        assertEquals(a, Legacy.read(buildJsonObject { put("sessions", JsonArray(a.map { wire.parseToJsonElement(it.json()) })) }.toString()))
    }
    @Test fun photoIdentityDoesNotNeedAbsolutePath() {
        val s = SessionEngine.start(m())
        val photo = FieldPhotoAssociation(s.session_id, PhotoReference("a".repeat(64), 4000, "renamable.jpg"))
        val with = s.copy(photo_associations = listOf(photo)).checked()
        assertEquals(photo.photo_reference.sha256, wire.decodeFromString<FieldSession>(with.json()).photo_associations[0].photo_reference.sha256)
        assertNull(photo.association_confidence)
        assertFalse(photo.photo_reference.original_identity_verified)
    }
}
