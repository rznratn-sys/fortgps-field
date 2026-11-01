package cz.fortgps.field

import android.Manifest
import android.app.Application
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class GnssLifecycleTest {
    private fun until(done:()->Boolean) {
        val end=System.nanoTime()+10_000_000_000L
        while(!done() && System.nanoTime()<end) {shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        assertTrue("Asynchronous state did not settle",done())
    }
    @Test fun cancelDoubleConfirmationBackgroundAndRecreationPreserveSession() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setProviderEnabled("gps",true)
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup()
        var activity=controller.get();val vm=ViewModelProvider(activity)[FieldViewModel::class.java]
        fun open(action:String) {
            vm.capture(action,"jednoduchy_bunkr","safe_nearby","")
            until {vm.pendingCapture.value!=null && !vm.busy.value}
        }
        open("start");vm.cancelMeasurement()
        assertNull(vm.pendingCapture.value)
        assertTrue(runBlocking {FieldDb.get(app).dao().all().isEmpty()})
        open("start")
        val id=vm.pendingCapture.value!!.sessionId
        val now=SystemClock.elapsedRealtimeNanos()
        val fix=FieldMeasurement(session_id=id,measurement_role="first_contact",measured_at=java.time.Instant.now().toString(),created_at=java.time.Instant.now().toString(),latitude=50.0,longitude=16.0,horizontal_accuracy_m=50.0,location_provider="gps",elapsed_realtime_nanos=now,is_mock=false)
        vm.gnss.value=GnssState(now,now,fix,50.0,1,foreground=true)
        vm.confirmFix(fix.measurement_id);vm.confirmFix(fix.measurement_id)
        until {!vm.busy.value}
        val db=FieldDb.get(app)
        val saved=runBlocking {db.dao().get(id)!!}
        assertEquals(1,runBlocking {db.dao().all().size})
        assertEquals(1,runBlocking {db.dao().pending().size})
        open("finish")
        val finishIntent=vm.pendingCapture.value
        val later=SystemClock.elapsedRealtimeNanos()
        vm.gnss.value=GnssState(later,later,fix.copy(measurement_id=uuid(),measurement_role="object_survey",elapsed_realtime_nanos=later),50.0,1,foreground=true)
        assertTrue(vm.gnss.value!!.canSave)
        controller.pause().stop()
        assertFalse(vm.gnss.value!!.canSave)
        controller.start().resume()
        assertFalse(vm.gnss.value!!.canSave)
        val config=android.content.res.Configuration(activity.resources.configuration)
        config.orientation=android.content.res.Configuration.ORIENTATION_LANDSCAPE
        controller.configurationChange(config)
        activity=controller.get()
        assertSame(vm,ViewModelProvider(activity)[FieldViewModel::class.java])
        assertEquals(finishIntent,vm.pendingCapture.value)
        assertFalse(vm.gnss.value!!.canSave)
        vm.cancelMeasurement()
        assertEquals(saved,runBlocking {db.dao().get(id)!!})
        open("finish")
        // Model/store teardown models loss of an unconfirmed measurement intent.
        controller.pause().stop().destroy();activity.viewModelStore.clear()
        val replacement=Robolectric.buildActivity(MainActivity::class.java).setup()
        val fresh=ViewModelProvider(replacement.get())[FieldViewModel::class.java]
        assertNull(fresh.pendingCapture.value)
        assertEquals(saved,runBlocking {db.dao().get(id)!!})
        assertEquals(id,runBlocking {db.dao().active()!!.id})
        replacement.pause().stop().destroy()
    }
}
