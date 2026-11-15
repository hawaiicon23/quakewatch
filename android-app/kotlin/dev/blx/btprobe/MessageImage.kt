package dev.blx.btprobe

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable internal fun MessageImage(content:ByteArray,row:Long,description:String){
    val context=LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null,row){value=withContext(Dispatchers.Default){ChatImage.decode(content)}}
    val image=bitmap?:return
    var expanded by remember(row){mutableStateOf(false)}
    var menu by remember(row){mutableStateOf(false)}
    var saving by remember{mutableStateOf(false)}
    var result by remember{mutableStateOf("")}
    var exportBytes by remember{mutableStateOf<ByteArray?>(null)}
    val scope=rememberCoroutineScope()
    val destination=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")){uri->
        val bytes=exportBytes;exportBytes=null
        if(uri!=null&&bytes!=null)scope.launch{result=withContext(Dispatchers.IO){runCatching{context.contentResolver.openOutputStream(uri)?.use{it.write(bytes)}?:error("Cannot open destination");"Image saved"}.getOrElse{"Save failed: ${it.message}"}}}
    }
    fun save(){if(saving)return;saving=true;menu=false
        scope.launch{
            val encoded=withContext(Dispatchers.Default){runCatching{ByteArrayOutputStream().use{out->check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out));out.toByteArray()}}}
            val bytes=encoded.getOrNull()
            if(bytes==null){result="Could not encode image";saving=false;return@launch}
            val name="BTProbe-${System.currentTimeMillis()}.png"
            if(Build.VERSION.SDK_INT>=29){result=withContext(Dispatchers.IO){
                val resolver=context.contentResolver;var uri:android.net.Uri?=null
                try{val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,name);put(MediaStore.MediaColumns.MIME_TYPE,"image/png");put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/BTProbe");put(MediaStore.MediaColumns.IS_PENDING,1)}
                    uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("Cannot create download")
                    resolver.openOutputStream(uri)?.use{it.write(bytes)}?:error("Cannot write download")
                    check(resolver.update(uri,ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)>0)
                    "Saved to Downloads/BTProbe"
                }catch(e:Exception){uri?.let{runCatching{resolver.delete(it,null,null)}};"Save failed: ${e.message}"}
            }}else{exportBytes=bytes;destination.launch(name)}
            saving=false
        }
    }
    Column{
        Image(image.asImageBitmap(),description,Modifier.width(180.dp).heightIn(max=240.dp).aspectRatio(image.width.toFloat()/image.height)
            .clip(RoundedCornerShape(12.dp)).combinedClickable(onClick={expanded=true},onLongClick={menu=true},onLongClickLabel="Save image to Downloads"))
        if(result.isNotEmpty())Text(result,style=MaterialTheme.typography.labelSmall)
    }
    if(menu)AlertDialog(onDismissRequest={menu=false},title={Text("Save image")},text={Text("Save a PNG copy to Downloads. The received image stays in your conversation.")},
        confirmButton={TextButton(onClick={save()},enabled=!saving){Text("Save to Downloads")}},dismissButton={TextButton(onClick={menu=false}){Text("Cancel")}})
    if(expanded)Dialog(onDismissRequest={expanded=false},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        var zoom by remember{mutableFloatStateOf(1f)};var offset by remember{mutableStateOf(Offset.Zero)}
        Column(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={expanded=false}){Text("Close",color=Color.White)};TextButton(onClick={save()},enabled=!saving){Text(if(saving)"Saving…" else "Save to Downloads",color=Color.White)}}
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp))
                .pointerInput(row){detectTransformGestures{_,pan,scale,_->
                    zoom=(zoom*scale).coerceIn(1f,6f)
                    val fit=minOf(size.width.toFloat()/image.width,size.height.toFloat()/image.height)
                    val x=((image.width*fit*zoom-size.width)/2).coerceAtLeast(0f);val y=((image.height*fit*zoom-size.height)/2).coerceAtLeast(0f)
                    offset=Offset((offset.x+pan.x).coerceIn(-x,x),(offset.y+pan.y).coerceIn(-y,y))
                }}.pointerInput(row){detectTapGestures(onDoubleTap={zoom=if(zoom>1f)1f else 2.5f;offset=Offset.Zero},onLongPress={menu=true})},contentAlignment=Alignment.Center){
                Image(image.asImageBitmap(),description,Modifier.fillMaxSize().graphicsLayer{scaleX=zoom;scaleY=zoom;translationX=offset.x;translationY=offset.y},contentScale=ContentScale.Fit)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){
                TextButton(onClick={zoom=(zoom/1.5f).coerceAtLeast(1f);offset=Offset.Zero}){Text("Zoom out",color=Color.White)}
                TextButton(onClick={zoom=1f;offset=Offset.Zero}){Text("Reset",color=Color.White)}
                TextButton(onClick={zoom=(zoom*1.5f).coerceAtMost(6f)}){Text("Zoom in",color=Color.White)}
            }
            if(result.isNotEmpty())Text(result,Modifier.padding(16.dp),color=Color.White)
        }
    }
}
