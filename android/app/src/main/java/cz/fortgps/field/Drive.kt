package cz.fortgps.field

import android.accounts.Account
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.*
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
class DriveFailure(val code: Int, message: String) : Exception(message)
fun authRequest(email: String): AuthorizationRequest = AuthorizationRequest.builder()
    .setAccount(Account(email, "com.google"))
    .setRequestedScopes(listOf(Scope(DRIVE_SCOPE))).build()

class DriveClient(private val token: String,
    private val http: OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(false).connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build(),
    private val api: String = "https://www.googleapis.com/drive/v3/",
    private val upload: String = "https://www.googleapis.com/upload/drive/v3/") {
    private fun request(url: String, method: String = "GET", body: RequestBody? = null): ByteArray {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").method(method, body).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw DriveFailure(r.code, when (r.code) {
                401 -> "Přihlášení vypršelo. Připoj Drive znovu."
                403, 404 -> "Drive odmítl přístup. Ověř účet, drive.file a přístup k původní složce."
                409 -> "Soubor s tímto Drive ID již existuje; proběhne kontrola obsahu."
                else -> "Chyba Drive HTTP ${r.code}"
            })
            return requireNotNull(r.body).bytes()
        }
    }
    private fun json(url: String): JSONObject = JSONObject(String(request(url), Charsets.UTF_8))
    fun validateDestination(folder: String, expectedEmail: String) {
        require(folder.matches(Regex("[A-Za-z0-9_-]{10,}"))) { "Neplatné ID složky" }
        val who = json(api + "about?fields=user(emailAddress)").getJSONObject("user").getString("emailAddress")
        require(who.equals(expectedEmail, ignoreCase = true)) { "Přihlášen jiný Google účet" }
        val f = json(api + "files/$folder?fields=id,mimeType,trashed,capabilities(canAddChildren)")
        require(f.getString("mimeType") == "application/vnd.google-apps.folder" && !f.getBoolean("trashed") && f.getJSONObject("capabilities").optBoolean("canAddChildren")) { "Cíl není zapisovatelná složka" }
    }
    fun allocateId(): String = json(api + "files/generateIds?count=1&space=drive&type=files").getJSONArray("ids").getString(0)
    fun findRevision(folder: String, s: OutboxRow): String? {
        val name = "fortgps-field-${s.sessionId}-r${s.revision}.json"
        // Build on the supplied base also in local HTTP tests.
        val query = "'$folder' in parents and name = '$name' and trashed = false"
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val files = json(api + "files?q=$encoded&fields=files(id),nextPageToken&pageSize=2").getJSONArray("files")
        require(files.length() <= 1) { "Více vzdálených souborů stejné relace a revize. Vyžaduje kontrolu." }
        return if (files.length() == 1) files.getJSONObject(0).getString("id") else null
    }
    fun exists(id: String): Boolean = try { json(api + "files/$id?fields=id"); true } catch (e: DriveFailure) { if (e.code == 404) false else throw e }
    fun verify(id: String, folder: String, payload: String) {
        val m = json(api + "files/$id?fields=id,parents,trashed,mimeType")
        require(!m.getBoolean("trashed") && m.getString("mimeType") == "application/json") { "Konflikt vzdáleného souboru" }
        val parents = m.getJSONArray("parents")
        require((0 until parents.length()).any { parents.getString(it) == folder }) { "Nesouhlasí cílová složka" }
        val actual = request(api + "files/$id?alt=media")
        require(actual.contentEquals(payload.toByteArray(Charsets.UTF_8))) { "Nesouhlasí obsah JSON. Originál se nepřepisuje." }
    }
    fun putImmutable(id: String, folder: String, s: OutboxRow) {
        if (!exists(id)) {
            val boundary = "field_${uuid()}"
            val metadata = JSONObject().put("id", id).put("name", "fortgps-field-${s.sessionId}-r${s.revision}.json")
                .put("parents", JSONArray().put(folder)).put("mimeType", "application/json")
                .put("appProperties", JSONObject().put("field_session_id", s.sessionId).put("field_revision", s.revision.toString()))
            val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${s.payload}\r\n--$boundary--\r\n"
            try {
                request(upload + "files?uploadType=multipart&fields=id", "POST", body.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            } catch (e: DriveFailure) { if (e.code != 409) throw e }
        }
        verify(id, folder, s.payload)
    }
}
class SyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    companion object { private val lock = Mutex() }
    override suspend fun doWork(): Result = lock.withLock {
        withContext(Dispatchers.IO) {
            val dao = FieldDb.get(applicationContext).dao()
            val prefs = applicationContext.getSharedPreferences("drive", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("enabled", false) || dao.pending().isEmpty()) return@withContext Result.success()
            var token: String? = null
            try {
                val email = prefs.getString("email", "")!!
                val result = Identity.getAuthorizationClient(applicationContext).authorize(authRequest(email)).await()
                require(!result.hasResolution()) { "Otevři aplikaci a připoj Drive znovu" }
                token = requireNotNull(result.accessToken) { "Google nevrátil přístupový token" }
                val client = DriveClient(token)
                val folder = prefs.getString("folder", "")!!
                client.validateDestination(folder, email)
                for (old in dao.pending()) {
                    ensureActive()
                    var item = dao.item(old.key)
                    // Persist the destination and generated Drive ID BEFORE any upload.
                    if (item.remoteId == null) {
                        item = item.copy(remoteId = client.findRevision(folder, item) ?: client.allocateId(), folderId = folder)
                        dao.update(item)
                    }
                    require(item.folderId == folder) { "Rozpracovaný přenos patří do jiné složky. Vrať původní nastavení." }
                    item = item.copy(state = "sending", error = null); dao.update(item)
                    client.putImmutable(requireNotNull(item.remoteId), folder, item)
                    dao.update(item.copy(state = "confirmed", error = null, confirmedAt = Instant.now().toString()))
                }
                Result.success()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // Do not log token, payload, coordinates, account, or remote response body.
                val message = (e.message ?: "Přenos selhal").take(240)
                dao.pending().forEach { dao.update(it.copy(state = "error", error = message)) }
                if (e is DriveFailure && e.code == 401 && token != null) {
                    try { Identity.getAuthorizationClient(applicationContext).clearToken(ClearTokenRequest.builder().setToken(token).build()).await() } catch (_: Exception) { }
                }
                if (e is java.io.IOException || (e is DriveFailure && (e.code == 429 || e.code >= 500))) Result.retry() else Result.success()
            }
        }
    }
}
