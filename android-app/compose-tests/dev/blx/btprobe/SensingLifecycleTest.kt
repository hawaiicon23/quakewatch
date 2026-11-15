package dev.blx.btprobe
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SensingLifecycleTest {
    @Test fun sensingKeepsSamplingWhileBluetoothIsPaused(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java)).use{scenario->
            scenario.onActivity{Permissions.startMesh(it,null)}
            val until=SystemClock.elapsedRealtime()+10000
            var ready=false
            while(!ready&&SystemClock.elapsedRealtime()<until){instrumentation.runOnMainSync{ready=MeshService.get()?.quake?.detector!=null};SystemClock.sleep(100)}
            assertTrue("Sensor started automatically",ready)
            var detector:QuakeDetector?=null
            instrumentation.runOnMainSync{detector=MeshService.get()!!.quake.detector}
            context.startService(Intent(context,MeshService::class.java).setAction(MeshService.ACTION_STOP))
            SystemClock.sleep(2000)
            try{instrumentation.runOnMainSync{
                val mesh=MeshService.get()!!;assertFalse(mesh.running);assertSame(detector,mesh.quake.detector)
                assertTrue(mesh.quake.enabled());assertTrue("Live samples continue",mesh.quake.detector.stateAt(SystemClock.elapsedRealtimeNanos())!=QuakeDetector.UNAVAILABLE)
            }}finally{scenario.onActivity{Permissions.startMesh(it,null)}}
        }
    }
}
