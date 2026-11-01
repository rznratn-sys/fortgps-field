package cz.fortgps.field

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.Instant

class FieldViewModel(app: Application) : AndroidViewModel(app) {
    private val db = FieldDb.get(app)
    private val repo = Repository(db)
    val sessions = db.dao().observe().map { rows -> rows.map { wire.decodeFromString<FieldSession>(it.payload) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val queue = db.dao().observeQueue().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val xmpOperations = db.dao().observeXmp().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val xmpChoices = MutableStateFlow<Pair<String,List<TimedPhoto>>?>(null)
    fun retryXmp(id: String) { XmpWorker.enqueue(getApplication(),id); message.value="GPS zůstává uložené. XMP zařazeno k opakování." }
    fun chooseXmp(id: String) = run {
        xmpChoices.value = withContext(Dispatchers.IO) {
            val s=wire.decodeFromString<FieldSession>(requireNotNull(db.dao().get(id)).payload)
            require(s.session_status=="completed" && s.session_mode=="photo")
            id to MediaPhotos(getApplication()).find(s).filter { p ->
                p.time==null || (p.time.earliest>=Instant.parse(s.started_at) && p.time.latestExclusive.minusNanos(1)<=Instant.parse(s.ended_at))
            }
        }
    }
    fun selectXmp(id: String,p: TimedPhoto) = run {
        withContext(Dispatchers.IO) { XmpService(getApplication()).select(id,p.uri,p.name) }; xmpChoices.value=null
    }
    fun restoreXmp(id: String) = run { withContext(Dispatchers.IO) { XmpService(getApplication()).restoreOriginal(id) } }
    fun deniedXmp(id: String) = run { withContext(Dispatchers.IO) { XmpService(getApplication()).deny(id) } }
    val message = MutableStateFlow("Připraveno. Data se nejdřív ukládají v telefonu.")
    val busy = MutableStateFlow(false)
    private var captureJob: Job? = null
    fun run(block: suspend () -> Unit) {
        if (busy.value || pendingCapture.value != null) return
        captureJob = viewModelScope.launch {
            busy.value = true
            try { block() }
            catch (_: TimeoutCancellationException) { message.value = "Operace byla přerušena. Uložená data zůstávají." }
            catch (e: CancellationException) { message.value = "Měření zastaveno. Uložená data zůstávají."; throw e }
            catch (e: Exception) { message.value = e.message ?: "Operace selhala. Uložená data zůstávají." }
            finally { busy.value = false }
        }
    }
    fun stopMeasurement() { if (pendingCapture.value != null) cancelMeasurement() else captureJob?.cancel() }
    val pendingCapture = MutableStateFlow<PendingCapture?>(null)
    val gnss = MutableStateFlow<GnssState?>(null)
    private val location = LocationCapture(app)
    private var gnssTicker: Job? = null
    private var foreground = false
    private var committing = false
    fun capture(action: String, type: String, relation: String, note: String) {
        if (pendingCapture.value != null || busy.value) return
        val boundary = Instant.now().toString()
        val elapsed = android.os.SystemClock.elapsedRealtimeNanos()
        val epoch = clockEpoch(getApplication())
        val zone = java.time.ZoneId.systemDefault().id
        run {
            require(action in setOf("start", "finish", "standalone"))
            val active = db.dao().active()
            require(if (action == "finish") active != null else active == null) { "Stav otevřené relace se změnil." }
            pendingCapture.value = PendingCapture(action, if (action == "finish") active!!.id else uuid(),
                type, relation, note, boundary, elapsed, epoch, zone)
            gnss.value = GnssState(elapsed)
            if (foreground) resumeGnss()
        }
    }
    fun measurementForeground(visible: Boolean) {
        foreground = visible
        if (visible) resumeGnss() else pauseGnss()
    }
    private fun pauseGnss() {
        location.stop(); gnssTicker?.cancel(); gnssTicker = null
        gnss.value = gnss.value?.copy(foreground=false, lost=true, now=android.os.SystemClock.elapsedRealtimeNanos())
    }
    fun resumeGnss() {
        val p = pendingCapture.value ?: return
        if (!foreground || committing) return
        pauseGnss()
        val after = android.os.SystemClock.elapsedRealtimeNanos()
        gnss.value = gnss.value?.copy(acceptAfter=after, now=after, foreground=true, lost=true, error=null)
        try {
            location.start(p.sessionId, if (p.action == "start") "first_contact" else "object_survey", { m ->
                gnss.value = gnss.value?.receive(m, android.os.SystemClock.elapsedRealtimeNanos())
            }, {
                gnss.value = gnss.value?.copy(lost=true, acceptAfter=android.os.SystemClock.elapsedRealtimeNanos())
            })
            gnssTicker = viewModelScope.launch {
                while (isActive) {
                    val problem = runCatching { location.problem() }.getOrElse { it.message ?: "Služba polohy selhala" }
                    gnss.value = gnss.value?.copy(now=android.os.SystemClock.elapsedRealtimeNanos(), error=problem)
                    if (problem != null) {
                        location.stop()
                        gnss.value = gnss.value?.copy(lost=true, acceptAfter=android.os.SystemClock.elapsedRealtimeNanos())
                        break
                    }
                    delay(500)
                }
            }
        } catch (e: Exception) { gnss.value = gnss.value?.copy(error=e.message ?: "GNSS služba selhala", lost=true) }
    }
    fun cancelMeasurement() {
        if (busy.value) return
        pauseGnss(); pendingCapture.value=null; gnss.value=null
        message.value="Měření zrušeno. Rozpracovaná relace zůstává otevřená."
    }
    fun confirmFix(expectedId: String) {
        if (busy.value) return
        val p = pendingCapture.value ?: return
        val now = Instant.now().toString()
        val elapsed = android.os.SystemClock.elapsedRealtimeNanos()
        val m = try {
            location.problem()?.let { error(it) }
            requireNotNull(gnss.value).confirmed(expectedId, elapsed)
        } catch (e: Exception) { message.value=e.message ?: "Fix nelze uložit"; return }
        // Latch synchronously before launching IO: double taps cannot create two commits.
        busy.value=true; committing=true
        pauseGnss()
        captureJob=viewModelScope.launch {
            try {
                when (p.action) {
                    "start" -> repo.create(SessionEngine.start(m).copy(started_at=now, created_at=now, updated_at=now,
                        photo_timezone_start=java.time.ZoneId.systemDefault().id,
                        object_type=p.type, note=p.note, photo_clock_epoch_start=clockEpoch(getApplication()),
                        photo_boundary_policy="manual_gnss_v1", photo_started_elapsed_nanos=elapsed).checked())
                    "finish" -> repo.change(p.sessionId) { old ->
                        SessionEngine.finish(old, m, p.type, p.relation, p.note, now).copy(
                            ended_at=p.photoEndAt, photo_timezone_end=p.timezone,
                            photo_clock_epoch_end=p.clockEpoch,
                            photo_boundary_policy=if (old.photo_boundary_policy == "manual_gnss_v1") "manual_gnss_v1" else "mixed_alpha2_alpha3",
                            photo_ended_elapsed_nanos=p.photoEndElapsed).checked()
                    }
                    "standalone" -> repo.create(SessionEngine.standalone(m, p.type, p.relation, p.note).copy(created_at=now, updated_at=now).checked())
                }
                pendingCapture.value=null; gnss.value=null
                message.value="Uloženo v telefonu. GNSS ±${m.horizontal_accuracy_m} m."
                scheduleSync(getApplication(), true)
                if (p.action == "finish") {
                    try {
                        db.dao().putXmp(XmpRow(p.sessionId)); XmpWorker.enqueue(getApplication(),p.sessionId)
                        message.value += " XMP čeká na kontrolu; výsledek je v historii relací."
                    } catch(e: Exception) { message.value += " XMP se nepodařilo zařadit: ${e.message}. Opakuj v historii." }
                }
            } catch(e: Exception) {
                message.value=e.message ?: "Uložení selhalo; relace zůstala zachovaná."
            } finally { busy.value=false; committing=false; if(pendingCapture.value != null && foreground) resumeGnss() }
        }
    }
    override fun onCleared() { location.stop(); super.onCleared() }
    fun closeWithoutSurvey(type: String, relation: String, note: String) = run {
        val id = requireNotNull(db.dao().active()).id
        repo.change(id) { SessionEngine.finish(it, null, type, relation, note, Instant.now().toString()) }
        message.value = "Uzavřeno bez zaměření objektu. Pozorování je zachované."
        scheduleSync(getApplication(), true)
    }
    fun cancel() = run {
        val id = requireNotNull(db.dao().active()).id
        repo.change(id) { SessionEngine.cancel(it, Instant.now().toString()) }
        message.value = "Relace zrušená, původní měření zachované."
        scheduleSync(getApplication(), true)
    }
    fun edit(id: String, type: String, note: String) = run {
        repo.change(id) { SessionEngine.revise(it, type, note, Instant.now().toString()) }
        message.value = "Oprava uložená jako nová revize."
        scheduleSync(getApplication(), true)
    }
    fun addPhotos(id: String, uris: List<Uri>) = run {
        val refs = withContext(Dispatchers.IO) {
            uris.map { uri ->
                val resolver = getApplication<Application>().contentResolver
                var name: String? = null
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0)
                }
                val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
                requireNotNull(resolver.openInputStream(uri)).use { input ->
                    val b = ByteArray(65536)
                    while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n); size += n }
                }
                FieldPhotoAssociation(id, PhotoReference(digest.digest().joinToString("") { "%02x".format(it) }, size, name))
            }
        }
        repo.change(id) { s -> s.copy(revision = s.revision + 1, updated_at = Instant.now().toString(),
            photo_associations = (s.photo_associations + refs).distinctBy { it.photo_reference.sha256 }).checked() }
        message.value = "Potvrzeno ${refs.size} fotografií. Originály se neměnily."
        scheduleSync(getApplication(), true)
    }
    fun export(uri: Uri) = run {
        withContext(Dispatchers.IO) {
            val all = db.dao().all().map { wire.parseToJsonElement(it.payload) }
            val content = buildJsonObject { put("format", "FortGPS Field 2.0 backup"); put("sessions", JsonArray(all)) }.toString()
            requireNotNull(getApplication<Application>().contentResolver.openOutputStream(uri, "wt")).use { it.write(content.toByteArray(Charsets.UTF_8)) }
        }
        message.value = "Záloha JSON vyexportována. Obsahuje aktuální relace, nikoli přenosové přihlašovací údaje."
    }
    fun import(uri: Uri) = run {
        val text = withContext(Dispatchers.IO) {
            requireNotNull(getApplication<Application>().contentResolver.openInputStream(uri)).use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= 20 * 1024 * 1024) { "Import je větší než 20 MiB" }
                    output.write(buffer, 0, n)
                }
                output.toString("UTF-8")
            }
        }
        val imported = Legacy.read(text)
        db.withTransaction {
            imported.forEach { s ->
                val old = db.dao().get(s.session_id)
                if (old == null) repo.create(s)
                else require(wire.decodeFromString<FieldSession>(old.payload) == s) { "UUID existuje s jiným obsahem. Import se neprovedl." }
            }
        }
        message.value = "Importováno/ověřeno ${imported.size} záznamů."
        scheduleSync(getApplication(), true)
    }
}
