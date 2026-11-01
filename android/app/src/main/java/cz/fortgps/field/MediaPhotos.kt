package cz.fortgps.field

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

class MediaPhotos(private val c: Context) {
    val resolver=c.contentResolver
    fun fullReadPermission(): Boolean = c.checkSelfPermission(if(Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT<29 || c.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)==PackageManager.PERMISSION_GRANTED)
    fun original(uri: Uri): Uri {
        require(uri.scheme=="content" && uri.authority==MediaStore.AUTHORITY) { "Vyber místní originál z MediaStore, nikoli cloudovou kopii." }
        return if(Build.VERSION.SDK_INT>=29) MediaStore.setRequireOriginal(uri) else uri
    }
    fun bytes(uri: Uri): ByteArray = requireNotNull(resolver.openInputStream(original(uri))).use {
        val out=java.io.ByteArrayOutputStream(); val b=ByteArray(65536)
        while(true) { val n=it.read(b); if(n<0) break; require(out.size().toLong()+n<=MAX_BYTES) { "JPEG větší než 16 MiB není podporován" }; out.write(b,0,n) }
        out.toByteArray()
    }
    fun time(bytes: ByteArray, s: FieldSession): CaptureTime? {
        val ex=ExifInterface(ByteArrayInputStream(bytes))
        val zone=s.photo_timezone_start?.takeIf { it==s.photo_timezone_end }
        return PhotoTime.parse(ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
            ex.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),ex.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),zone)
    }
    fun find(s: FieldSession): List<TimedPhoto> {
        require(fullReadPermission()) { "Pro automatické hledání povol všechny fotografie a původní metadata polohy. Omezený výběr nestačí." }
        val base=if(Build.VERSION.SDK_INT>=29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val path=if(Build.VERSION.SDK_INT>=29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
        // DATE_TAKEN is only a broad candidate index. Every selected time is independently read from EXIF.
        val pathWhere=if(Build.VERSION.SDK_INT>=29) "$path = ?" else "$path LIKE ?"
        val where="($pathWhere) AND (${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ? OR ${MediaStore.Images.Media.DATE_TAKEN} IS NULL OR ${MediaStore.Images.Media.DATE_TAKEN}=0)"
        val arg=if(Build.VERSION.SDK_INT>=29) "DCIM/Camera/" else "%/DCIM/Camera/%"
        return buildList {
            resolver.query(base,arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.MIME_TYPE),where,arrayOf(arg,(java.time.Instant.parse(s.started_at).toEpochMilli()-26*3600_000L).toString(),(java.time.Instant.parse(s.ended_at).toEpochMilli()+26*3600_000L).toString()),null)?.use { cursor ->
                require(cursor.count<=2000) { "V Camera je přes 2000 položek. Automatické hledání zastaveno; vyber snímek ručně." }
                while(cursor.moveToNext()) {
                    val uri=ContentUris.withAppendedId(base,cursor.getLong(0)); val name=cursor.getString(1)
                    // Non-JPEG camera photos may be the last shot too. They are never silently ignored.
                    val time=runCatching {
                        resolver.openInputStream(original(uri))!!.use { input ->
                            val ex=ExifInterface(input)
                            PhotoTime.parse(ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), ex.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
                                ex.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),s.photo_timezone_start?.takeIf { it==s.photo_timezone_end })
                        }
                    }.getOrNull()
                    add(TimedPhoto(uri.toString(),name,time))
                }
            } ?: error("MediaStore nevrátil seznam fotografií")
        }
    }
    companion object { const val MAX_BYTES=16L*1024*1024 }
}
