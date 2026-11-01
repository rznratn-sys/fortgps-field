package cz.fortgps.field

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
class GnssUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun waitLiveFixStaleRecoveryAndCancel() {
        val state=mutableStateOf(GnssState(1,foreground=true))
        var saved:String?=null;var cancelled=false
        compose.setContent {MaterialTheme {GnssScreen(state.value,false,"",{saved=it},{cancelled=true},{})}}
        compose.onNodeWithText("Uložit tento fix").assertIsNotEnabled()
        val m=FieldMeasurement(session_id=uuid(),measurement_role="object_survey",measured_at="2026-11-01T08:00:00Z",created_at="2026-11-01T08:00:00Z",latitude=50.0,longitude=16.0,horizontal_accuracy_m=100.0,location_provider="gps",elapsed_realtime_nanos=2,is_mock=false)
        compose.runOnIdle {state.value=state.value.receive(m,2)}
        compose.onNodeWithText("Uložit tento fix").assertIsEnabled().performScrollTo().performClick()
        compose.runOnIdle {assertEquals(m.measurement_id,saved);state.value=state.value.copy(now=20_000_000_000L)}
        compose.onNodeWithText("Uložit tento fix").assertIsNotEnabled()
        compose.runOnIdle {state.value=state.value.receive(m.copy(measurement_id=uuid(),elapsed_realtime_nanos=21_000_000_000L),21_000_000_000L)}
        compose.onNodeWithText("Uložit tento fix").assertIsEnabled()
        compose.onNodeWithText("Zrušit měření").performScrollTo().performClick()
        compose.runOnIdle {assertTrue(cancelled)}
    }
    @Test fun errorAndPausedStatesCannotConfirm() {
        val state=mutableStateOf(GnssState(1,foreground=false,error="Chybí oprávnění"))
        var retried=false
        compose.setContent {MaterialTheme {GnssScreen(state.value,false,"",{fail()},{},{retried=true})}}
        compose.onNodeWithText("Uložit tento fix").assertIsNotEnabled()
        compose.onNodeWithText("Znovu připojit GNSS").performScrollTo().performClick()
        compose.runOnIdle {assertTrue(retried);state.value=state.value.copy(error=null)}
        compose.onNodeWithText("Uložit tento fix").assertIsNotEnabled()
    }
}
