package cz.fortgps.field

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PhotoBoundaryTest {
    fun session(): FieldSession {
        val s=XmpTest().session();val start="2026-11-01T08:00:00Z";val end="2026-11-01T08:05:00Z"
        return s.copy(started_at=start,ended_at=end,photo_boundary_policy="manual_gnss_v1",
            photo_started_elapsed_nanos=100_000_000_000L,photo_ended_elapsed_nanos=400_000_000_000L,
            measurements=s.measurements.mapIndexed { i,m ->
                val t=if(i==0) "2026-11-01T07:59:58Z" else "2026-11-01T08:08:00Z"
                m.copy(measured_at=t,created_at=t,location_provider="gps",is_mock=false,
                    elapsed_realtime_nanos=if(i==0) 98_000_000_000L else 580_000_000_000L)
            })
    }
    @Test fun longWaitDoesNotExtendPhotoInterval() {
        val s=session();assertTrue(PhotoTime.boundsSafe(s))
        fun p(name:String,t:String)=TimedPhoto(name,name,CaptureTime(Instant.parse(t),Instant.parse(t).plusMillis(1)))
        val photos=listOf(p("last","2026-11-01T08:04:59Z"),p("duringGpsWait","2026-11-01T08:06:00Z"))
        assertEquals("last",PhotoTime.choose(s,photos).photo!!.name)
        assertEquals("no_photo",PhotoTime.choose(s,photos.takeLast(1)).state)
    }
    @Test fun clockJumpRebootMissingBoundaryAndMixedSessionFailClosed() {
        val s=session()
        for(bad in listOf(s.copy(photo_ended_elapsed_nanos=1),s.copy(ended_at="2026-11-01T08:07:00Z"),
            s.copy(photo_started_elapsed_nanos=null),s.copy(photo_boundary_policy="mixed_alpha2_alpha3"),
            s.copy(photo_clock_epoch_start=1,photo_clock_epoch_end=2),
            s.copy(measurements=s.measurements.map {it.copy(measured_at="2026-11-01T09:00:00Z")}))) assertFalse(PhotoTime.boundsSafe(bad))
    }
    @Test fun legacyPolicyStillUsesAlpha2Checks() { assertTrue(PhotoTime.boundsSafe(XmpTest().session())) }
}
