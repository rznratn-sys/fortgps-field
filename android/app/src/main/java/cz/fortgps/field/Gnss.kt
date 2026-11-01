package cz.fortgps.field

/** Application freshness policy, not a claim about positioning accuracy. */
object GnssPolicy {
    const val MAX_AGE_NANOS = 10_000_000_000L
    fun eligible(m: FieldMeasurement, after: Long, now: Long): Boolean {
        val t = m.elapsed_realtime_nanos ?: return false
        return m.location_provider == "gps" && m.is_mock != true && t >= after && t <= now &&
            now - t <= MAX_AGE_NANOS && m.latitude.isFinite() && m.latitude in -90.0..90.0 &&
            m.longitude.isFinite() && m.longitude in -180.0..180.0 &&
            m.horizontal_accuracy_m?.let { it.isFinite() && it >= 0 } == true
    }
}
data class GnssState(
    val acceptAfter: Long, val now: Long = acceptAfter,
    val current: FieldMeasurement? = null, val bestAccuracy: Double? = null,
    val count: Int = 0, val foreground: Boolean = false,
    val lost: Boolean = false, val error: String? = null
) {
    val ageSeconds: Double? get() = current?.elapsed_realtime_nanos?.let { (now-it)/1e9 }
    val canSave: Boolean get() = foreground && !lost && error == null &&
        current?.let { GnssPolicy.eligible(it, acceptAfter, now) } == true
    val label: String get() = when {
        error != null -> "Chyba lokalizace: $error"
        !foreground -> "Měření pozastavené – po návratu čekám na nový GNSS fix"
        lost && current != null -> "Ztráta GNSS signálu – poslední fix nelze uložit"
        current != null && !canSave -> "Zastaralý fix – čekám na nový GNSS fix"
        current == null -> "Čekám na první satelitní GNSS fix"
        count == 1 -> "Aktuální platný GNSS fix"
        else -> "Zpřesňování – průběžné GNSS měření"
    }
    fun receive(m: FieldMeasurement, time: Long): GnssState {
        if (!foreground || error != null || !GnssPolicy.eligible(m, acceptAfter, time)) return copy(now=time)
        if (current?.elapsed_realtime_nanos?.let { m.elapsed_realtime_nanos!! <= it } == true) return copy(now=time)
        return copy(current=m, now=time, count=count+1, lost=false,
            bestAccuracy=minOf(bestAccuracy ?: Double.POSITIVE_INFINITY, m.horizontal_accuracy_m!!))
    }
    fun confirmed(expectedId: String, time: Long): FieldMeasurement {
        require(copy(now=time).canSave && current?.measurement_id == expectedId) { "Fix se změnil nebo zestárl. Zkontroluj aktuální údaje a potvrď znovu." }
        return requireNotNull(current)
    }
}
data class PendingCapture(val action: String, val sessionId: String, val type: String,
    val relation: String, val note: String, val photoEndAt: String,
    val photoEndElapsed: Long, val clockEpoch: Long, val timezone: String)
