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
    val message = MutableStateFlow("Připraveno. Data se nejdřív ukládají v telefonu.")
    val busy = MutableStateFlow(false)
    private var captureJob: Job? = null
    fun run(block: suspend () -> Unit) {
        if (busy.value) return
        captureJob = viewModelScope.launch {
            busy.value = true
            try { block() }
            catch (_: TimeoutCancellationException) { message.value = "Poloha nebyla do 35 s dostupná. Relace zůstala uložená; lze opakovat nebo uzavřít bez bodu." }
            catch (e: CancellationException) { message.value = "Měření zastaveno. Uložená data zůstávají."; throw e }
            catch (e: Exception) { message.value = e.message ?: "Operace selhala. Uložená data zůstávají." }
            finally { busy.value = false }
        }
    }
    fun stopMeasurement() { captureJob?.cancel() }
    fun capture(action: String, type: String, relation: String, note: String) = run {
        val active = db.dao().active()?.let { wire.decodeFromString<FieldSession>(it.payload) }
        val id = if (action == "finish") requireNotNull(active).session_id else uuid()
        message.value = "Zjišťuji novou polohu…"
        val m = LocationCapture(getApplication()).measure(id, if (action == "start") "first_contact" else "object_survey")
        when (action) {
            "start" -> repo.create(SessionEngine.start(m).copy(object_type = type, note = note).checked())
            "finish" -> repo.change(id) { SessionEngine.finish(it, m, type, relation, note, Instant.now().toString()) }
            "standalone" -> repo.create(SessionEngine.standalone(m, type, relation, note))
            else -> error("Neznámá akce")
        }
        message.value = "Uloženo v telefonu. Přesnost ±${m.horizontal_accuracy_m?.toInt() ?: "?"} m."
        scheduleSync(getApplication(), true)
    }
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
                else require(old.payload == s.json()) { "UUID existuje s jiným obsahem. Import se neprovedl." }
            }
        }
        message.value = "Importováno/ověřeno ${imported.size} záznamů."
        scheduleSync(getApplication(), true)
    }
}
