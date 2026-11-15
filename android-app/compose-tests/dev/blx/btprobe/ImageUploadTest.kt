package dev.blx.btprobe

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ImageUploadTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture():File {
        val b=Bitmap.createBitmap(240,160,Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            for(x in 0..239) drawLine(x.toFloat(),0f,x.toFloat(),160f,Paint().apply{color=Color.rgb(x,100,230-x/2)})
            drawText("Nearby image",12f,80f,Paint().apply{color=Color.WHITE;textSize=24f;isAntiAlias=true})
        }
        val f=File(context.cacheDir,"image-upload-test.jpg")
        f.outputStream().use{b.compress(Bitmap.CompressFormat.JPEG,95,it)};b.recycle()
        ExifInterface(f).apply{
            setAttribute(ExifInterface.TAG_ORIENTATION,"6")
            setAttribute(ExifInterface.TAG_MAKE,"BTProbe private camera metadata")
            setAttribute(ExifInterface.TAG_DATETIME,"2026:01:02 03:04:05")
            setLatLong(37.0,-122.0);saveAttributes()
        }
        return f
    }
    @Test fun softwareCodecStripsMetadataAndSurvivesMeshPackets() {
        val f=fixture()
        try {
            val source=ChatImage.load(context,Uri.fromFile(f))
            assertEquals(160,source.width);assertEquals(240,source.height)
            val result=ChatImage.encode(source,128,80)
            assertEquals(85,result.width);assertEquals(128,result.height)
            assertTrue(ChatImage.isRawJxl(result.bytes))
            assertFalse(result.bytes.toString(Charsets.ISO_8859_1).contains("BTProbe private camera metadata"))
            assertTrue(result.bytes.size<=ChatProtocol.MAX_IMAGE)
            val inbox=ChatTransfers.Inbox()
            var received:ChatProtocol.Message?=null
            val packets=ChatProtocol.fragment2(1,99,ChatProtocol.envelope(ChatProtocol.JXL,"Test",result.bytes))
            for(raw in packets.reversed()) {inbox.receive(ChatProtocol.decode(raw),0)?.message?.let{received=it}}
            assertNotNull(received)
            val decoded=ChatImage.decode(received!!.content)!!
            assertEquals(result.width,decoded.width);assertEquals(result.height,decoded.height)
            assertEquals(Bitmap.Config.ARGB_8888,decoded.config)
            decoded.recycle()
            val small=ChatImage.encode(source,64,95)
            assertTrue(small.width<result.width&&small.height<result.height)
            val detail=ChatImage.encode(source,128,0)
            assertTrue("High compression reduces this fixture",result.bytes.size<detail.bytes.size)
            source.recycle()
            assertNull(ChatImage.decode(byteArrayOf(0xff.toByte(),0x0a,0)))
            assertNull(ChatImage.decode(ByteArray(ChatProtocol.MAX_IMAGE+1)))
            val wide=Bitmap.createBitmap(1025,1,Bitmap.Config.ARGB_8888)
            assertNull(ChatImage.decode(NativeJxl.encode(wide,30)));wide.recycle()
            val legacy=Bitmap.createBitmap(70,50,Bitmap.Config.ARGB_8888)
            val out=java.io.ByteArrayOutputStream();legacy.compress(Bitmap.CompressFormat.JPEG,70,out);legacy.recycle()
            val old=ChatImage.decode(out.toByteArray());assertNotNull(old);old?.recycle()
            println("IMAGE_CODEC_VERIFIED ${result.width}x${result.height} ${result.bytes.size} bytes ${packets.size} packets; stripped EXIF; CPU ARGB_8888; legacy JPEG valid")
        } finally {f.delete()}
    }
    @Test fun uploadSlidersUpdatePreviewWithoutSending() {
        val f=fixture()
        val prefs=context.getSharedPreferences("appearance",0)
        val original=prefs.getString("theme","system")
        val intent=Intent(context,ImageUploadActivity::class.java).setData(Uri.fromFile(f)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<ImageUploadActivity>(intent).use {
                compose.waitUntil(30000){compose.onAllNodesWithContentDescription("Compressed image preview").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Send image").assertIsEnabled().assertIsDisplayed()
                compose.onNodeWithContentDescription("Image longest edge in pixels").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress){it(128f)}
                compose.onNodeWithContentDescription("Image compression level").performScrollTo()
                    .performSemanticsAction(SemanticsActions.SetProgress){it(95f)}
                compose.waitUntil(30000){compose.onAllNodesWithText("85 × 128 pixels · JPEG XL").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("Send image").assertIsEnabled()
                for(theme in listOf("light","dark")) {
                    prefs.edit().putString("theme",theme).commit();compose.waitForIdle()
                    compose.onNodeWithContentDescription("Image longest edge in pixels").performScrollTo()
                    val scale=context.resources.configuration.fontScale
                    val folder=File(context.filesDir,"design-verification").apply{mkdirs()}
                    File(folder,"image-upload-$theme-$scale.png").outputStream().use{stream->compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,stream)}
                }
                compose.onNodeWithContentDescription("Cancel image upload").performClick()
            }
        } finally {prefs.edit().putString("theme",original).commit();f.delete()}
    }
}
