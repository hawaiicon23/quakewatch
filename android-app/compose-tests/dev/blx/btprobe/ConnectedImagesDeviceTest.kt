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

/** Opt-in, two-physical-phone radio test. Run with -e bulkRole listen/send and peer ID. */
class ConnectedImagesDeviceTest {
    @Test fun physicalLink() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val role=args.getString("bulkRole")
        assumeTrue("Requires a second physical phone",role=="listen"||role=="send")
        fun onMain(action:()->Unit)=instrumentation.runOnMainSync(action)
        fun waitFor(timeout:Long=30000,condition:()->Boolean) {
            val until=SystemClock.elapsedRealtime()+timeout
            while(SystemClock.elapsedRealtime()<until) {
                var ready=false;onMain{ready=condition()};if(ready)return
                SystemClock.sleep(100)
            }
            fail("Timed out waiting for physical Bluetooth link")
        }
        val context=instrumentation.targetContext
        val originalFallback=MeshService.settings(context).getBoolean("image_advertising",true)
        if(args.getString("connectedOnly")=="true")MeshService.settings(context).edit().putBoolean("image_advertising",false).commit()
        try { ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use {scenario->
            scenario.onActivity{Permissions.startMesh(it,false)}
            waitFor{MeshService.get()?.ready==true}
            android.util.Log.i("BTImageTest","READY id="+Integer.toHexString(DeviceIdentity.id(context))+" role="+role)
            if(role=="listen") {
                val stopAt=SystemClock.elapsedRealtime()+8000
                val until=SystemClock.elapsedRealtime()+65000
                var stopped=false
                while(SystemClock.elapsedRealtime()<until) {
                    if(args.getString("disableBulk")=="true"&&!stopped&&SystemClock.elapsedRealtime()>stopAt){onMain{MeshService.get()?.bulk?.stop()};stopped=true;android.util.Log.i("BTImageTest","Listener disabled for fallback test")}
                    SystemClock.sleep(250)
                }
                return
            }
            val peer=java.lang.Long.parseLong(args.getString("peer"),16).toInt()
            waitFor{MeshService.get()?.bulk?.hasPeer(peer)==true}
            fun send(type:Int,bytes:ByteArray):ChatStore.Entry {
                var row=0L
                onMain {
                    val chat=MeshService.get()!!.chat
                    // Scope this benchmark to the two selected physical phones. Other real
                    // nearby nodes remain untouched and will be rediscovered after the test.
                    chat.peers.keys.retainAll(setOf(peer))
                    assertNull(chat.enqueue(type,bytes,null))
                    row=chat.store.entries().last().row
                }
                waitFor(45000){MeshService.get()!!.chat.store.entries().firstOrNull{it.row==row}?.status?.startsWith("Delivered to")==true}
                var found:ChatStore.Entry?=null
                onMain {found=MeshService.get()!!.chat.store.entries().first{it.row==row}}
                return found!!
            }
            val text=send(ChatProtocol.TEXT,"Advertising transport check".toByteArray())
            assertTrue(text.status.startsWith("Delivered to"))
            val fallback=args.getString("fallback")=="true"
            if(fallback)SystemClock.sleep(10000)
            val image=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
            for(y in 0..511)for(x in 0..511) image.setPixel(x,y,Color.rgb((x/2+(y%13)*2)%256,(y/2)%256,((x+y)/4)%256))
            for(edge in if(fallback)listOf(64)else listOf(64,256,512)) {
                val encoded=ChatImage.encode(image,edge,80)
                assertTrue(encoded.bytes.size<=ChatProtocol.MAX_IMAGE)
                val start=SystemClock.elapsedRealtime()
                val entry=send(ChatProtocol.JXL,encoded.bytes)
                if(fallback)assertFalse(entry.status.contains("data link"))else assertTrue(entry.status,entry.status.contains("data link"))
                val hash=java.security.MessageDigest.getInstance("SHA-256").digest(encoded.bytes).joinToString(""){"%02x".format(it)}
                android.util.Log.i("BTImageTest","BENCH edge=$edge bytes=${encoded.bytes.size} elapsed_ms=${SystemClock.elapsedRealtime()-start} id=${entry.id} status=${entry.status} sha256=$hash")
            }
            image.recycle()
        }} finally {MeshService.settings(context).edit().putBoolean("image_advertising",originalFallback).commit()}
    }
}
