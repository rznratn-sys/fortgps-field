package cz.fortgps.field

import org.junit.Assert.*
import org.junit.Test

class GnssTest {
    private fun m(t: Long=2_000_000_000L, accuracy: Double=100.0, provider: String="gps", lat: Double=50.0) =
        FieldMeasurement(session_id=uuid(), measurement_role="object_survey", measured_at="2026-11-01T08:00:00Z",
            created_at="2026-11-01T08:00:00Z", latitude=lat, longitude=16.0,
            horizontal_accuracy_m=accuracy, location_provider=provider, elapsed_realtime_nanos=t, is_mock=false)
    private fun state()=GnssState(1_000_000_000L,foreground=true)
    @Test fun networkFirstCannotBecomeMeasurementEvenWithExcellentAccuracy() {
        for (p in listOf("network", "fused", "passive", "")) assertFalse(state().receive(m(accuracy=0.1,provider=p),2_000_000_000L).canSave)
        val s=state().receive(m(accuracy=0.1,provider="network"),2_000_000_000L)
        assertNull(s.current);assertFalse(s.canSave)
        assertTrue(s.receive(m(),2_000_000_000L).canSave)
    }
    @Test fun poorFixMayBeConfirmedAndNeverAutoConfirmed() {
        val m=m(accuracy=900.0);val s=state().receive(m,2_000_000_000L)
        assertSame(m,s.confirmed(m.measurement_id,3_000_000_000L));assertEquals(1,s.count)
    }
    @Test fun latestPositionWinsOverHistoricalBest() {
        val s=state().receive(m(accuracy=5.0),2_000_000_000L)
        val moved=m(3_000_000_000L,50.0,lat=50.02)
        val next=s.receive(moved,3_000_000_000L)
        assertEquals(5.0,next.bestAccuracy!!,0.0);assertSame(moved,next.current)
        assertSame(moved,next.confirmed(moved.measurement_id,3_000_000_000L))
    }
    @Test fun improvementAndOutOfOrderDuplicateCallbacks() {
        var s=state().receive(m(),2_000_000_000L)
        s=s.receive(m(3_000_000_000L,10.0),3_000_000_000L)
        val current=s.current
        assertSame(current,s.receive(m(accuracy=1.0),4_000_000_000L).current)
        assertEquals(2,s.receive(current!!,4_000_000_000L).count)
    }
    @Test fun staleFutureMissingAndMockAreRejected() {
        val fix=m();val s=state().receive(fix,2_000_000_000L)
        assertTrue(s.copy(now=12_000_000_000L).canSave)
        assertFalse(s.copy(now=12_000_000_001L).canSave)
        for (bad in listOf(fix.copy(elapsed_realtime_nanos=null),fix.copy(elapsed_realtime_nanos=0),
            fix.copy(elapsed_realtime_nanos=9_000_000_000L),fix.copy(is_mock=true),fix.copy(horizontal_accuracy_m=null),fix.copy(latitude=Double.NaN))) {
            assertFalse(GnssPolicy.eligible(bad,1_000_000_000L,2_000_000_000L))
        }
    }
    @Test fun permissionDisabledBackgroundAndSignalLossInvalidate() {
        val s=state().receive(m(),2_000_000_000L)
        assertFalse(s.copy(error="permission").canSave);assertFalse(s.copy(foreground=false).canSave)
        assertFalse(s.copy(lost=true).canSave)
        val resumed=s.copy(acceptAfter=4_000_000_000L,lost=true)
        assertFalse(resumed.canSave)
        assertTrue(resumed.receive(m(5_000_000_000L),5_000_000_000L).canSave)
    }
    @Test fun confirmationPinsExactDisplayedFixAndRechecksAge() {
        val a=m();val b=m(3_000_000_000L)
        val s=state().receive(a,2_000_000_000L).receive(b,3_000_000_000L)
        for ((id,at) in listOf(a.measurement_id to 3_000_000_000L,b.measurement_id to 20_000_000_000L)) {
            try {s.confirmed(id,at);fail()} catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun noFixCanWaitIndefinitelyWithoutSaving() {
        val s=state().copy(now=Long.MAX_VALUE/2)
        assertNull(s.current);assertFalse(s.canSave)
        assertTrue(s.label.contains("Čekám"))
    }
}
