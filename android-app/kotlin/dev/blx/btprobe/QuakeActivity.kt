package dev.blx.btprobe

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import java.util.Locale

internal class SensorExport : ViewModel() {var csv:String?=null}
class QuakeActivity : ProbeActivity() {
    private lateinit var export:SensorExport
    private var exportStatus by mutableStateOf("")
    private val saveCsv=registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->
        if(uri!=null) try {contentResolver.openOutputStream(uri)?.use{it.write((export.csv?:error("Capture expired")).toByteArray())}?:error("No output stream");exportStatus="Sensor capture exported."}
        catch(e:Exception){exportStatus="Export failed: ${e.message}"}
        export.csv=null
    }
    override fun onCreate(saved:Bundle?){super.onCreate(saved);export=ViewModelProvider(this)[SensorExport::class.java];setContent{ProbeTheme{SensingPage()}}}
    @Composable private fun SensingPage(){
        val tick=meshRevision();val mesh=remember(tick){MeshService.get()};val monitor=mesh?.quake;val prefs=MeshService.settings(this@QuakeActivity)
        var test by rememberSaveable{mutableStateOf(prefs.getBoolean("quakeTest",QuakeDefaults.TEST))}
        var roster by rememberSaveable{mutableStateOf(prefs.getString("quakeRoster",QuakeDefaults.ROSTER)?:"")}
        var error by rememberSaveable{mutableStateOf<String?>(null)}
        val wall=System.currentTimeMillis();val elapsed=SystemClock.elapsedRealtime();val detector=monitor?.detector;val consensus=monitor?.consensus
        val state=detector?.stateAt(SystemClock.elapsedRealtimeNanos())?:0
        val appliedTest=prefs.getBoolean("quakeTest",QuakeDefaults.TEST)
        val decision=consensus?.decision(wall,elapsed)?:if(monitor?.enabled()==true)"Waiting for Bluetooth and accelerometer" else "Monitoring is off"
        GlassScreen(this,2,"Feel. Compare. Understand.","Independent sensor readings, shared nearby."){
            Panel {
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Pill(if(appliedTest)"Shake-test mode" else "Experimental sensing",monitor?.enabled()==true)}
                Text(decision,style=MaterialTheme.typography.titleLarge)
                Supporting("Possible earthquake only. Coincident table or vehicle motion can also make phones agree. This prototype is not a verified warning system.")
            }
            MetricPair(
                first={Panel{Eyebrow("Motion RMS");Text(String.format(Locale.US,"%.4f",detector?.rms?:0.0),style=MaterialTheme.typography.headlineMedium);Supporting("m/s² · filtered")}},
                second={Panel{Eyebrow("Sensor rate");Text(String.format(Locale.US,"%.1f",detector?.hz?:0.0),style=MaterialTheme.typography.headlineMedium);Supporting("Hz · target 50")}}
            )
            Panel {
                Eyebrow("COLLECTIVE OBSERVATIONS")
                Text(if(consensus!=null)"${consensus.positives(wall,elapsed)} candidate votes · ${consensus.required} needed" else "Waiting for a sensing group",style=MaterialTheme.typography.titleLarge)
                if(consensus!=null){
                    val reports=consensus.fresh(wall,elapsed).associateBy{it.sender}
                    consensus.roster.forEach{id->
                        val report=reports[id];val label=when(report?.state){3->"Candidate";2->"Quiet";1->"Calibrating";0->"Unavailable";else->"Missing / stale"}
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                            Column(Modifier.weight(1f)){Text(String.format(Locale.US,"%08X",id),fontWeight=FontWeight.SemiBold);Supporting(if(id==consensus.self)"This phone" else "Nearby node")}
                            Pill(label,report?.state==2||report?.state==3)
                        }
                    }
                    Supporting(String.format(Locale.US,"Group %08X · fixed roster",consensus.cohort))
                }
                Supporting(if(state==0)"No recent usable local samples." else detector?.reason?:"Start monitoring to see sensor status.")
                if(detector!=null)Supporting(String.format(Locale.US,"Baseline %.4f m/s² · motion / noise %.2f×",detector.baseline,detector.ratio))
            }
            Panel {
                Eyebrow("SENSING CONTROLS")
                Supporting("Accelerometer monitoring is always on while the background service runs, including during Bluetooth reconnects.")
                ToggleRow("Shake-test mode","Short calibration. TEST votes never count in normal mode.",test){test=it}
                Supporting(DeviceIdentity.display(DeviceIdentity.name(this@QuakeActivity),DeviceIdentity.id(this@QuakeActivity)))
                OutlinedTextField(roster,{roster=it;error=null},label={Text("Shared node IDs")},supportingText={Text("8-digit IDs separated by spaces or commas. Use the same list on every phone.")},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),isError=error!=null)
                if(error!=null)Text(error!!,color=MaterialTheme.colorScheme.error)
                PremiumButton("Apply sensing settings"){
                    try{
                        val ids=QuakeConsensus.parseRoster(roster,DeviceIdentity.id(this@QuakeActivity));val joined=ids.joinToString(" "){String.format(Locale.US,"%08X",it)}
                        prefs.edit().putBoolean("quakeTest",test).putString("quakeRoster",joined).apply();roster=joined;mesh?.quake?.configure();error=null
                        Permissions.startMesh(this@QuakeActivity,null)
                    }catch(e:IllegalArgumentException){error=e.message}
                }
                Supporting("Keep still for ${if(test)3 else 30} seconds after applying. For a table test, move one phone first, then both together for at least one second. Two nodes need both votes; larger groups need a strict majority. Keep clocks aligned to about one second.")
            }
            Panel{
                Eyebrow("CAPTURE & REVIEW")
                Supporting("Export up to 6000 recent raw samples, with timestamps, mode and local decision. Approximately two minutes at 50 Hz.")
                PremiumButton("Export sensor CSV",enabled=mesh!=null,secondary=true){export.csv=mesh?.quake?.csv();saveCsv.launch("btprobe-sensors.csv")}
                if(exportStatus.isNotBlank())Supporting(exportStatus)
                Supporting("No correlated shaking does not rule out an earthquake. Missing or stale reports cannot vote. Sensor observations are public, unauthenticated and direct-neighbor only.")
            }
        }
    }
}
