package dev.blx.btprobe

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

class ImageUploadActivity : ProbeActivity() {
    companion object {
        const val IMAGE_BYTES="encodedImage"
        // Native encode cannot be interrupted midway. Serialize it, dropping canceled requests
        // before they start; a slider drag never launches overlapping CPU encoders.
        private val encoder=Mutex()
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {ProbeTheme {UploadPage(intent.data)}}
    }
    @Composable private fun UploadPage(uri:Uri?) {
        var edge by rememberSaveable {mutableIntStateOf(ChatImage.DEFAULT_EDGE)}
        var compression by rememberSaveable {mutableIntStateOf(ChatImage.DEFAULT_COMPRESSION)}
        var loadError by remember {mutableStateOf<String?>(null)}
        var encodeError by remember {mutableStateOf<String?>(null)}
        val source by produceState<Bitmap?>(null,uri) {
            try {
                requireNotNull(uri){"Choose an image from Chat first."}
                value=withContext(Dispatchers.IO){ChatImage.load(this@ImageUploadActivity,uri)}
            } catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){loadError=e.message ?: "Could not open this image."}
            catch(_:OutOfMemoryError){loadError="Not enough memory to open this image."}
            catch(_:LinkageError){loadError="Image codec unavailable on this device."}
        }
        val encoded by produceState<ChatImage.Encoded?>(null,source,edge,compression) {
            value=null;encodeError=null
            val pixels=source ?: return@produceState
            delay(350)
            try {
                value=encoder.withLock {
                    withContext(Dispatchers.Default) {ensureActive();ChatImage.encode(pixels,edge,compression)}
                }
            } catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){encodeError=e.message ?: "Could not compress this image."}
            catch(_:LinkageError){encodeError="Image codec unavailable on this device."}
            catch(_:OutOfMemoryError){encodeError="Not enough memory. Choose a smaller image size."}
        }
        val result=encoded?.takeIf{it.compression==compression && it.edge==edge}
        var previewError by remember {mutableStateOf<String?>(null)}
        val verified by produceState<Pair<ChatImage.Encoded,Bitmap>?>(null,result) {
            value=null;previewError=null
            result?.takeIf{it.bytes.size<=ChatProtocol.MAX_IMAGE}?.let { image ->
                val decoded=withContext(Dispatchers.Default){ChatImage.decode(image.bytes)}
                if(decoded==null)previewError="Could not verify this compressed image. Try a smaller size."
                else value=image to decoded
            }
        }
        val preview=verified?.takeIf{it.first===result}?.second
        val tooLarge=(result?.bytes?.size ?: 0)>ChatProtocol.MAX_IMAGE
        val ready=result!=null&&!tooLarge&&preview!=null
        val colors=MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={finish()}) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"Cancel image upload")}
                Text("Share an image",style=MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=22.dp,vertical=12.dp),
                verticalArrangement=Arrangement.spacedBy(20.dp)) {
                val display=preview ?: source
                if(display!=null) {
                    Image(display.asImageBitmap(),if(preview!=null)"Compressed image preview" else "Selected image preview",
                        Modifier.fillMaxWidth().heightIn(min=140.dp,max=280.dp).clip(RoundedCornerShape(24.dp)))
                }
                Panel {
                    Text("Image size",style=MaterialTheme.typography.titleMedium)
                    Text("$edge px · longest edge",style=MaterialTheme.typography.bodyLarge)
                    Slider(value=edge.toFloat(),onValueChange={edge=((it/64).roundToInt()*64).coerceIn(64,1024)},
                        valueRange=64f..1024f,steps=14,modifier=Modifier.semantics{contentDescription="Image longest edge in pixels"})
                    Supporting("Aspect ratio is preserved. Small originals are never enlarged.")
                }
                Panel {
                    Text("Compression",style=MaterialTheme.typography.titleMedium)
                    Text("$compression% · ${if(compression>=70) "High" else if(compression>=35) "Balanced" else "More detail"}",style=MaterialTheme.typography.bodyLarge)
                    Slider(value=compression.toFloat(),onValueChange={compression=it.roundToInt()},valueRange=0f..100f,steps=19,
                        modifier=Modifier.semantics{contentDescription="Image compression level"})
                    Supporting("Higher compression favors a smaller file over fine detail. Encoder effort stays high.")
                }
                Panel {
                    Text("Estimated upload size",style=MaterialTheme.typography.titleMedium)
                    if(result!=null) {
                        Text(String.format(Locale.US,"%.1f KiB · %,d bytes",result.bytes.size/1024f,result.bytes.size),style=MaterialTheme.typography.headlineMedium)
                        Supporting("${result.width} × ${result.height} pixels · JPEG XL")
                        val packets=(result.bytes.size+22+ChatProtocol.CHUNK2-1)/ChatProtocol.CHUNK2
                        Supporting(if(MeshService.settings(this@ImageUploadActivity).getBoolean("image_advertising",true))
                            "Connected transfer to compatible nearby phones. Broadcast fallback: up to $packets data packets, plus retries and acknowledgements."
                            else "Connected-only image delivery · advertising fallback is off.")
                    } else if(loadError==null&&encodeError==null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Supporting(if(source==null)"Opening image…" else "Compressing preview…")
                    }
                    if(tooLarge) Text("Above the 16 KiB Bluetooth limit. Lower the image size or increase compression.",color=colors.error)
                    (loadError ?: encodeError ?: previewError)?.let {Text(it,color=colors.error)}
                }
                Supporting("Location, camera details, timestamps, embedded thumbnails and source profiles are removed. Colors are converted to sRGB; transparent areas become white. Essential decoding headers remain.")
                Supporting("All receiving phones need the updated app for JPEG XL. Images are public to nearby chat devices.")
            }
            Surface(color=colors.surface,tonalElevation=2.dp) {
                Box(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=12.dp)) {
                    PremiumButton("Send image",enabled=ready,onClick={
                        result?.takeIf{ready}?.let {
                            setResult(Activity.RESULT_OK,Intent().putExtra(IMAGE_BYTES,it.bytes));finish()
                        }
                    })
                }
            }
        }
    }
}
