package dev.blx.btprobe
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in physical two-phone test: -e dataRole listen/send -e peer HEX. */
class ConnectedDataDeviceTest {
    @Test fun physicalPersistentLink(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
        val role=args.getString("dataRole");assumeTrue("Needs two physical phones",role=="listen"||role=="send")
        val started=System.currentTimeMillis()
        val context=instrumentation.targetContext;val peer=java.lang.Long.parseLong(args.getString("peer"),16).toInt();val self=DeviceIdentity.id(context)
        fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
        fun waitFor(timeout:Long=30000,condition:()->Boolean){val until=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<until){var ok=false;main{ok=condition()};if(ok)return;SystemClock.sleep(100)};fail("Timed out: persistent data test")}
        val prefs=MeshService.settings(context);val roster=prefs.getString("quakeRoster",QuakeDefaults.ROSTER);val test=prefs.getBoolean("quakeTest",QuakeDefaults.TEST)
        try{ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use{scenario->
            scenario.onActivity{Permissions.startMesh(it,false)}
            waitFor{MeshService.get()?.ready==true}
            main{prefs.edit().putString("quakeRoster",String.format("%08X %08X",self,peer)).putBoolean("quakeTest",false).commit();MeshService.get()!!.quake.configure()}
            waitFor{MeshService.get()?.data?.connectedPeers()?.contains(peer)==true}
            waitFor{MeshService.get()!!.quake.consensus.fresh(System.currentTimeMillis(),SystemClock.elapsedRealtime()).any{it.sender==peer}}
            android.util.Log.i("BTDataTest","CONNECTED_AND_SENSOR_VOTE role=$role peer="+Integer.toHexString(peer))
            ActivityScenario.launch<WalkieTalkieActivity>(Intent(context,WalkieTalkieActivity::class.java)).use{voice->
                if(role=="listen"){
                    var initialAlerts=0;main{initialAlerts=MeshService.get()!!.dataAlertsReceived}
                    waitFor(60000){val chat=MeshService.get()!!.chat;chat.store.entries().any{!it.outgoing&&it.sender==peer&&it.time>=started&&it.type==ChatProtocol.JXL}}
                    waitFor{MeshService.get()!!.dataAlertsReceived>initialAlerts}
                    var played=false
                    val until=SystemClock.elapsedRealtime()+30000
                    while(!played&&SystemClock.elapsedRealtime()<until){voice.onActivity{played=it.played>0};SystemClock.sleep(100)}
                    assertTrue("Data-link audio was played",played)
                    waitFor(40000){MeshService.get()!!.chat.store.entries().any{!it.outgoing&&it.sender==peer&&it.time>=started&&it.type==ChatProtocol.TEXT&&it.content.toString(Charsets.UTF_8)=="Persistent-link-finished"}}
                    main{assertTrue(MeshService.get()!!.data.connectedPeers().contains(peer))}
                    android.util.Log.i("BTDataTest","RECEIVED_IMAGE_ALERT_AND_VOICE link_still_open_after_20s=true")
                    SystemClock.sleep(2000) // do not terminate instrumentation before the sender checks reuse
                }else{
                    fun send(type:Int,bytes:ByteArray){var row=0L;main{val chat=MeshService.get()!!.chat;chat.peers.keys.retainAll(setOf(peer));assertNull(chat.enqueue(type,bytes,null));row=chat.store.entries().last().row}
                        waitFor(15000){MeshService.get()!!.chat.store.entries().any{it.row==row&&it.status.startsWith("Delivered to")&&it.status.contains("data link")}}
                        main{android.util.Log.i("BTDataTest","DELIVERED type=$type "+MeshService.get()!!.chat.store.entries().first{it.row==row}.status)}
                    }
                    SystemClock.sleep(1500) // receiver voice page must be ready before fixture delivery
                    send(ChatProtocol.TEXT,"Persistent data-link text check".toByteArray())
                    val image=Bitmap.createBitmap(128,96,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(45,100,190))}
                    val encoded=ChatImage.encode(image,128,80);image.recycle();send(ChatProtocol.JXL,encoded.bytes)
                    main{assertTrue(MeshService.get()!!.broadcast("TEST"))}
                    voice.onActivity{it.sendTone()};SystemClock.sleep(1200)
                    voice.onActivity{assertTrue(it.sent>=25)}
                    SystemClock.sleep(20000)
                    main{assertTrue(MeshService.get()!!.data.connectedPeers().contains(peer))}
                    send(ChatProtocol.TEXT,"Persistent-link-finished".toByteArray())
                    android.util.Log.i("BTDataTest","VOICE_SENT link_still_open_after_20s=true")
                    SystemClock.sleep(1000)
                }
            }
        }}finally{main{prefs.edit().putString("quakeRoster",roster).putBoolean("quakeTest",test).commit();MeshService.get()?.quake?.configure()}}
    }
}
