package cz.fortgps.field

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

object Legacy {
    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    fun migrate(r: JsonObject): FieldSession {
        val id = requireNotNull(r.text("id")); require(UUID.fromString(id).toString() == id)
        val created = requireNotNull(r.text("recorded_at")); Instant.parse(created)
        val measured = r.text("position_time") ?: created; Instant.parse(measured)
        val type = r.text("type")?.takeIf { it in objectTypes }
        val m = FieldMeasurement(measurement_id = UUID.nameUUIDFromBytes("$id:legacy_observer".toByteArray(StandardCharsets.UTF_8)).toString(),
            session_id = id, measurement_role = "legacy_observer", measured_at = measured, created_at = created,
            latitude = requireNotNull(r["latitude"]?.jsonPrimitive?.doubleOrNull),
            longitude = requireNotNull(r["longitude"]?.jsonPrimitive?.doubleOrNull),
            horizontal_accuracy_m = r["accuracy_m"]?.jsonPrimitive?.doubleOrNull,
            location_provider = "web_geolocation_unspecified")
        return FieldSession(session_id = id, session_mode = "legacy", started_at = null, ended_at = null,
            created_at = created, updated_at = created, session_status = "legacy_unverified", object_type = type,
            note = r.text("note") ?: "", measurements = listOf(m), legacy_record_json = r.toString(),
            warnings = listOf("legacy_device_position_not_object_survey", "no_photo_session_bounds")).checked()
    }
    fun read(text: String): List<FieldSession> {
        val root = wire.parseToJsonElement(text)
        val records = when (root) {
            is JsonArray -> root
            is JsonObject -> root["records"] as? JsonArray ?: root["sessions"] as? JsonArray ?: JsonArray(listOf(root))
            else -> error("JSON není záznam ani záloha")
        }
        return records.map {
            val obj = it.jsonObject
            if (obj.text("schema_version") == "2.0") wire.decodeFromJsonElement<FieldSession>(obj).checked() else migrate(obj)
        }
    }
}
