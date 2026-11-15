package dev.blx.btprobe

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ChatActivity : ProbeActivity() {
    private var imageProgress by mutableStateOf("")
    private var conversationOpen by mutableIntStateOf(0)
    private val upload=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if(result.resultCode==RESULT_OK) {
            result.data?.getByteArrayExtra(ImageUploadActivity.IMAGE_BYTES)?.let { bytes ->
                imageProgress=if(bytes.size<=ChatProtocol.MAX_IMAGE) enqueue(ChatProtocol.JXL,bytes) ?: "" else "Image exceeds transfer limit."
            }
        }
    }
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) upload.launch(Intent(this,ImageUploadActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    override fun onCreate(saved:Bundle?) {
        super.onCreate(saved)
        setContent { ProbeTheme { ChatPage() } }
        if(MeshService.get()?.running!=true) Permissions.startMesh(this@ChatActivity,false)
    }
    override fun onStart() {super.onStart();conversationOpen++;MeshService.chatVisible=true;MeshService.get()?.cancelChatNotification()}
    override fun onStop() {MeshService.chatVisible=false;super.onStop()}
    private fun enqueue(type:Int,content:ByteArray,retry:ChatStore.Entry?=null):String? {
        val mesh=MeshService.get() ?: run {Permissions.startMesh(this@ChatActivity,false);return "Starting Bluetooth. Try sending again in a moment."}
        val error=mesh.chat.enqueue(type,content,retry)
        if(error==null&&!mesh.running) Permissions.startMesh(this@ChatActivity,false)
        return error
    }
    @Composable private fun ChatPage() {
        val revision=meshRevision();val mesh=remember(revision){MeshService.get()}
        var draft by rememberSaveable{mutableStateOf("")};var error by rememberSaveable{mutableStateOf<String?>(null)}
        var showPeers by remember{mutableStateOf(false)}
        val version=mesh?.chat?.historyVersion ?: -1
        val entries=remember(mesh,version){mesh?.chat?.store?.entries() ?: emptyList()}
        val previews=remember(revision){mesh?.chat?.previews() ?: emptyList()}
        val thread=rememberLazyListState()
        SideEffect{mesh?.chat?.finishTransfers()}
        // Index 0 is the visual bottom. Reverse layout also keeps a growing image or
        // multiline bubble anchored correctly, without relying on measured pixel offsets.
        LaunchedEffect(conversationOpen,entries.lastOrNull()?.row,previews.map{Triple(it.sender,it.id,it.received)}) {
            if(entries.isNotEmpty()||previews.isNotEmpty())thread.scrollToItem(0)
        }
        fun send() {
            val bytes=draft.toByteArray()
            error=when {draft.isBlank()->"Enter a message."; bytes.size>ChatProtocol.MAX_TEXT->"Use at most 4096 UTF-8 bytes.";else->enqueue(ChatProtocol.TEXT,bytes)}
            if(error==null) draft=""
        }
        var showMenu by remember { mutableStateOf(false) }
        val colors=MaterialTheme.colorScheme
        val bubbleWidth=minOf(520.dp, androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp * .82f)
        Box(Modifier.fillMaxSize().background(colors.background)) {
            Column(Modifier.widthIn(max=760.dp).fillMaxSize().align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                Surface(color=colors.surface.copy(alpha=if(LocalGlass.current.solid)1f else .94f),tonalElevation=1.dp) {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                            IconButton(onClick={onBackPressedDispatcher.onBackPressed()}) { Icon(Icons.AutoMirrored.Filled.ArrowBack,"Back") }
                            Column(Modifier.weight(1f).clickable{showPeers=true}.padding(vertical=6.dp)) {
                                Text("Nearby chat",style=MaterialTheme.typography.titleLarge)
                                Text(if(mesh?.ready==true) "Listening · ${mesh.chat.peers.values.count{android.os.SystemClock.elapsedRealtime()-it.seen<60000}} nearby · ${mesh.activeDataLinks()} links" else "Paused · Nearby devices",
                                    style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)
                            }
                            Box {
                                IconButton(onClick={showMenu=true}) {Icon(Icons.Default.MoreVert,"Chat options")}
                                DropdownMenu(expanded=showMenu,onDismissRequest={showMenu=false}) {
                                    DropdownMenuItem(text={Text("Nearby devices")},onClick={showMenu=false;showPeers=true})
                                    DropdownMenuItem(text={Text(if(mesh?.running==true) "Pause Bluetooth" else "Start Bluetooth")},onClick={showMenu=false;toggleRadio()})
                                    DropdownMenuItem(text={Text("Settings")},onClick={showMenu=false;startActivity(Intent(this@ChatActivity,PowerSettingsActivity::class.java))})
                                    if(mesh?.chat?.outgoing?.isNotEmpty()==true) DropdownMenuItem(text={Text("Cancel queued transfers")},onClick={showMenu=false;mesh.chat.cancelAll()})
                                }
                            }
                        }
                        Text("Public Bluetooth conversation · Unencrypted",Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=6.dp),
                            style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("conversation"),state=thread,reverseLayout=true,
                    verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(horizontal=16.dp,vertical=18.dp)) {
                    if(entries.isEmpty()&&previews.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=40.dp),horizontalAlignment=Alignment.CenterHorizontally,
                            verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Email,null,Modifier.size(40.dp),tint=colors.primary)
                            Text("Say hello nearby",style=MaterialTheme.typography.titleLarge)
                            Text("Messages from nearby phones appear here. No pairing or internet needed.",
                                style=MaterialTheme.typography.bodyMedium,color=colors.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    items(previews.asReversed(),key={"${it.sender}:${it.id}"}) {p ->
                        Column(Modifier.fillMaxWidth(.86f).background(colors.surfaceVariant,RoundedCornerShape(22.dp,22.dp,22.dp,6.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("${p.name ?: mesh?.chat?.peerName(p.sender) ?: "Phone"} · receiving",style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)
                            Text(if(p.type==ChatProtocol.JPEG||p.type==ChatProtocol.JXL)"Image arriving…" else p.text.ifBlank{"…"},style=MaterialTheme.typography.bodyLarge)
                            LinearProgressIndicator(progress={p.received.toFloat()/p.count.coerceAtLeast(1)},modifier=Modifier.fillMaxWidth())
                            Text("${p.received} / ${p.count} packets",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                        }
                    }
                    items(entries.asReversed(),key={it.row}) {entry ->
                        Column(Modifier.fillMaxWidth(),horizontalAlignment=if(entry.outgoing)Alignment.End else Alignment.Start) {
                            if(!entry.outgoing) Text(DeviceIdentity.display(entry.name,entry.sender),
                                Modifier.padding(start=12.dp,bottom=4.dp),style=MaterialTheme.typography.labelMedium,color=colors.onSurfaceVariant)
                            Surface(modifier=Modifier.widthIn(max=bubbleWidth),
                                color=if(entry.outgoing)colors.primary else colors.surfaceVariant,
                                contentColor=if(entry.outgoing)colors.onPrimary else colors.onSurface,
                                shape=RoundedCornerShape(topStart=22.dp,topEnd=22.dp,bottomStart=if(entry.outgoing)22.dp else 6.dp,bottomEnd=if(entry.outgoing)6.dp else 22.dp)) {
                                Column(Modifier.padding(horizontal=16.dp,vertical=11.dp)) {
                                    if(entry.type==ChatProtocol.TEXT) SelectionContainer {Text(entry.content.toString(Charsets.UTF_8),style=MaterialTheme.typography.bodyLarge)}
                                    else {
                                        MessageImage(entry.content,entry.row,"Image from ${if(entry.outgoing) "you" else entry.name}")
                                    }
                                }
                            }
                            Text(SimpleDateFormat("HH:mm",Locale.getDefault()).format(Date(entry.time))+if(entry.outgoing) " · "+entry.status else "",
                                Modifier.padding(horizontal=12.dp,vertical=4.dp),style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                            if(entry.outgoing&&entry.status!="Sending"&&(!entry.status.startsWith("Delivered to")||entry.status.contains("Retry")))
                                TextButton(onClick={error=enqueue(entry.type,entry.content,entry)}){Text("Retry")}
                        }
                    }
                }
                Surface(color=colors.surface,tonalElevation=2.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(mesh?.chat?.imageLinkBusy==true) {
                            LinearProgressIndicator(progress={mesh.chat.imageLinkProgress},modifier=Modifier.fillMaxWidth().semantics{contentDescription="Connected image transfer progress"})
                        }
                        if(mesh?.chat?.imageLinkStatus?.isNotBlank()==true)Text(mesh.chat.imageLinkStatus,Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                        mesh?.chat?.outgoing?.peek()?.takeIf{!it.bulkPending}?.let { transfer ->
                            LinearProgressIndicator(progress={transfer.data.sent().toFloat()/transfer.data.frames.size.coerceAtLeast(1)},
                                modifier=Modifier.fillMaxWidth().semantics{contentDescription="Outgoing transfer progress"})
                        }
                        if(imageProgress.isNotBlank()||mesh?.chat?.outgoing?.isNotEmpty()==true) {
                            Text(if(imageProgress.isNotBlank())imageProgress else mesh?.chat?.progress.orEmpty(),
                                Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                        }
                        if(error!=null) Text(error!!,Modifier.padding(horizontal=12.dp),color=colors.error,style=MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom) {
                            IconButton(onClick={
                                if((mesh?.chat?.outgoing?.size?:0)>=4)error="Wait for a queued transfer to finish."
                                else runCatching{picker.launch(arrayOf("image/*"))}.onFailure{error="No image picker available."}
                            }) {Icon(Icons.Default.Add,"Attach an image")}
                            OutlinedTextField(draft,{draft=it;error=null},modifier=Modifier.weight(1f).onPreviewKeyEvent { event ->
                                if(event.key==Key.Enter){if(event.type==KeyEventType.KeyDown)send();true}else false
                            }.semantics{contentDescription="Message nearby phones"},placeholder={Text("Message")},maxLines=4,shape=RoundedCornerShape(26.dp),isError=error!=null,
                                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Send),keyboardActions=KeyboardActions(onSend={send()}))
                            FilledIconButton(onClick={send()},enabled=draft.isNotBlank(),modifier=Modifier.padding(start=4.dp).size(48.dp)) {
                                Icon(Icons.AutoMirrored.Filled.Send,"Send message")
                            }
                        }
                    }
                }
            }
        }
        if(showPeers)DeviceConnectionsDialog(this,mesh,revision){showPeers=false}
    }
}
