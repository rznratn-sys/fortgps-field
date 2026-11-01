package cz.fortgps.field

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import java.time.Instant

/** Foreground subscription only. Never requests network or cached locations. */
class LocationCapture(private val c: Context) {
    private val lm = c.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    private var status: GnssStatus.Callback? = null
    fun problem(): String? = when {
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> "Chybí oprávnění k přesné poloze. Povol přesnou polohu v nastavení aplikace."
        !lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> "GNSS / určování polohy je vypnuté."
        else -> null
    }
    @Suppress("MissingPermission", "DEPRECATION")
    fun start(session: String, role: String, onFix: (FieldMeasurement) -> Unit, onLost: () -> Unit) {
        stop()
        problem()?.let { error(it) }
        val l = object : LocationListener {
            override fun onLocationChanged(l: Location) {
                if (listener !== this) return
                onFix(FieldMeasurement(session_id = session, measurement_role = role,
                    measured_at = Instant.ofEpochMilli(l.time).toString(), created_at = Instant.now().toString(),
                    latitude = l.latitude, longitude = l.longitude,
                    horizontal_accuracy_m = if (l.hasAccuracy()) l.accuracy.toDouble() else null,
                    altitude_m = if (l.hasAltitude()) l.altitude else null,
                    vertical_accuracy_m = if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters.toDouble() else null,
                    altitude_reference = if (l.hasAltitude()) "wgs84_ellipsoid" else null,
                    location_provider = l.provider, elapsed_realtime_nanos = l.elapsedRealtimeNanos,
                    is_mock = if (Build.VERSION.SDK_INT >= 31) l.isMock else l.isFromMockProvider,
                    mean_sea_level_altitude_m = if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) l.mslAltitudeMeters else null,
                    mean_sea_level_accuracy_m = if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitudeAccuracy()) l.mslAltitudeAccuracyMeters.toDouble() else null,
                    mean_sea_level_reference = if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) "android_msl_unspecified_national_datum" else null))
            }
            override fun onProviderDisabled(provider: String) { if (listener === this) onLost() }
            override fun onProviderEnabled(provider: String) { }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
        }
        val s = object : GnssStatus.Callback() {
            override fun onStopped() { if (status === this) onLost() }
        }
        listener = l; status = s
        try {
            lm.registerGnssStatusCallback(s, Handler(Looper.getMainLooper()))
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
        } catch (e: Exception) { stop(); throw e }
    }
    fun stop() {
        val l = listener; val s = status
        listener = null; status = null
        l?.let { runCatching { lm.removeUpdates(it) } }
        s?.let { runCatching { lm.unregisterGnssStatusCallback(it) } }
    }
}
