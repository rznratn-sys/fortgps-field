package cz.fortgps.field

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/** Durable backup + journal. MediaStore is NOT an atomic replace API. Never discard an unverified backup. */
class XmpService(private val c: Context) {
    private val db=FieldDb.get(c); private val media=MediaPhotos(c)
    private val dir=File(c.noBackupFilesDir,"xmp-recovery").apply { mkdirs() }
    private suspend fun session(id: String)=wire.decodeFromString<FieldSession>(requireNotNull(db.dao().get(id)).payload)
    suspend fun state(r: XmpRow) {
        db.withTransaction {
            db.dao().putXmp(r)
            val s=session(r.sessionId)
            val result=XmpResult(r.state,r.message,r.name,r.beforeHash,r.afterHash,r.protectedHash,r.selectionMethod)
            if(s.xmp_result!=result) Repository(db).change(s.session_id) {
                it.copy(revision=it.revision+1, updated_at=Instant.now().toString(), xmp_result=result).checked()
            }
        }
        scheduleSync(c,true)
    }
    suspend fun deny(id: String) = lock.withLock {
        val r=db.dao().xmp(id) ?: XmpRow(id)
        state(r.copy(state="permission_required",message=if(r.backupPath!=null) "Obnova originálu vyžaduje souhlas. Dočasná záloha zůstává zachována. Neodinstalovávej aplikaci." else "Souhlas s úpravou nebyl udělen. Fotografie se nezměnila."))
    }
    suspend fun restoreOriginal(id: String) = lock.withLock {
        val r=requireNotNull(db.dao().xmp(id));require(r.backupPath!=null)
        val bytes=File(r.backupPath).readBytes();require(sha256(bytes)==r.beforeHash)
        write(Uri.parse(requireNotNull(r.uri)),bytes)
        require(sha256(media.bytes(Uri.parse(r.uri)))==r.beforeHash)
        state(r.copy(state="failed",message="Originál na výslovnou žádost obnoven ze zálohy a ověřen. XMP lze opakovat.",backupPath=null));cleanup(id)
    }
    suspend fun select(id: String, uri: String, name: String) = lock.withLock {
        val r=db.dao().xmp(id) ?: XmpRow(id)
        require(r.backupPath==null && r.state!="verified") { "Relace má dokončený zápis nebo čeká na obnovu originálu" }
        val s=session(id); require(s.session_mode=="photo" && s.session_status=="completed")
        val t=media.time(media.bytes(Uri.parse(uri)),s)
        if(t!=null) {
            require(t.earliest>=Instant.parse(s.started_at) && t.latestExclusive.minusNanos(1)<=Instant.parse(s.ended_at)) { "Fotografie není celým časovým intervalem uvnitř relace. Zápis odmítnut." }
        }
        state(r.copy(uri=uri,name=name,state="pending",beforeHash=null,afterHash=null,protectedHash=null,selectionMethod="user_confirmed_original",message="Ručně potvrzený originál; čeká na bezpečný zápis."))
        XmpWorker.enqueue(c,id)
    }
    suspend fun process(id: String) = lock.withLock {
        var r=db.dao().xmp(id) ?: XmpRow(id)
        try {
            // Recovery always precedes new work. A restart never starts writing to a different photo.
            if(r.backupPath!=null) { recover(r); return@withLock }
            if(r.state=="verified") return@withLock
            val s=session(id)
            require(s.session_status=="completed" && s.session_mode=="photo") { "XMP je jen pro dokončenou fotografickou relaci." }
            require(db.dao().xmpAll().none { it.backupPath!=null }) { "Nejdřív obnov rozpracovaný originál v historii relací." }
            if(!media.fullReadPermission()) {
                state(r.copy(state="permission_required",message="GPS uloženo. Povol všechny fotografie a přístup k původní poloze médií; pak opakuj XMP.")); return@withLock
            }
            if(r.uri==null) {
                state(r.copy(state="searching",message="GPS uloženo. Hledám poslední snímek dle EXIF…"))
                val candidates=media.find(s)
                kotlinx.coroutines.delay(1500)
                val stable=media.find(s)
                require(candidates.toSet()==stable.toSet()) { "MediaStore se ještě mění. Fotografie nebyla upravena; opakuj zápis." }
                val decision=PhotoTime.choose(s,stable)
                if(decision.photo==null) { state(r.copy(state=decision.state,message="GPS uloženo. ${decision.reason}")); return@withLock }
                r=r.copy(uri=decision.photo.uri,name=decision.photo.name,selectionMethod="automatic_exif_interval")
                state(r)
            }
            val uri=Uri.parse(requireNotNull(r.uri))
            // Probe without truncation. Consent UI uses createWriteRequest / RecoverableSecurityException.
            try { requireNotNull(media.resolver.openFileDescriptor(uri,"rw")).close() }
            catch(e: SecurityException) {
                state(r.copy(state="permission_required",message="GPS uloženo. Potvrď systémový souhlas s úpravou ${r.name}.")); return@withLock
            }
            val before=media.bytes(uri)
            val bitmap=android.graphics.BitmapFactory.decodeByteArray(before,0,before.size,android.graphics.BitmapFactory.Options().apply { inSampleSize=8 })
            require(bitmap!=null) { "JPEG nelze dekódovat. Originál nebyl změněn." }; bitmap.recycle()
            val after=FortXmp.prepare(before,s)
            if(after.contentEquals(before)) {
                state(r.copy(state="verified",message="GPS uloženo. Odpovídající XMP už je ověřeno: ${r.name}",beforeHash=sha256(before),afterHash=sha256(before),protectedHash=JpegGuard.protectedHash(before))); return@withLock
            }
            requireSpace(dir.usableSpace,before.size.toLong(),after.size.toLong())
            // Temp lives outside cache, is fsynced before the journal and kept across process death.
            val backup=File(dir,"$id.original"); val prepared=File(dir,"$id.prepared")
            durable(backup,before); durable(prepared,after); syncDir()
            require(sha256(backup.readBytes())==sha256(before) && sha256(prepared.readBytes())==sha256(after))
            r=r.copy(state="writing",message="GPS uloženo. Probíhá chráněný zápis; originál je v dočasné záloze.",
                beforeHash=sha256(before),afterHash=sha256(after),protectedHash=JpegGuard.protectedHash(before),backupPath=backup.absolutePath)
            state(r) // Persisted BEFORE touching the original.
            // Detect a gallery/editor changing the file while the result was assembled.
            require(sha256(media.bytes(uri))==r.beforeHash) { "Fotografii mezitím změnila jiná aplikace" }
            write(uri,after)
            val actual=media.bytes(uri)
            require(sha256(actual)==r.afterHash); JpegGuard.verify(before,actual); require(FortXmp.read(actual)==FortXmp.values(s))
            state(r.copy(state="verified",message="GPS uloženo. XMP zapsáno: ${r.name}",backupPath=null))
            cleanup(id)
        } catch(e: Exception) {
            val current=db.dao().xmp(id) ?: r
            if(current.backupPath!=null) {
                try { recover(current) }
                catch(recovery: Exception) {
                    state(current.copy(state=if(recovery is SecurityException) "permission_required" else "failed",
                        message="Obnova originálu není dokončena: ${recovery.message}. Záloha zachována. Neodinstalovávej aplikaci; opakuj obnovu."))
                }
            } else { cleanup(id); state(current.copy(state=when(e) { is XmpConflict->"conflict"; is SecurityException->"permission_required"; else->"failed" },
                message="GPS uloženo. XMP nepotvrzeno: ${e.message}")) }
        }
    }
    private suspend fun recover(r: XmpRow) {
        val uri=Uri.parse(requireNotNull(r.uri)); val backup=File(requireNotNull(r.backupPath))
        val before=backup.readBytes(); require(sha256(before)==r.beforeHash) { "Dočasná záloha neprošla SHA-256" }
        val current=runCatching { media.bytes(uri) }.getOrNull()
        if(current!=null && sha256(current)==r.afterHash) {
            JpegGuard.verify(before,current)
            require(FortXmp.read(current)==FortXmp.values(session(r.sessionId)))
            state(r.copy(state="verified",message="XMP ověřeno po obnovení aplikace: ${r.name}",backupPath=null))
        } else {
            if(current==null || sha256(current)!=r.beforeHash) {
                // Never silently overwrite a valid externally edited JPEG during recovery.
                if(current!=null && runCatching { JpegGuard.inspect(current) }.isSuccess)
                    error("Fotografie má jiný platný obsah. Záloha ponechána pro ruční obnovu")
                write(uri,before)
            }
            require(sha256(media.bytes(uri))==r.beforeHash) { "Obnova originálu nebyla potvrzena" }
            state(r.copy(state="failed",message="GPS uloženo. Originál obnoven a ověřen; XMP nebylo potvrzeno. Lze opakovat.",backupPath=null))
        }
        cleanup(r.sessionId)
    }
    private fun write(uri: Uri, bytes: ByteArray) {
        requireNotNull(media.resolver.openFileDescriptor(uri,"rw")).use { pfd ->
            val fd=pfd.fileDescriptor
            require(android.system.Os.fstat(fd).let { android.system.OsConstants.S_ISREG(it.st_mode) }) { "Poskytovatel nenabízí běžný soubor" }
            val space=android.system.Os.fstatvfs(fd)
            require(space.f_bavail*space.f_frsize>=bytes.size+4L*1024*1024) { "Na úložišti fotografie není dostatek místa" }
            android.system.Os.lseek(fd,0,android.system.OsConstants.SEEK_SET)
            // No truncate until bytes have been written. Durable backup remains until independent readback.
            var p=0; while(p<bytes.size) { val n=android.system.Os.write(fd,bytes,p,bytes.size-p); require(n>0); p+=n }
            android.system.Os.ftruncate(fd,bytes.size.toLong()); android.system.Os.fsync(fd)
        }
    }
    private fun durable(f: File,b: ByteArray) { FileOutputStream(f).use { it.write(b); it.fd.sync() } }
    private fun syncDir() {
        val fd=android.system.Os.open(dir.absolutePath,android.system.OsConstants.O_RDONLY,0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
    private fun cleanup(id: String) { File(dir,"$id.original").delete(); File(dir,"$id.prepared").delete() }
    companion object {
        val lock=Mutex()
        fun requireSpace(available: Long,before: Long,after: Long) {
            require(available>=before+after+maxOf(16L*1024*1024,after-before)) { "Pro bezpečnou úpravu fotografie není dostatek volného místa." }
        }
    }
}
class XmpWorker(c: Context,p: WorkerParameters): CoroutineWorker(c,p) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id=inputData.getString("session") ?: return@withContext Result.failure()
        XmpService(applicationContext).process(id); Result.success()
    }
    companion object {
        fun enqueue(c: Context,id: String) {
            WorkManager.getInstance(c).enqueueUniqueWork("xmp-$id",ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<XmpWorker>().setInputData(workDataOf("session" to id)).build())
        }
        fun resume(c: Context) {
            // Room is durable; workers themselves are also persisted by WorkManager.
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { resumeRows(c) } }
        }
    }
}
private suspend fun resumeRows(c: Context) {
    val dao=FieldDb.get(c).dao()
    dao.all().forEach { row ->
        val s=wire.decodeFromString<FieldSession>(row.payload)
        if(s.session_mode=="photo" && s.session_status=="completed" && s.photo_timezone_start!=null && s.xmp_result==null && dao.xmp(s.session_id)==null)
            dao.putXmp(XmpRow(s.session_id))
    }
    dao.xmpAll().filter { it.state=="verified" && it.backupPath==null }.forEach {
        File(c.noBackupFilesDir,"xmp-recovery/${it.sessionId}.original").delete()
        File(c.noBackupFilesDir,"xmp-recovery/${it.sessionId}.prepared").delete()
    }
    dao.xmpAll().filter { it.backupPath!=null || it.state in setOf("pending","searching","writing") }.forEach { XmpWorker.enqueue(c,it.sessionId) }
}

