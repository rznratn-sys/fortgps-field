package cz.fortgps.field

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.*
import kotlinx.coroutines.*
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LocationCapture(private val c: Context) {
    @Suppress("MissingPermission", "DEPRECATION")
    suspend fun measure(session: String, role: String): FieldMeasurement = withContext(Dispatchers.Main.immediate) {
        require(c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "Povol polohu telefonu" }
        val lm = c.getSystemService(LocationManager::class.java)
        val fine = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { (fine || it != LocationManager.GPS_PROVIDER) && lm.isProviderEnabled(it) }
        require(providers.isNotEmpty()) { "GPS/poloha je vypnutá nebo nedostupná" }
        val requestedAt = SystemClock.elapsedRealtimeNanos()
        withTimeout(35_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(l: Location) {
                        // Never reuse a cached position from the previous measurement.
                        if (l.elapsedRealtimeNanos < requestedAt || !cont.isActive) return
                        lm.removeUpdates(this)
                        val m = FieldMeasurement(session_id = session, measurement_role = role,
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
                            mean_sea_level_reference = if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) "android_msl_unspecified_national_datum" else null)
                        cont.resume(m)
                    }
                    override fun onProviderDisabled(provider: String) { }
                    override fun onProviderEnabled(provider: String) { }
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                try { providers.forEach { lm.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) } }
                catch (e: Exception) { lm.removeUpdates(listener); if (cont.isActive) cont.resumeWithException(e) }
            }
        }
    }
}
