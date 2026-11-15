package dev.blx.btprobe
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class MessageImageTest {
    @get:Rule val compose=createComposeRule()
    @Test fun imageOpensZoomsAndSavesToDownloads(){
        assumeTrue(Build.VERSION.SDK_INT>=29)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val resolver=context.contentResolver
        fun downloads():Set<Long>{val ids=mutableSetOf<Long>();resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,arrayOf("_id"),"_display_name LIKE ?",arrayOf("BTProbe-%.png"),null)?.use{while(it.moveToNext())ids.add(it.getLong(0))};return ids}
        val before=downloads()
        val bitmap=Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
        val encoded=ChatImage.encode(bitmap,80,80);bitmap.recycle()
        compose.setContent{ProbeTheme{MessageImage(encoded.bytes,1,"Viewer test image")}}
        compose.waitUntil(10000){compose.onAllNodesWithContentDescription("Viewer test image").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Viewer test image").performTouchInput{longClick()}
        compose.onNodeWithText("Save image").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Viewer test image").performClick()
        compose.onNodeWithText("Zoom in").performClick();compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Save to Downloads").performClick()
        try{
            compose.waitUntil(15000){downloads().minus(before).isNotEmpty()}
            val saved=downloads().minus(before)
            assertEquals(1,saved.size)
            resolver.openInputStream(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,saved.single())).use{stream->
                val decoded=android.graphics.BitmapFactory.decodeStream(stream);assertNotNull(decoded);assertEquals(80,decoded.width);assertEquals(60,decoded.height);decoded.recycle()
            }
        }finally{downloads().minus(before).forEach{resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI,it),null,null)}}
        compose.onNodeWithText("Close").performClick()
    }
}
