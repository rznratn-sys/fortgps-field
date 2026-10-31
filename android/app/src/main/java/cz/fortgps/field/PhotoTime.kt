package cz.fortgps.field

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** A whole-second EXIF value is an uncertainty interval, never a fabricated millisecond. */
data class CaptureTime(val earliest: Instant, val latestExclusive: Instant)
data class TimedPhoto(val uri: String, val name: String, val time: CaptureTime?)
data class PhotoDecision(val state: String, val photo: TimedPhoto? = null, val reason: String)
object PhotoTime {
    fun parse(original: String?, offset: String?, subsecond: String?, zone: String?): CaptureTime? = runCatching {
        require(!original.isNullOrBlank())
        var local = LocalDateTime.parse(original, DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT))
        val sub = subsecond?.trim()?.takeIf { it.isNotEmpty() }
        require(sub == null || (sub.length <= 9 && sub.all(Char::isDigit)))
        if (sub != null) local = local.withNano(sub.padEnd(9, '0').toInt())
        val off = if (!offset.isNullOrBlank()) ZoneOffset.of(offset.trim()) else {
            require(zone != null)
            val offsets = ZoneId.of(zone).rules.getValidOffsets(local)
            require(offsets.size == 1) // DST overlap/gap requires a manual decision.
            offsets.single()
        }
        val first = local.toInstant(off)
        val resolution = if (sub == null) 1_000_000_000L else Math.pow(10.0, (9-sub.length).toDouble()).toLong()
        CaptureTime(first, first.plusNanos(resolution))
    }.getOrNull()

    fun boundsSafe(s: FieldSession): Boolean = runCatching {
        require(s.session_mode == "photo" && s.session_status == "completed")
        val start = Instant.parse(s.started_at); val end = Instant.parse(s.ended_at)
        require(start < end)
        require(s.photo_clock_epoch_start == s.photo_clock_epoch_end)
        val first = s.measurements.single { it.measurement_role == "first_contact" }
        val last = s.measurements.single { it.measurement_role == "object_survey" }
        require(first.measured_at == s.started_at && last.measured_at == s.ended_at)
        require(s.measurements.none { Duration.between(Instant.parse(it.measured_at), Instant.parse(it.created_at)).abs() > Duration.ofSeconds(5) || it.is_mock == true })
        if (first.elapsed_realtime_nanos != null && last.elapsed_realtime_nanos != null) {
            val mono = last.elapsed_realtime_nanos - first.elapsed_realtime_nanos
            require(mono > 0 && Duration.between(start,end).minusNanos(mono).abs() <= Duration.ofSeconds(2))
        }
        true
    }.getOrDefault(false)

    fun choose(s: FieldSession, photos: List<TimedPhoto>): PhotoDecision {
        if (!boundsSafe(s)) return PhotoDecision("ambiguous", reason="Neplatné hranice nebo nesoulad hodin. Vyber snímek ručně.")
        val start = Instant.parse(s.started_at); val end = Instant.parse(s.ended_at)
        // Unknown times may hide the last shot; DATE_TAKEN is not independent EXIF proof.
        if (photos.any { it.time == null }) return PhotoDecision("ambiguous", reason="Chybí jednoznačný EXIF čas. Vyber snímek ručně.")
        val intersect = photos.filter { it.time!!.earliest <= end && it.time.latestExclusive > start }
        if (intersect.any { it.time!!.earliest < start || it.time.latestExclusive.minusNanos(1) > end })
            return PhotoDecision("ambiguous", reason="Přesnost EXIF času nestačí na hranici relace. Vyber snímek ručně.")
        if (intersect.isEmpty()) return PhotoDecision("no_photo", reason="V intervalu relace nebyla nalezena fotografie.")
        val latest = intersect.maxBy { it.time!!.earliest }
        if (intersect.any { it != latest && it.time!!.latestExclusive > latest.time!!.earliest })
            return PhotoDecision("ambiguous", reason="Poslední fotografii nelze určit jednoznačně. Vyber snímek ručně.")
        return PhotoDecision("pending", latest, "Jednoznačně poslední fotografie dle EXIF.")
    }
}
