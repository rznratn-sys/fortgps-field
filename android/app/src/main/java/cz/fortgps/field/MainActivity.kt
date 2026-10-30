package cz.fortgps.field

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.auth.api.identity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

class MainActivity : ComponentActivity() {
    private lateinit var vm: FieldViewModel
    private var photoSessionId: String? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.message.value = "Oprávnění vyhodnoceno. Pro nové měření stiskni znovu tlačítko."
    }
    private val authorizeResult = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try {
            val auth = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data)
            completeAuthorization(auth)
        } catch (_: Exception) { vm.message.value = "Připojení Google nebylo dokončeno. Lokální data zůstávají." }
    }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        val id = photoSessionId
        if (id != null && uris.isNotEmpty()) vm.addPhotos(id, uris)
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::export) }
    private val import = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        photoSessionId = savedInstanceState?.getString("photoSessionId")
        vm = ViewModelProvider(this)[FieldViewModel::class.java]
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB7DF7C), onPrimary = Color(0xFF122515),
                background = Color(0xFF101D19), surface = Color(0xFF20352E), onSurface = Color(0xFFF0F5ED))) {
                FieldScreen(vm, onCapture = { a, t, r, n ->
                    if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                        permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    else vm.capture(a, t, r, n)
                }, onConnect = ::connect, onPhotos = { id ->
                    photoSessionId = id
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, onExport = { export.launch("FortGPS_Field_2_backup.json") }, onImport = { import.launch(arrayOf("application/json", "text/plain")) })
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); outState.putString("photoSessionId", photoSessionId)
    }
    private fun connect(email: String, folderInput: String) {
        val folder = folderInput.trim().substringAfter("/folders/").substringBefore('?').substringBefore('/')
        if (!email.contains('@') || !folder.matches(Regex("[A-Za-z0-9_-]{10,}"))) {
            vm.message.value = "Zadej Google účet a ID nebo odkaz na původní importní složku."; return
        }
        getSharedPreferences("drive", Context.MODE_PRIVATE).edit().putBoolean("enabled", false)
            .putString("email", email.trim()).putString("folder", folder).apply()
        Identity.getAuthorizationClient(this).authorize(authRequest(email.trim()))
            .addOnSuccessListener { auth ->
                if (auth.hasResolution()) authorizeResult.launch(IntentSenderRequest.Builder(requireNotNull(auth.pendingIntent).intentSender).build())
                else completeAuthorization(auth)
            }.addOnFailureListener { vm.message.value = "Google autorizace selhala. Zkontroluj Android OAuth klienta, balíček a SHA-1 certifikátu." }
    }
    private fun completeAuthorization(auth: AuthorizationResult) {
        lifecycleScope.launch {
            try {
                val token = requireNotNull(auth.accessToken) { "Google nevrátil token" }
                val prefs = getSharedPreferences("drive", Context.MODE_PRIVATE)
                withContext(Dispatchers.IO) { DriveClient(token).validateDestination(prefs.getString("folder", "")!!, prefs.getString("email", "")!!) }
                prefs.edit().putBoolean("enabled", true).apply()
                vm.message.value = "Účet i cílová složka ověřeny. Synchronizace zařazena."
                scheduleSync(this@MainActivity, true)
            } catch (e: Exception) { vm.message.value = e.message ?: "Nelze ověřit cílovou složku" }
        }
    }
}

private fun statusLabel(s: String) = when (s) {
    "open" -> "OTEVŘENÁ RELACE"
    "completed" -> "UZAVŘENÁ • ZAMĚŘENO"
    "incomplete" -> "UZAVŘENÁ • BEZ ZAMĚŘENÍ"
    "cancelled" -> "ZRUŠENÁ • DATA ZACHOVÁNA"
    "standalone" -> "SAMOSTATNÝ BOD"
    else -> "IMPORT 1.1 • POLOHA ZAŘÍZENÍ"
}
@Composable private fun BigButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(label, fontSize = 19.sp) }
}
@Composable private fun Choice(label: String, selected: String, options: Map<String, String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("$label: ${options[selected]}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onChange(id); expanded = false }, modifier = Modifier.heightIn(min = 56.dp)) }
        }
    }
}
@Composable private fun FieldScreen(vm: FieldViewModel, onCapture: (String, String, String, String) -> Unit,
    onConnect: (String, String) -> Unit, onPhotos: (String) -> Unit, onExport: () -> Unit, onImport: () -> Unit) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val active = sessions.firstOrNull { it.session_status == "open" }
    var type by rememberSaveable { mutableStateOf("jednoduchy_bunkr") }
    var relation by rememberSaveable { mutableStateOf("safe_nearby") }
    var note by rememberSaveable(active?.session_id) { mutableStateOf(active?.note ?: "") }
    LaunchedEffect(active?.session_id) { active?.object_type?.let { type = it } }
    var history by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var editType by rememberSaveable { mutableStateOf("jednoduchy_bunkr") }
    var editNote by rememberSaveable { mutableStateOf("") }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("FortGPS Field", style = MaterialTheme.typography.headlineLarge)
            Text("2.0 • Terénní důkazy", color = MaterialTheme.colorScheme.primary)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (active == null) "ŽÁDNÁ OTEVŘENÁ RELACE" else statusLabel(active.session_status), style = MaterialTheme.typography.titleMedium)
                    if (active != null) {
                        Text("Začátek: ${active.started_at}\nFotografuj běžným fotoaparátem. Pak se vrať sem.")
                        val first = active.measurements.firstOrNull()
                        Text("První kontakt: ±${first?.horizontal_accuracy_m?.toInt() ?: "?"} m • poloha telefonu")
                        if (active.started_at != null && Duration.between(Instant.parse(active.started_at), Instant.now()).abs() > Duration.ofHours(2))
                            Text("Relace je otevřená přes 2 hodiny nebo nesouhlasí čas. Ověř, zda stále patří ke stejnému objektu.", color = MaterialTheme.colorScheme.error)
                    }
                    if (active == null) BigButton("PRVNÍ KONTAKT", !busy) { onCapture("start", type, relation, note) }
                    Choice("Typ", type, objectTypes) { type = it }
                    Choice("Poloha vůči objektu", relation, relations) { relation = it }
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Poznámka (nepovinná)") }, modifier = Modifier.fillMaxWidth())
                    if (active != null) {
                        BigButton("ZAMĚŘIT OBJEKT A UZAVŘÍT", !busy) { onCapture("finish", type, relation, note) }
                        OutlinedButton(onClick = { confirm = "incomplete" }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Uzavřít bez bezpečného zaměření") }
                        TextButton(onClick = { confirm = "cancel" }, enabled = !busy, modifier = Modifier.heightIn(min = 56.dp)) { Text("Zrušit relaci") }
                    } else OutlinedButton(onClick = { onCapture("standalone", type, relation, note) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Zaměřit samostatný bod") }
                    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = vm::stopMeasurement) { Text("Zastavit čekání") } }
                    Text(message)
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Synchronizace", style = MaterialTheme.typography.titleLarge)
                    Text("Čeká: ${queue.count { it.state == "pending" }} • Odesílá: ${queue.count { it.state == "sending" }}\nChyba: ${queue.count { it.state == "error" }} • Potvrzeno: ${queue.count { it.state == "confirmed" }} revizí")
                    queue.firstOrNull { it.error != null }?.let { Text(it.error!!, color = MaterialTheme.colorScheme.error) }
                    BigButton("SYNCHRONIZOVAT NYNÍ", !busy) { vm.message.value = "Přenos zařazen. Je potřeba připojený Drive a internet."; scheduleSync(vm.getApplication(), true) }
                    TextButton(onClick = { settings = true }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Připojit / nastavit Google Drive") }
                    Text("Na pozadí plánuje přenos Android. Okamžité odeslání není zaručené.", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = { history = !history }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Záznamy a záloha (${sessions.size})") }
            if (history) {
                BigButton("EXPORTOVAT ZÁLOHU JSON", !busy, onExport)
                OutlinedButton(onClick = onImport, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Importovat JSON 1.1 / 2.0") }
                sessions.forEach { s ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(statusLabel(s.session_status), color = MaterialTheme.colorScheme.primary)
                            Text("${objectTypes[s.object_type] ?: "Typ neurčen"} • revize ${s.revision}")
                            Text(s.started_at ?: s.created_at)
                            Text(s.session_id, style = MaterialTheme.typography.bodySmall)
                            val latest = queue.firstOrNull { it.sessionId == s.session_id && it.revision == s.revision }
                            Text(when(latest?.state) { "confirmed" -> "Odesláno a obsah potvrzen"; "sending" -> "Odesílá se"; "error" -> "Chyba, čeká na opakování"; else -> "Čeká na odeslání" })
                            s.measurements.lastOrNull()?.let { m -> Text("${m.measurement_role}: ${m.latitude}, ${m.longitude}\nPřesnost ±${m.horizontal_accuracy_m ?: "?"} m") }
                            if (s.note.isNotBlank()) Text(s.note)
                            if (s.warnings.isNotEmpty()) Text("K prověření: ${s.warnings.joinToString()}", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { editId = s.session_id; editType = s.object_type ?: "jednoduchy_bunkr"; editNote = s.note }, enabled = !busy, modifier = Modifier.heightIn(min = 56.dp)) { Text("Opravit typ / poznámku") }
                            TextButton(onClick = { onPhotos(s.session_id) }, enabled = !busy, modifier = Modifier.heightIn(min = 56.dp)) { Text("Potvrdit fotografie (${s.photo_associations.size})") }
                        }
                    }
                }
            }
            Text("WGS84 • Udávaná přesnost není záruka. Bod u vstupu není geometrický střed. Výška GPS není výška terénu z DEM.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirm != null) AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (confirm == "cancel") "Zrušit relaci?" else "Uzavřít bez druhého bodu?") },
        text = { Text("Původní měření zůstane uložené. Kvůli bodu není potřeba riskovat přiblížení k objektu.") },
        confirmButton = { TextButton(onClick = { if (confirm == "cancel") vm.cancel() else vm.closeWithoutSurvey(type, relation, note); confirm = null }) { Text("Potvrdit") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Zpět") } })
    if (editId != null) AlertDialog(onDismissRequest = { editId = null }, title = { Text("Oprava záznamu") }, text = {
        Column { Choice("Typ", editType, objectTypes) { editType = it }; OutlinedTextField(editNote, { editNote = it }, label = { Text("Poznámka") }) }
    }, confirmButton = { TextButton(onClick = { vm.edit(editId!!, editType, editNote); editId = null }) { Text("Uložit novou revizi") } }, dismissButton = { TextButton(onClick = { editId = null }) { Text("Zpět") } })
    if (settings) DriveSettings(onDismiss = { settings = false }, onConnect = { email, folder -> onConnect(email, folder); settings = false })
}
@Composable private fun DriveSettings(onDismiss: () -> Unit, onConnect: (String, String) -> Unit) {
    val c = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { c.getSharedPreferences("drive", Context.MODE_PRIVATE) }
    var email by rememberSaveable { mutableStateOf(prefs.getString("email", "")!!) }
    var folder by rememberSaveable { mutableStateOf(prefs.getString("folder", "")!!) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Google Drive") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nejdřív nastav Android OAuth klienta podle návodu. Použij původní importní složku; aplikace ověří účet i přístup. Žádné heslo ani Client Secret sem nepatří.")
            OutlinedTextField(email, { email = it }, label = { Text("Google účet (e-mail)") })
            OutlinedTextField(folder, { folder = it }, label = { Text("Odkaz / ID importní složky") })
            Text("Přístup drive.file může vyžadovat udělení přístupu ke složce přes Google Picker v témže Cloud projektu. Pouhé vložení ID ho neuděluje.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { prefs.edit().putBoolean("enabled", false).apply(); onDismiss() }) { Text("Pozastavit automatické odesílání") }
        }
    }, confirmButton = { TextButton(onClick = { onConnect(email, folder) }) { Text("Připojit a ověřit") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Zpět") } })
}
