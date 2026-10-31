package cz.fortgps.field

import com.adobe.internal.xmp.XMPMetaFactory
import com.adobe.internal.xmp.options.SerializeOptions
import org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Read-only structural guard. Writing belongs exclusively to Apache Commons Imaging. */
object JpegGuard {
    val xmpHeader = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    data class Parts(val protected: List<ByteArray>, val xmp: ByteArray?)
    fun inspect(b: ByteArray): Parts {
        require(b.size >= 4 && b[0] == 0xff.toByte() && b[1] == 0xd8.toByte()) { "Neplatný JPEG" }
        val keep = mutableListOf<ByteArray>(); var xmp: ByteArray? = null; var p=2
        while (p+4 <= b.size) {
            require(b[p] == 0xff.toByte()) { "Poškozená struktura JPEG" }
            val marker=b[p+1].toInt() and 255
            if (marker == 0xda) {
                validateTail(b,p)
                keep.add(b.copyOfRange(p,b.size)); return Parts(keep,xmp)
            }
            require(marker in 0xc0..0xfe && marker !in 0xd0..0xd9)
            val len=((b[p+2].toInt() and 255) shl 8) or (b[p+3].toInt() and 255)
            require(len>=2 && p+2+len<=b.size) { "Neplatná délka JPEG segmentu" }
            val data=b.copyOfRange(p+4,p+2+len)
            val head=data.take(80).toByteArray().toString(Charsets.ISO_8859_1)
            require(!head.startsWith("MPF\u0000") && !head.startsWith("http://ns.adobe.com/xmp/extension/")) { "MPF / rozšířené XMP vyžaduje jiný zapisovač" }
            if (marker == 0xe1 && data.size>=xmpHeader.size && data.copyOfRange(0,xmpHeader.size).contentEquals(xmpHeader)) {
                require(xmp==null) { "Více XMP paketů" }; xmp=data.copyOfRange(xmpHeader.size,data.size)
            } else keep.add(b.copyOfRange(p,p+2+len))
            p+=2+len
        }
        error("JPEG nemá obrazový scan")
    }
    private fun validateTail(b: ByteArray,scan: Int) {
        var end=scan+2
        while(end+1<b.size && !(b[end]==0xff.toByte() && b[end+1]==0xd9.toByte())) end++
        require(end+1<b.size) { "Chybí JPEG EOI" }; end+=2
        if(end==b.size) return
        // Samsung SEF offsets point backwards from SEFH. Byte-identical tail survives APP1 insertion.
        fun le(p: Int): Int { require(p>=0 && p+4<=b.size); return java.nio.ByteBuffer.wrap(b,p,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int }
        require(b.copyOfRange(b.size-4,b.size).toString(Charsets.US_ASCII)=="SEFT") { "Nepodporovaný JPEG trailer" }
        val size=le(b.size-8); val h=b.size-8-size
        require(h>=end && b.copyOfRange(h,h+4).toString(Charsets.US_ASCII)=="SEFH") { "Neplatný Samsung SEF" }
        val count=le(h+8); require(count in 1..16 && size==12+12*count)
        val allowed=setOf("Image_UTC_Data","MCC_Data","Color_Display_P3","Camera_Capture_Mode_Info")
        val ranges=mutableListOf<IntRange>()
        repeat(count) { i ->
            val e=h+12+12*i; val start=h-le(e+4); val len=le(e+8)
            require(start>=end && len>=8 && start+len<=h)
            val nameLen=le(start+4); require(nameLen>0 && nameLen<=len-8)
            val name=b.copyOfRange(start+8,start+8+nameLen).toString(Charsets.US_ASCII)
            require(name in allowed) { "Nepodporovaný Samsung SEF záznam: $name" }
            ranges.add(start until start+len)
        }
        val sorted=ranges.sortedBy { it.first }
        require(sorted.first().first==end && sorted.last().last==h-1 && sorted.zipWithNext().all { (a,c)-> a.last+1==c.first })
    }
    fun verify(before: ByteArray, after: ByteArray) {
        val a=inspect(before).protected; val b=inspect(after).protected
        require(a.size==b.size && a.indices.all { a[it].contentEquals(b[it]) }) { "Změnila se obrazová data nebo původní metadata" }
    }
    fun protectedHash(bytes: ByteArray): String {
        val d=MessageDigest.getInstance("SHA-256"); inspect(bytes).protected.forEach(d::update)
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
class XmpConflict : Exception("Fotografie obsahuje odlišné FortGPS XMP. Automatické přepsání je zakázáno.")
object FortXmp {
    const val NS="https://github.com/rznratn-sys/fortgps-field/ns/fortgps/1.0/"
    init { XMPMetaFactory.getSchemaRegistry().registerNamespace(NS,"fortgps") }
    fun values(s: FieldSession): Map<String,String> {
        require(s.session_status=="completed" && s.session_mode=="photo")
        val m=s.measurements.single { it.measurement_role=="object_survey" }
        return linkedMapOf("SchemaVersion" to "1.0", "SessionID" to s.session_id,
            "SessionStartUTC" to requireNotNull(s.started_at), "SessionEndUTC" to requireNotNull(s.ended_at),
            "ObjectMeasurementID" to m.measurement_id, "ObjectLatitude" to m.latitude.toString(),
            "ObjectLongitude" to m.longitude.toString(), "ObjectPositionRelation" to requireNotNull(m.position_relation),
            "ObjectType" to requireNotNull(s.object_type), "VerificationStatus" to s.verification_status,
            "MeasurementSource" to s.source, "ObjectFixUTC" to m.measured_at, "AppVersion" to "2.0.0-alpha2").apply {
                m.horizontal_accuracy_m?.let { put("ObjectAccuracyM",it.toString()) }
                m.location_provider?.let { put("GPSProvider",it) }
                m.altitude_m?.let { put("ObjectAltitudeM",it.toString()); put("AltitudeReference",requireNotNull(m.altitude_reference)) }
            }
    }
    fun read(bytes: ByteArray): Map<String,String> {
        val xml=JpegGuard.inspect(bytes).xmp ?: return emptyMap()
        val x=XMPMetaFactory.parseFromBuffer(xml,com.adobe.internal.xmp.options.ParseOptions().setDisallowDoctype(true))
        return buildMap {
            val it=x.iterator(); while(it.hasNext()) {
                val p=it.next() as com.adobe.internal.xmp.properties.XMPPropertyInfo
                if(p.namespace==NS && p.path!=null) put(p.path.substringAfter(':'),p.value ?: "")
            }
        }
    }
    fun prepare(before: ByteArray, s: FieldSession): ByteArray {
        val parts=JpegGuard.inspect(before)
        val x=parts.xmp?.let { XMPMetaFactory.parseFromBuffer(it,com.adobe.internal.xmp.options.ParseOptions().setDisallowDoctype(true)) } ?: XMPMetaFactory.create()
        val expected=values(s); val found=read(before)
        if(found.isNotEmpty()) {
            if(expected.all { found[it.key]==it.value } && found.size==expected.size) return before
            throw XmpConflict()
        }
        // Absolute offset based multimedia metadata cannot safely survive insertion.
        val prior=parts.xmp?.toString(Charsets.UTF_8).orEmpty()
        require(!listOf("MotionPhoto","MicroVideo","Container:Directory","HasExtendedXMP").any(prior::contains)) { "Motion Photo / rozšířené XMP není podporováno" }
        expected.forEach { (k,v)-> x.setProperty(NS,k,v) }
        val xml=XMPMetaFactory.serializeToString(x,SerializeOptions().setUseCompactFormat(true))
        require(xml.toByteArray(Charsets.UTF_8).size < 60_000) { "XMP je příliš velké pro bezpečný APP1 paket" }
        val out=ByteArrayOutputStream(before.size+8192)
        JpegXmpRewriter().updateXmpXml(before,out,xml)
        return out.toByteArray().also { result ->
            JpegGuard.verify(before,result)
            require(read(result)==expected) { "XMP neprošlo kontrolou" }
        }
    }
}
