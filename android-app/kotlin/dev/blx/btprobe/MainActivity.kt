package dev.blx.btprobe

import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import java.io.File
import java.util.Locale

class MainActivity : ProbeActivity() {
    private var page by mutableIntStateOf(0)
    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        page = saved?.getInt("page") ?: if (intent.getStringExtra("page") == "tools") 3 else 0
        setContent { ProbeTheme { MainPage() } }
        if(saved==null)Permissions.startMesh(this,null)
        if (MeshService.lines.isEmpty()) MeshService.note("Ready. Start Bluetooth to connect with nearby phones.")
        if (getSystemService(android.media.AudioManager::class.java)?.getStreamVolume(AlertPlayer.volumeStream(this)) == 0)
            MeshService.note("${if(AlertPlayer.useMediaVolume(this)) "Media" else "Alarm"} volume is muted: earthquake audio will be silent.")
    }
    override fun onStart() { super.onStart(); MeshService.mainVisible = true; getSystemService(NotificationManager::class.java).cancel(2) }
    override fun onStop() { MeshService.mainVisible = false; super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); page = if (intent.getStringExtra("page") == "tools") 3 else 0 }
    override fun onSaveInstanceState(out: Bundle) { out.putInt("page", page); super.onSaveInstanceState(out) }

    @Composable private fun MainPage() {
        val tick = meshRevision(); val mesh = remember(tick) { MeshService.get() }
        val savedTabs = rememberSaveableStateHolder()
        val reducedMotion = LocalGlass.current.reducedMotion
        BackHandler(page == 3) { page = 0 }
        GlassScreen(this, page, if (page == 0) "Nearby, together." else "Your field kit.",
            if (page == 0) "A small network. A little more connection." else "Radio tests, relays and device controls.", back = false,
            navigate = { if (it == 0 || it == 3) page = it else navigateTo(this, it) }) {
            if (mesh?.bannerVisible == true) AlertBanner(mesh)
            AnimatedContent(page, transitionSpec = {
                if (reducedMotion) ContentTransform(EnterTransition.None, ExitTransition.None)
                else (fadeIn(spring(stiffness = 500f)) + slideInVertically(spring(dampingRatio = 1f, stiffness = 500f)) { it / 18 }) togetherWith fadeOut(spring(stiffness = 500f))
            }, label = "network tools") { tab ->
                savedTabs.SaveableStateProvider(tab) { Column(verticalArrangement = Arrangement.spacedBy(18.dp)) { if (tab == 0) Network(mesh,tick) else Tools(mesh) } }
            }
        }
    }
    @Composable private fun Network(mesh: MeshService?,revision:Int) {
        var showConnections by remember {mutableStateOf(false)}
        val now = SystemClock.elapsedRealtime()
        val peers = mesh?.peers?.values()?.filter { now - it.lastSeen < 30000 } ?: emptyList()
        val on = mesh?.running == true
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(Color(0xFF153C43),Color(0xFF14253C)))).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("YOUR LOCAL NETWORK", Modifier.weight(1f), color = Color(0xFFC4DCD9), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Surface(color = Color.White.copy(alpha=.12f), shape = RoundedCornerShape(50)) { Text(if (mesh?.ready == true) "●  Listening" else if (on) "○  Starting" else "○  Offline", Modifier.padding(horizontal=12.dp,vertical=7.dp), color=Color.White, fontSize=12.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(peers.size.toString().padStart(2,'0'), color=Color.White, fontSize=62.sp, lineHeight=66.sp, fontWeight=FontWeight.Light, letterSpacing=(-3).sp)
                    Text(if (peers.size == 1) "phone nearby" else "phones nearby", color=Color(0xFFDBE9E8), fontSize=15.sp)
                }
                SignalMark(Modifier.size(104.dp), Color(0xFF83DED1))
            }
            Text(if (on) mesh?.stateText ?: "Listening nearby" else "Start Bluetooth to find your nearby network.", color=Color(0xFFDBE9E8), style=MaterialTheme.typography.bodyMedium)
            PremiumButton(if (on) "Pause Bluetooth" else "Start Bluetooth", onClick=::toggleRadio)
        }
        MetricPair(
            first = { Panel { Eyebrow("Messages sent"); Text("${mesh?.txCount ?: 0}", style=MaterialTheme.typography.headlineMedium); Supporting("This session") } },
            second = { Panel { Eyebrow("Replies"); Text("${mesh?.ackCount ?: 0}", style=MaterialTheme.typography.headlineMedium); Supporting("From nearby phones") } }
        )
        Panel {
            Eyebrow("MAKE A CONNECTION")
            ActionRow("Nearby chat", "Messages and small images, offline.", Icons.Default.Email) { startActivity(Intent(this@MainActivity,ChatActivity::class.java)) }
            HorizontalDivider(color=MaterialTheme.colorScheme.outline.copy(alpha=.12f))
            ActionRow("Walkie-talkie", "Hold to speak. Release to listen.", Icons.Default.Call) { startActivity(Intent(this@MainActivity,WalkieTalkieActivity::class.java)) }
        }
        val advertisingCount=mesh?.chat?.peers?.values?.count{now-it.seen<60000} ?: 0
        val activeLinks=mesh?.activeDataLinks() ?: 0
        Panel {
            Eyebrow("DEVICE CONNECTIONS")
            Text("$advertisingCount advertising · $activeLinks active data links",style=MaterialTheme.typography.titleLarge)
            Supporting("See node IDs, signal strength, active transfers and recent data-link results.")
            PremiumButton("Devices & links",secondary=true){showConnections=true}
        }
        if(showConnections)DeviceConnectionsDialog(this,mesh,revision){showConnections=false}
        Panel {
            Eyebrow("EARTHQUAKE SENSING")
            Text("Independent observations.\nA shared picture.",style=MaterialTheme.typography.titleLarge)
            Supporting("See live accelerometer readings and nearby node votes. Experimental sensing, with a separate table-shake test.")
            PremiumButton("Open sensing",secondary=true) { startActivity(Intent(this@MainActivity,QuakeActivity::class.java)) }
        }
        Supporting(DeviceIdentity.display(DeviceIdentity.name(this@MainActivity),DeviceIdentity.id(this@MainActivity))+"\nPublic Bluetooth mesh · no SIM or internet needed.")
    }
    @Composable private fun AlertBanner(mesh: MeshService) {
        Panel {
            Pill(if (mesh.banner.title.startsWith("✓")) "Trusted key signature" else "Nearby alert", false)
            Text(mesh.banner.title, style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.error, modifier=Modifier.semantics { liveRegion=LiveRegionMode.Assertive })
            Text(mesh.banner.body,style=MaterialTheme.typography.bodyLarge)
            Supporting(mesh.banner.meta)
            if (mesh.banner.showOk) PremiumButton(if (mesh.banner.okSent) "You’re marked safe" else "I'm OK", enabled=!mesh.banner.okSent) { mesh.markSafe() }
            if (mesh.banner.code=="CHK"||mesh.banner.code==MeshService.CHECK_SENT) Supporting("${mesh.safe.size} nearby people marked safe\n"+mesh.safe.joinToString { String.format("%08X",it) })
            PremiumButton("Dismiss", secondary=true) { mesh.dismissBanner() }
        }
    }
    @Composable private fun Tools(mesh: MeshService?) {
        var message by rememberSaveable { mutableStateOf("TEST") }
        var destination by rememberSaveable { mutableStateOf("") }
        var error by rememberSaveable { mutableStateOf<String?>(null) }
        var logExpanded by rememberSaveable { mutableStateOf(false) }
        var alerts by remember { mutableStateOf(false) }
        var authority by remember { mutableStateOf(false) }
        var keyRevision by remember { mutableIntStateOf(0) }
        var name by rememberSaveable { mutableStateOf(DeviceIdentity.name(this@MainActivity)) }
        val prefs=MeshService.settings(this@MainActivity)
        fun pref(key:String,default:Boolean)=prefs.getBoolean(key,default)
        var revision by remember { mutableIntStateOf(0) }
        @Suppress("UNUSED_VARIABLE") val read=revision
        fun setPref(key:String,value:Boolean) { prefs.edit().putBoolean(key,value).apply();revision++ }
        Panel {
            Eyebrow("RANGE & IDENTITY")
            Text("Find your range.",style=MaterialTheme.typography.titleLarge)
            Supporting("Continuous beacons every ~0.5 seconds. Nearby phones report received signal strength in dBm.")
            OutlinedTextField(name,{name=it},label={Text("Device name")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp))
            PremiumButton("Save device name",secondary=true) {
                if (!DeviceIdentity.validName(name)) error="Use 1–12 UTF-8 bytes without control characters."
                else { DeviceIdentity.saveName(this@MainActivity,name); mesh?.restartRadio(); error=null }
            }
            ToggleRow("Continuous test","Keep the radio broadcasting range-test beacons.",mesh?.continuous==true) { value -> Permissions.startMesh(this@MainActivity,value) }
            Supporting(String.format("Stable node · %08X",DeviceIdentity.id(this@MainActivity)))
        }
        Panel {
            Eyebrow("BROADCAST TEST")
            OutlinedTextField(message,{message=it;error=null},label={Text("Test message")},modifier=Modifier.fillMaxWidth(),singleLine=true,shape=RoundedCornerShape(16.dp))
            val relay=messageRelayOn()
            ToggleRow("Relay messages","Forward through up to three intermediary phones.",relay) { getSharedPreferences("identity",MODE_PRIVATE).edit().putBoolean("relay",it).apply();revision++ }
            if (relay) OutlinedTextField(destination,{destination=it;error=null},label={Text("Destination ID (blank = everyone)")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp))
            Supporting(if(relay) "1–8 UTF-8 bytes in relay mode." else "1–12 UTF-8 bytes per test message.")
            if(error!=null) Text(error!!,color=MaterialTheme.colorScheme.error)
            PremiumButton("Broadcast test",enabled=mesh?.ready==true) {
                val limit=if(relay) 8 else 12
                if(message.toByteArray().size !in 1..limit) error="Enter 1–$limit UTF-8 bytes."
                else if(relay && destination.isNotBlank()&&!destination.matches(Regex("[0-9a-fA-F]{8}"))) error="Destination must have 8 hexadecimal digits."
                else if(relay) mesh?.sendRelay(message,if(destination.isBlank()) 0 else destination.toLong(16).toInt()) else mesh?.broadcast(message)
            }
            ToggleRow("Automatic replies","Acknowledge received test messages.",pref("autoAck",true)) {setPref("autoAck",it)}
        }
        Panel {
            Eyebrow("ALERT & RELAY CONTROLS")
            ActionRow("Send an alert","Choose a message for nearby phones.",Icons.Default.Warning) {alerts=true}
            ToggleRow("Relay earthquake alerts","Forward eligible alerts through the mesh.",pref("relay",true)) {setPref("relay",it)}
            ToggleRow("Play unsigned official alerts","Testing only. These messages have no verified authority signature.",pref("playUnsigned",false)) {setPref("playUnsigned",it)}
            ActionRow("Authority key","Manage this phone’s alert-signing key.",Icons.Default.Info) {authority=true}
            ActionRow("Radio & appearance","Power requests, theme and accessibility.",Icons.Default.Settings) {startActivityForResult(Intent(this@MainActivity,PowerSettingsActivity::class.java),30)}
        }
        Panel {
            Eyebrow("RADIO ACTIVITY")
            Supporting("${mesh?.rxCount?:0} received · ${mesh?.relayCount?:0} relayed · ${mesh?.beaconCount?:0} beacons")
            PremiumButton(if(logExpanded) "Hide event log" else "Show event log",secondary=true) {logExpanded=!logExpanded}
            if(logExpanded) { SelectionContainer {Text(MeshService.lines.joinToString("\n\n"),fontFamily=FontFamily.Monospace,fontSize=12.sp,lineHeight=18.sp)};PremiumButton("Clear log",secondary=true){MeshService.clearLog()} }
        }
        if(alerts) AlertDialog(onDismissRequest={alerts=false},title={Text("Broadcast an alert")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
            AlertPlayer.ALERTS.forEach { (code,a) -> TextButton(onClick={ if(mesh?.running!=true) MeshService.note("Start Bluetooth before sending an alert.") else if(code=="CHK") mesh.sendCheck() else mesh.sendAlert(code);alerts=false }) {Text("$code · ${a.title}")} }
        }},confirmButton={TextButton(onClick={alerts=false}){Text("Close")}})
        if(authority) {
            val has=remember(keyRevision){PhoneAuthority.exists()}
            val line=remember(keyRevision,has){if(has) runCatching{PhoneAuthority.trustedLine(DeviceIdentity.name(this@MainActivity)+"-authority")}.getOrDefault("Key unavailable") else ""}
            AlertDialog(onDismissRequest={authority=false},title={Text("Alert authority")},text={SelectionContainer {Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(if(has) "This phone can sign outgoing official alert codes. Other phones must explicitly trust its public key." else "Create a private signing key in Android Keystore. Its public key must be added to the trusted key list on receiving phones.")
                if(has) {Text(line,fontSize=12.sp);Supporting("${mesh?.trustedCount()?:0} trusted keys built into this app.")}
            }}},dismissButton={TextButton(onClick={authority=false}){Text("Close")}},confirmButton={TextButton(onClick={
                runCatching { if(has) {val file=File(getExternalFilesDir(null) ?: filesDir,"authority_public_key.txt");file.writeText(line+"\n");MeshService.note("Public key saved at ${file.absolutePath}")} else {PhoneAuthority.create();keyRevision++} }.onFailure{MeshService.note("Key error: ${it.message}")}
            }){Text(if(has) "Export public key" else "Create key")}})
        }
    }
    private fun messageRelayOn()=getSharedPreferences("identity",MODE_PRIVATE).getBoolean("relay",false)
}
