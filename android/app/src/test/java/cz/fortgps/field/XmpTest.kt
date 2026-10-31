package cz.fortgps.field
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.decodeFromString
import java.io.File
import java.time.Instant
class XmpTest {
    fun session(): FieldSession {
        val id=uuid();val a="2026-10-31T17:51:05.857Z";val b="2026-10-31T17:52:02.676Z"
        val m=FieldMeasurement(session_id=id,measurement_role="first_contact",measured_at=a,created_at=a,latitude=50.0001,longitude=16.0001,horizontal_accuracy_m=100.0)
        return FieldSession(session_id=id,started_at=a,ended_at=b,created_at=a,updated_at=b,session_status="completed",object_type="jednoduchy_bunkr",measurements=listOf(m,m.copy(measurement_id=uuid(),measurement_role="object_survey",measured_at=b,created_at=b,latitude=50.0002,longitude=16.0002,horizontal_accuracy_m=20.45800018310547,position_relation="safe_nearby")))
    }
    fun photo(n: String,t: String?,sub: String?="123")=TimedPhoto(n,n,PhotoTime.parse(t,"+01:00",sub,null))
    @Test fun latestInsideAndNotAfter() {
        val r=PhotoTime.choose(session(),listOf(photo("a","2026:10:31 18:51:30"),photo("last","2026:10:31 18:51:49"),photo("after","2026:10:31 18:52:18")))
        assertEquals("last",r.photo!!.name)
    }
    @Test fun timezoneAndDst() {
        assertEquals(Instant.parse("2026-10-31T17:51:49.123Z"),PhotoTime.parse("2026:10:31 18:51:49","+01:00","123",null)!!.earliest)
        assertEquals(Instant.parse("2026-10-31T17:51:49Z"),PhotoTime.parse("2026:10:31 18:51:49",null,null,"Europe/Prague")!!.earliest)
        assertNull(PhotoTime.parse("2026:10:25 02:30:00",null,null,"Europe/Prague"))
        assertNull(PhotoTime.parse("2026:03:29 02:30:00",null,null,"Europe/Prague"))
        assertNotNull(PhotoTime.parse("2026:10:25 02:30:00","+02:00",null,null))
        assertNull(PhotoTime.parse("2026:10:31 18:51:49",null,null,null))
        assertNull(PhotoTime.parse("2026:02:31 18:51:49","+01:00",null,null))
    }
    @Test fun tieBoundaryAndOutside() {
        assertEquals("ambiguous",PhotoTime.choose(session(),listOf(photo("a","2026:10:31 18:51:49",null),photo("b","2026:10:31 18:51:49",null))).state)
        assertEquals("ambiguous",PhotoTime.choose(session(),listOf(photo("boundary","2026:10:31 18:52:02",null))).state)
        assertEquals("no_photo",PhotoTime.choose(session(),listOf(photo("out","2026:10:31 18:52:03",null))).state)
    }
    @Test fun unknownEmptyInvalidAndCancelled() {
        assertEquals("ambiguous",PhotoTime.choose(session(),listOf(photo("unknown",null))).state)
        assertEquals("no_photo",PhotoTime.choose(session(),emptyList()).state)
        for(status in listOf("cancelled","incomplete","open")) assertEquals("ambiguous",PhotoTime.choose(session().copy(session_status=status),emptyList()).state)
        val s=session();assertFalse(PhotoTime.boundsSafe(s.copy(ended_at=s.started_at)))
        assertFalse(PhotoTime.boundsSafe(s.copy(measurements=s.measurements.map {it.copy(created_at="2026-10-31T18:00:00Z")})))
    }
    @Test fun clockEpochChangeDisablesAutomaticWrite() {
        val s=session();assertFalse(PhotoTime.boundsSafe(s.copy(photo_clock_epoch_start=1,photo_clock_epoch_end=2)))
    }
    @Test fun malformedJpegRefused() {
        for(bytes in listOf(byteArrayOf(),byteArrayOf(-1,-40,0,1),byteArrayOf(-1,-40,-1,-31,0,99))) {
            try {JpegGuard.inspect(bytes);fail("Malformed JPEG accepted")} catch(_: IllegalArgumentException) {} catch(_: IllegalStateException) {}
        }
    }
    @Test fun correctSurveyAndNoFakeAccuracy() {
        assertEquals("50.0002",FortXmp.values(session())["ObjectLatitude"])
        assertEquals("20.45800018310547",FortXmp.values(session())["ObjectAccuracyM"])
        val s=session();assertFalse(FortXmp.values(s.copy(measurements=s.measurements.map {it.copy(horizontal_accuracy_m=null)})).containsKey("ObjectAccuracyM"))
    }
    @Test fun lowSpaceRefused() {
        try { XmpService.requireSpace(100,1000,2000);fail() } catch(_: IllegalArgumentException) {}
        XmpService.requireSpace(32*1024*1024,1000,2000)
    }
    @Test fun actualPilotSevenPhotos() {
        val path=System.getenv("FIELD_PILOT_DIR");org.junit.Assume.assumeNotNull(path)
        val root=File(path!!);val s=wire.decodeFromString<FieldSession>(File(root,"session.json").readText())
        val files=File(root,"input").walkTopDown().filter {it.extension.lowercase()=="jpg"}.toList();assertEquals(7,files.size)
        val photos=files.map { f ->
            val ex=org.apache.commons.imaging.Imaging.getMetadata(f) as org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
            fun v(tag: org.apache.commons.imaging.formats.tiff.taginfos.TagInfo)=ex.findExifValueWithExactMatch(tag)?.stringValue
            val tags=org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants::class.java
            fun tag(n: String)=tags.getField(n).get(null) as org.apache.commons.imaging.formats.tiff.taginfos.TagInfo
            TimedPhoto(f.absolutePath,f.name,PhotoTime.parse(v(tag("EXIF_TAG_DATE_TIME_ORIGINAL")),ex.exif.allFields.firstOrNull {it.tag==0x9011}?.stringValue,v(tag("EXIF_TAG_SUB_SEC_TIME_ORIGINAL")),"Europe/Prague"))
        }
        // Explicit test timezone assumption for historical alpha1. Production must not invent it.
        assertEquals("ambiguous",PhotoTime.choose(s,photos.map {if(it.name!="20261031_185149.jpg") it.copy(time=null) else it}).state)
        val chosen=PhotoTime.choose(s,photos).photo!!;assertEquals("20261031_185149.jpg",chosen.name)
        val out=File(root,"output").apply {mkdirs()};files.forEach {it.copyTo(File(out,it.name),true)}
        val target=File(out,chosen.name);val before=target.readBytes();val after=FortXmp.prepare(before,s);target.writeBytes(after)
        assertFalse(before.contentEquals(after));JpegGuard.verify(before,target.readBytes());assertEquals(FortXmp.values(s),FortXmp.read(target.readBytes()))
        assertArrayEquals(after,FortXmp.prepare(after,s))
        try {FortXmp.prepare(after,s.copy(object_type="velky_bunkr"));fail()} catch(_: XmpConflict) {}
        files.filter {it.name!=chosen.name}.forEach {assertEquals(sha256(it.readBytes()),sha256(File(out,it.name).readBytes()))}
        val x=com.adobe.internal.xmp.XMPMetaFactory.create();x.setProperty("http://ns.adobe.com/xap/1.0/","CreatorTool","Foreign tool")
        val buffer=java.io.ByteArrayOutputStream();org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter().updateXmpXml(before,buffer,com.adobe.internal.xmp.XMPMetaFactory.serializeToString(x,com.adobe.internal.xmp.options.SerializeOptions()))
        val merged=FortXmp.prepare(buffer.toByteArray(),s)
        val reread=com.adobe.internal.xmp.XMPMetaFactory.parseFromBuffer(JpegGuard.inspect(merged).xmp)
        assertEquals("Foreign tool",reread.getPropertyString("http://ns.adobe.com/xap/1.0/","CreatorTool"))
        // Malformed foreign XMP must stop preparation before any original is touched.
        val invalid=java.io.ByteArrayOutputStream();org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter().updateXmpXml(before,invalid,"<broken>")
        try {FortXmp.prepare(invalid.toByteArray(),s);fail("Malformed XMP accepted")} catch(_: com.adobe.internal.xmp.XMPException) {}
        // Simulate a changed protected byte: preservation guard must fail.
        val changed=after.copyOf();changed[changed.size-80]=(changed[changed.size-80].toInt() xor 1).toByte()
        try {JpegGuard.verify(before,changed);fail("Changed protected bytes accepted")} catch(_: IllegalArgumentException) {}
        File(root,"kotlin-evidence.json").writeText("""{"selected":"${chosen.name}","before_sha256":"${sha256(before)}","after_sha256":"${sha256(after)}","protected_jpeg_sha256":"${JpegGuard.protectedHash(before)}","timezone_assumption":"Europe/Prague","unchanged_other_files":6}""")
    }
}
