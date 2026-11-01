package cz.fortgps.field

import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

class RealJpegTest {
    @Test fun privateSamsungOriginalPreservedAndIndependentlyParsed() {
        val path=System.getenv("FIELD_REAL_JPEG");assumeNotNull(path)
        val input=File(path!!);val original=input.readBytes();val s=XmpTest().session()
        val historical=FortXmp.read(original)
        if (historical.isNotEmpty()) {
            val backup=System.getenv("FIELD_ALPHA2_BACKUP");assumeNotNull(backup)
            val old=Legacy.read(File(backup!!).readText()).single {it.session_id==historical["SessionID"]}
            assertArrayEquals(original,FortXmp.prepare(original,old))
            try {FortXmp.prepare(original,s);fail("Historical packet overwritten")} catch(_:XmpConflict) {}
        }
        // Explicit derived test fixture: remove ONLY FortGPS properties on a memory copy.
        // The supplied file is never modified. This is not an unmarked original camera sample.
        val x=com.adobe.internal.xmp.XMPMetaFactory.parseFromBuffer(JpegGuard.inspect(original).xmp)
        historical.keys.forEach {x.deleteProperty(FortXmp.NS,it)}
        val fixture=java.io.ByteArrayOutputStream()
        org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter().updateXmpXml(original,fixture,com.adobe.internal.xmp.XMPMetaFactory.serializeToString(x,com.adobe.internal.xmp.options.SerializeOptions()))
        val before=fixture.toByteArray();JpegGuard.verify(original,before)
        val output=FortXmp.prepare(before,s)
        JpegGuard.verify(before,output)
        assertArrayEquals(output,FortXmp.prepare(output,s))
        assertEquals(FortXmp.values(s),FortXmp.read(output))
        assertTrue(org.apache.commons.imaging.Imaging.getXmpXml(output).contains(s.session_id))
        val root=File(input.parentFile,"../evidence").apply {mkdirs()}
        File(root,"alpha3-xmp.jpg").writeBytes(output)
        File(root,"evidence.json").writeText("""{"before_sha256":"${sha256(before)}","after_sha256":"${sha256(output)}","protected_sha256":"${JpegGuard.protectedHash(before)}","session_id":"${s.session_id}"}""")
    }
}
