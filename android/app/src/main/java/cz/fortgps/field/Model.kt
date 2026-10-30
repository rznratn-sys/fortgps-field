package cz.fortgps.field

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.util.UUID

val wire = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
fun uuid(): String = UUID.randomUUID().toString()
val objectTypes = linkedMapOf("jednoduchy_bunkr" to "Jednoduchý bunkr", "velky_bunkr" to "Velký bunkr", "vstup_do_podzemi" to "Vstup do podzemí")
val relations = linkedMapOf("on_roof" to "Na stropě", "at_entrance" to "U vstupu", "safe_nearby" to "V bezpečné blízkosti")

@Serializable data class FieldMeasurement(
    val measurement_id: String = uuid(), val session_id: String,
    val measurement_role: String, val measured_at: String, val created_at: String,
    val latitude: Double, val longitude: Double, val horizontal_accuracy_m: Double?,
    val altitude_m: Double? = null, val vertical_accuracy_m: Double? = null,
    val altitude_reference: String? = null, val position_relation: String? = null,
    val location_provider: String? = null, val elapsed_realtime_nanos: Long? = null,
    val is_mock: Boolean? = null, val mean_sea_level_altitude_m: Double? = null,
    val mean_sea_level_accuracy_m: Double? = null,
    val mean_sea_level_reference: String? = null
)
@Serializable data class PhotoReference(val sha256: String, val size_bytes: Long, val original_name: String?,
    val hash_scope: String = "android_picker_delivered_bytes", val original_identity_verified: Boolean = false)
@Serializable data class FieldPhotoAssociation(
    val session_id: String, val photo_reference: PhotoReference,
    val association_method: String = "user_photo_picker",
    val association_status: String = "user_confirmed",
    val association_confidence: Double? = null
)
@Serializable data class FieldSession(
    val session_id: String = uuid(), val schema_version: String = "2.0",
    val revision: Int = 1, val session_mode: String = "photo",
    val started_at: String?, val ended_at: String? = null, val created_at: String,
    val updated_at: String, val session_status: String = "open",
    val object_type: String? = null, val note: String = "",
    val measurements: List<FieldMeasurement> = emptyList(),
    val photo_associations: List<FieldPhotoAssociation> = emptyList(),
    val warnings: List<String> = emptyList(), val verification_status: String = "unverified",
    val source: String = "FortGPS Field Android",
    val legacy_record_json: String? = null
) {
    fun validate() {
        require(UUID.fromString(session_id).toString() == session_id)
        require(schema_version == "2.0" && revision > 0)
        require(session_mode in setOf("photo", "standalone", "legacy"))
        require(session_status in setOf("open", "completed", "incomplete", "cancelled", "standalone", "legacy_unverified"))
        require(object_type == null || objectTypes.containsKey(object_type))
        Instant.parse(created_at); Instant.parse(updated_at)
        started_at?.let(Instant::parse); ended_at?.let(Instant::parse)
        require(measurements.map { it.measurement_id }.distinct().size == measurements.size)
        measurements.forEach {
            require(UUID.fromString(it.measurement_id).toString() == it.measurement_id)
            require(it.session_id == session_id)
            require(it.measurement_role in setOf("first_contact", "object_survey", "legacy_observer"))
            require(it.latitude.isFinite() && it.latitude in -90.0..90.0)
            require(it.longitude.isFinite() && it.longitude in -180.0..180.0)
            listOf(it.horizontal_accuracy_m, it.vertical_accuracy_m, it.mean_sea_level_accuracy_m).forEach { n -> require(n == null || (n.isFinite() && n >= 0)) }
            require(it.altitude_m == null || it.altitude_m.isFinite())
            require(it.mean_sea_level_altitude_m == null || it.mean_sea_level_altitude_m.isFinite())
            require((it.altitude_m == null) == (it.altitude_reference == null))
            require(it.position_relation == null || relations.containsKey(it.position_relation))
            Instant.parse(it.measured_at); Instant.parse(it.created_at)
        }
        photo_associations.forEach {
            require(it.session_id == session_id)
            require(it.photo_reference.sha256.matches(Regex("[0-9a-f]{64}")))
            require(it.photo_reference.size_bytes >= 0)
        }
        if (session_status == "open") require(ended_at == null)
        if (session_status == "open") require(session_mode == "photo" && measurements.any { it.measurement_role == "first_contact" })
        if (session_status == "standalone") require(session_mode == "standalone" && object_type != null && measurements.any { it.measurement_role == "object_survey" })
        measurements.filter { it.measurement_role == "object_survey" }.forEach { require(it.position_relation != null) }
        if (session_status == "completed") {
            require(started_at != null && ended_at != null && object_type != null)
            require(measurements.any { it.measurement_role == "first_contact" })
            require(measurements.any { it.measurement_role == "object_survey" })
        }
        if (session_status != "open" && session_status != "legacy_unverified") require(ended_at != null)
    }
    fun temporalWarnings(): List<String> = buildList {
        if (started_at == null || (session_status != "open" && ended_at == null)) add("incomplete_time_bounds")
        if (started_at != null && ended_at != null) {
            val d = Duration.between(Instant.parse(started_at), Instant.parse(ended_at))
            if (d.isNegative) add("clock_order_conflict")
            if (d > Duration.ofHours(2)) add("long_session_over_2h_review")
        }
        if (measurements.any { Duration.between(Instant.parse(it.measured_at), Instant.parse(it.created_at)).abs() > Duration.ofMinutes(5) }) add("device_and_fix_clock_disagree")
        if (measurements.any { it.is_mock == true }) add("mock_location")
    }
}

object SessionEngine {
    fun start(m: FieldMeasurement): FieldSession = FieldSession(session_id = m.session_id,
        started_at = m.measured_at, created_at = m.created_at, updated_at = m.created_at,
        measurements = listOf(m.copy(measurement_role = "first_contact", position_relation = null))).checked()

    fun finish(s: FieldSession, m: FieldMeasurement?, type: String, relation: String, note: String, now: String): FieldSession {
        require(s.session_status == "open"); require(type in objectTypes); require(relation in relations)
        require(m == null || m.session_id == s.session_id)
        return s.copy(revision = s.revision + 1, ended_at = m?.measured_at ?: now, updated_at = now,
            session_status = if (m == null) "incomplete" else "completed", object_type = type, note = note,
            measurements = s.measurements + listOfNotNull(m?.copy(measurement_role = "object_survey", position_relation = relation)),
            warnings = s.warnings + if (m == null) listOf("no_safe_object_survey") else emptyList()).checked()
    }
    fun standalone(m: FieldMeasurement, type: String, relation: String, note: String): FieldSession =
        FieldSession(session_id = m.session_id, session_mode = "standalone", session_status = "standalone",
            started_at = m.measured_at, ended_at = m.measured_at, created_at = m.created_at, updated_at = m.created_at,
            object_type = type, note = note, measurements = listOf(m.copy(measurement_role = "object_survey", position_relation = relation))).checked()
    fun cancel(s: FieldSession, now: String): FieldSession {
        require(s.session_status == "open")
        return s.copy(revision = s.revision + 1, ended_at = now, updated_at = now, session_status = "cancelled",
            warnings = s.warnings + "cancelled_by_user").checked()
    }
    fun revise(s: FieldSession, type: String, note: String, now: String): FieldSession =
        s.copy(revision = s.revision + 1, object_type = type, note = note, updated_at = now).checked()
}
fun FieldSession.checked(): FieldSession {
    val s = copy(warnings = (warnings + temporalWarnings()).distinct()); s.validate(); return s
}
fun FieldSession.json(): String = wire.encodeToString(this)
