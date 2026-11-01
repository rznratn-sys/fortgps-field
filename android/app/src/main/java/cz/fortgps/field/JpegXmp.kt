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
        // Walk entropy escapes, restart markers and length-delimited headers of later scans.
        // Searching for the first FF D9 would mistake bytes inside a later header for EOI.
        var p=scan; var end=-1
        while(p < b.size) {
            require(b[p] == 0xff.toByte()) { "Neplatný marker ve scanu JPEG" }
            while(p < b.size && b[p] == 0xff.toByte()) p++
            require(p < b.size)
            val marker=b[p++].toInt() and 255
            if(marker == 0xd9) { end=p; break }
            require(marker !in 0xd0..0xd8 && marker != 0 && marker != 1) { "Nepodporovaný JPEG scan" }
            require(p+2 <= b.size)
            val size=((b[p].toInt() and 255) shl 8) or (b[p+1].toInt() and 255)
            require(size >= 2 && size <= b.size-p)
            p+=size
            if(marker == 0xda) {
                while(p < b.size) {
                    if(b[p] != 0xff.toByte()) { p++; continue }
                    val begin=p
                    while(p < b.size && b[p] == 0xff.toByte()) p++
                    require(p < b.size)
                    val next=b[p].toInt() and 255
                    if(next == 0 || next in 0xd0..0xd7) { p++; continue }
                    p=begin; break
                }
            }
        }
        require(end >= 0) { "Chybí JPEG EOI" }
        if(end==b.size) return
        // Samsung SEF offsets point backwards from SEFH. Byte-identical tail survives APP1 insertion.
        fun le(p: Int): Int { require(p>=0 && p+4<=b.size); return java.nio.ByteBuffer.wrap(b,p,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int }
        require(b.copyOfRange(b.size-4,b.size).toString(Charsets.US_ASCII)=="SEFT") { "Nepodporovaný JPEG trailer" }
        val size=le(b.size-8); val h=b.size-8-size
        require(h>=end && b.copyOfRange(h,h+4).toString(Charsets.US_ASCII)=="SEFH") { "Neplatný Samsung SEF" }
        require(le(h+4) in setOf(101,107)) { "Nepodporovaná verze Samsung SEF" }
        val count=le(h+8); require(count in 1..16 && size==12+12*count)
        val allowed=mapOf("Image_UTC_Data" to 0x0a01, "MCC_Data" to 0x0aa1, "Color_Display_P3" to 0x0cc1, "Camera_Capture_Mode_Info" to 0x0c61)
        val ranges=mutableListOf<IntRange>()
        repeat(count) { i ->
            val e=h+12+12*i; val start=h-le(e+4); val len=le(e+8)
            require(start>=end && len>=8 && len<=h-start)
            require(b.copyOfRange(e,e+4).contentEquals(b.copyOfRange(start,start+4))) { "Neshoda typu SEF záznamu" }
            val nameLen=le(start+4); require(nameLen>0 && nameLen<=len-8)
            val name=b.copyOfRange(start+8,start+8+nameLen).toString(Charsets.US_ASCII)
            require(name in allowed) { "Samsung SEF $name: bezpečný přepis včetně vnitřních odkazů není ověřen. Původní JPEG zůstává zachován." }
            val type=((b[e+3].toInt() and 255) shl 8) or (b[e+2].toInt() and 255)
            require(allowed[name] == type) { "Nepodporovaná kombinace názvu a typu Samsung SEF" }
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
            "MeasurementSource" to s.source, "ObjectFixUTC" to m.measured_at, "AppVersion" to "2.0.0-alpha3").apply {
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
    /** Preserve the actual writer version on matching historical alpha2 packets. */
    fun matches(found: Map<String,String>, s: FieldSession): Boolean {
        val expected=values(s)
        return found.size == expected.size && found["AppVersion"] in setOf("2.0.0-alpha2", "2.0.0-alpha3") &&
            expected.all { (k,v) -> k == "AppVersion" || found[k] == v }
    }
    fun prepare(before: ByteArray, s: FieldSession): ByteArray {
        val parts=JpegGuard.inspect(before)
        val x=parts.xmp?.let { XMPMetaFactory.parseFromBuffer(it,com.adobe.internal.xmp.options.ParseOptions().setDisallowDoctype(true)) } ?: XMPMetaFactory.create()
        val expected=values(s); val found=read(before)
        if(found.isNotEmpty()) {
            if(matches(found,s)) return before
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
