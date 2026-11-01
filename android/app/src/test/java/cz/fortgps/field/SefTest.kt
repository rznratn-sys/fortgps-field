package cz.fortgps.field

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream

/** Synthetic structures only. These are NOT samples of Samsung Gallery compatibility. */
class SefTest {
    private fun le(n: Int)=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
    private fun jpeg():ByteArray=java.util.Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAADAAMDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD5/ooooA//2Q==")
    fun sef(base:ByteArray=jpeg(),name:String="Image_UTC_Data",version:Int=101):ByteArray {
        val tag=le(0x0a010000);val bytes=name.toByteArray(Charsets.US_ASCII)
        val data=tag+le(bytes.size)+bytes+"example-payload".toByteArray()
        return base+data+"SEFH".toByteArray()+le(version)+le(1)+tag+le(data.size)+le(data.size)+le(24)+"SEFT".toByteArray()
    }
    @Test fun supportedSyntheticTailPreservedWithoutRecompressionAndIdempotent() {
        val before=sef();val s=XmpTest().session();val after=FortXmp.prepare(before,s)
        JpegGuard.verify(before,after);assertArrayEquals(after,FortXmp.prepare(after,s))
        assertEquals(FortXmp.values(s),FortXmp.read(after))
        // Read back JPEG dimensions and XMP independently of our structural guard.
        assertEquals(3,org.apache.commons.imaging.Imaging.getImageInfo(after).width)
        val xml=org.apache.commons.imaging.Imaging.getXmpXml(after)
        assertTrue(xml.contains(s.session_id))
    }
    @Test fun fieldReportedUnknownNamesRemainRejected() {
        for(name in listOf("Camera_Scene_Info","Gallery_DC_Data","MotionPhoto_Data","unknown")) {
            val before=sef(name=name);val hash=sha256(before)
            try {FortXmp.prepare(before,XmpTest().session());fail("Unknown SEF accepted")} catch(e:IllegalArgumentException) {assertTrue(e.message!!.contains(name))}
            assertEquals(hash,sha256(before))
        }
    }
    @Test fun unknownVersionInvalidOffsetsTypeAndTrailerAreRefused() {
        val good=sef()
        val typeMismatch=good.copyOf().also {it[it.size-20]=(it[it.size-20].toInt() xor 1).toByte()}
        val offsetOverflow=good.copyOf().also {System.arraycopy(le(Int.MAX_VALUE),0,it,it.size-16,4)}
        for(bad in listOf(sef(version=102),typeMismatch,offsetOverflow,good.dropLast(1).toByteArray(),jpeg()+byteArrayOf(1,2,3))) {
            try {JpegGuard.inspect(bad);fail("Unsafe tail accepted")} catch(_:IllegalArgumentException) {} catch(_:IllegalStateException) {}
        }
    }
    @Test fun plainJpegConflictRefused() {
        val before=jpeg();val s=XmpTest().session();val after=FortXmp.prepare(before,s)
        JpegGuard.verify(before,after)
        try {FortXmp.prepare(after,s.copy(object_type="velky_bunkr"));fail()} catch(_:XmpConflict) {}
    }
    @Test fun foreignXmpPreservedAndMalformedRefused() {
        val before=jpeg()
        val x=com.adobe.internal.xmp.XMPMetaFactory.create()
        x.setProperty("http://ns.adobe.com/xap/1.0/","CreatorTool","Foreign tool")
        val foreign=ByteArrayOutputStream()
        org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter().updateXmpXml(before,foreign,com.adobe.internal.xmp.XMPMetaFactory.serializeToString(x,com.adobe.internal.xmp.options.SerializeOptions()))
        val result=FortXmp.prepare(foreign.toByteArray(),XmpTest().session())
        val parsed=com.adobe.internal.xmp.XMPMetaFactory.parseFromBuffer(JpegGuard.inspect(result).xmp)
        assertEquals("Foreign tool",parsed.getPropertyString("http://ns.adobe.com/xap/1.0/","CreatorTool"))
        val bad=ByteArrayOutputStream()
        org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter().updateXmpXml(before,bad,"<broken>")
        try {FortXmp.prepare(bad.toByteArray(),XmpTest().session());fail()} catch(_:com.adobe.internal.xmp.XMPException) {}
    }

    @Test fun historicalWriterVersionIsPreservedButUnknownVersionsAreConflicts() {
        val s=XmpTest().session();val new=FortXmp.prepare(jpeg(),s)
        val x=com.adobe.internal.xmp.XMPMetaFactory.parseFromBuffer(JpegGuard.inspect(new).xmp)
        x.setProperty(FortXmp.NS,"AppVersion","2.0.0-alpha2")
        fun rewrite():ByteArray {
            val o=ByteArrayOutputStream()
            org.apache.commons.imaging.formats.jpeg.xmp.JpegXmpRewriter().updateXmpXml(new,o,com.adobe.internal.xmp.XMPMetaFactory.serializeToString(x,com.adobe.internal.xmp.options.SerializeOptions()))
            return o.toByteArray()
        }
        val old=rewrite();assertArrayEquals(old,FortXmp.prepare(old,s));assertTrue(FortXmp.matches(FortXmp.read(old),s))
        x.setProperty(FortXmp.NS,"AppVersion","unknown")
        try {FortXmp.prepare(rewrite(),s);fail()} catch(_:XmpConflict) {}
    }

}
