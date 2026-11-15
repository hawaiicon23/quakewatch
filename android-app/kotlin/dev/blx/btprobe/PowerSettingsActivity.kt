@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.blx.btprobe

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.*
import java.util.concurrent.TimeUnit

class PowerSettingsActivity : ProbeActivity() {
    private var name by mutableStateOf("")
    private var slider by mutableIntStateOf(1)
    private var mode by mutableIntStateOf(1)
    private var rootVerified by mutableStateOf(false)
    private var rootStatus by mutableStateOf("Root not checked")
    private var result by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private var testing by mutableStateOf(false)
    private var checking by mutableStateOf(false)
    private var pendingTest=false
    private var probe:AdvertisingSetCallback?=null
    private var advertiser:BluetoothLeAdvertiser?=null
    private var generation=0
    private val ui=Handler(Looper.getMainLooper())
    override fun onCreate(saved:Bundle?){
        super.onCreate(saved);val p=RadioPower.prefs(this@PowerSettingsActivity)
        rootVerified=p.getBoolean("root_verified",false)&&System.currentTimeMillis()-p.getLong("root_checked_at",0)<900000
        rootStatus=if(rootVerified)"Root recently verified" else p.getString("root_status","Root not checked")?:"Root not checked"
        name=saved?.getString("name")?:DeviceIdentity.name(this@PowerSettingsActivity);slider=saved?.getInt("slider")?:p.getInt("slider",1).coerceIn(0,20)
        mode=saved?.getInt("mode")?:if(p.getBoolean("boost",false)&&rootVerified)3 else if(p.getBoolean("force20",false))2 else 1
        if(mode==3&&!rootVerified)mode=1
        result=RadioPower.lastResult(this@PowerSettingsActivity)
        setContent{ProbeTheme{SettingsPage()}}
    }
    private fun request()=PowerPolicy.requested(slider,mode==2,mode==3,rootVerified)
    private fun save():Boolean{
        if(!DeviceIdentity.validName(name)){error="Use 1–12 UTF-8 bytes without control characters.";return false}
        return try{DeviceIdentity.saveName(this@PowerSettingsActivity,name);RadioPower.save(this@PowerSettingsActivity,slider,mode==2,mode==3,rootVerified);setResult(RESULT_OK);result=RadioPower.lastResult(this@PowerSettingsActivity);error=null;true}
        catch(e:Exception){error="Could not apply: ${e.message}";false}
    }
    @Composable private fun SettingsPage(){
        val p=options();var revision by remember{mutableIntStateOf(0)};@Suppress("UNUSED_VARIABLE")val read=revision
        fun change(k:String,v:Boolean){p.edit().putBoolean(k,v).apply();revision++}
        GlassScreen(this,3,"Make it yours.","Appearance, device identity and radio controls."){
            Panel{
                Eyebrow("APPEARANCE")
                Text("A clearer view.",style=MaterialTheme.typography.titleLarge)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    listOf("system" to "System","light" to "Light","dark" to "Dark").forEach{(key,label)->
                        FilterChip(selected=p.getString("theme","system")==key,onClick={p.edit().putString("theme",key).apply();revision++},label={Text(label)},modifier=Modifier.heightIn(min=48.dp))
                    }
                }
                ToggleRow("Reduce transparency","Solid surfaces with a defined edge.",p.getBoolean("solid",false)){change("solid",it)}
                ToggleRow("Reduce motion","Immediate state changes with minimal movement.",p.getBoolean("reduceMotion",false)){change("reduceMotion",it)}
                ToggleRow("Higher contrast","Stronger labels, borders and opaque navigation.",p.getBoolean("contrast",false)){change("contrast",it)}
                Supporting("System dark mode and disabled system animations are respected. Glass blur is available on Android 12 and newer; older phones use solid navigation.")
            }
            Panel{
                Eyebrow("EARTHQUAKE ALERTS")
                val alertPrefs=MeshService.settings(this@PowerSettingsActivity)
                val selected=alertPrefs.getString("quake_alert_mode","notification")
                listOf("notification" to "Notification", "audio" to "Audio", "off" to "Off").forEach{(key,label)->
                    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(selected==key,role=Role.RadioButton,onClick={
                        alertPrefs.edit().putString("quake_alert_mode",key).apply();revision++;MeshService.get()?.alertSettingsChanged()
                        if(key!="off"&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),92)
                    }),verticalAlignment=Alignment.CenterVertically){RadioButton(selected=selected==key,onClick=null);Spacer(Modifier.width(10.dp));Text(label)}
                }
                ToggleRow("Use media volume","Off uses alarm volume, so media mute does not silence earthquake audio.",alertPrefs.getBoolean("quake_media_volume",true)){
                    alertPrefs.edit().putBoolean("quake_media_volume",it).apply();revision++;MeshService.get()?.alertSettingsChanged()
                }
                Supporting("Notification shows a silent alert. Audio adds spoken alerts using the selected system volume. Off silences alerts; sensing and message delivery continue. Android notification settings still apply.")
            }
            Panel{
                Eyebrow("IMAGE TRANSFERS")
                val transfers=MeshService.settings(this@PowerSettingsActivity)
                ToggleRow("Image broadcast fallback","Allow images over advertising when a connected transfer cannot reach a device.",transfers.getBoolean("image_advertising",true)){
                    transfers.edit().putBoolean("image_advertising",it).apply();revision++
                    MeshService.get()?.chat?.finishTransfers();MeshService.get()?.wake();MeshService.changed()
                }
                Supporting("With fallback off, images use connected Bluetooth only. Discovery stays on. Text uses data links first with advertising fallback. Up to three persistent data peers can stay connected; speed and availability depend on the phones.")
            }
            Panel{
                Eyebrow("DEVICE IDENTITY")
                OutlinedTextField(name,{name=it;error=null},label={Text("Device name")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),isError=error!=null)
                Supporting(String.format("Stable node · %08X",DeviceIdentity.id(this@PowerSettingsActivity)))
                Supporting("Shared across nearby beacons, chat and walkie-talkie. Up to 12 UTF-8 bytes.")
                if(error!=null)Text(error!!,color=MaterialTheme.colorScheme.error)
            }
            Panel{
                Eyebrow("BLUETOOTH POWER REQUEST")
                Text("${RadioPower.signed(request())} dBm",style=MaterialTheme.typography.displaySmall)
                Supporting("Requested setting · supported output is chosen by the controller.")
                RadioOption("Custom request",1)
                Slider(slider.toFloat(),{slider=it.toInt()},valueRange=0f..20f,steps=19,enabled=mode==1,modifier=Modifier.fillMaxWidth().semantics { contentDescription="Bluetooth transmit power request in dBm" })
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Supporting("0 dBm");Supporting("+20 dBm")}
                RadioOption("Maximum request · +20 dBm",2)
                PremiumButton("Reset request to +1 dBm",secondary=true){mode=1;slider=1}
                Supporting(RadioPower.device()+"\n"+RadioPower.method(request()))
            }
            Panel{
                Eyebrow("CONTROLLER RESULT")
                Text(result,style=MaterialTheme.typography.bodyLarge)
                Supporting("A controller report, not a measurement of antenna output. This setting requests BLE advertising power; it does not control connected-data or cellular power.")
                PremiumButton(if(testing)"Testing request…" else "Apply & test on this phone",enabled=!testing,secondary=true){if(save()){pendingTest=true;prepareTest()}}
            }
            Panel{
                Eyebrow("ADVANCED / ROOT")
                Supporting(rootStatus)
                PremiumButton(if(checking)"Checking root…" else "Check root access",enabled=!checking,secondary=true, onClick=::checkRoot)
                RadioOption("Experimental +21 dBm request",3,rootVerified)
                Supporting("Requires a recent root check. Firmware may cap or reject the request; root does not guarantee a power increase.")
            }
            PremiumButton("Apply & return"){if(save()){stopProbe();finish()}}
            PremiumButton("Cancel",secondary=true){finish()}
        }
    }
    @Composable private fun RadioOption(label:String,value:Int,enabled:Boolean=true){
        Row(Modifier.fillMaxWidth().selectable(mode==value,enabled=enabled,role=Role.RadioButton,onClick={mode=value}).padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
            RadioButton(selected=mode==value,onClick=null,enabled=enabled);Spacer(Modifier.width(10.dp));Text(label,color=if(enabled)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    // The platform lint checker cannot infer the permission-array gate below.
    @android.annotation.SuppressLint("MissingPermission")
    private fun prepareTest(){
        if(Build.VERSION.SDK_INT>=31){val permissions=arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.BLUETOOTH_CONNECT);if(permissions.any{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}){requestPermissions(permissions,90);return}}
        val adapter=getSystemService(BluetoothManager::class.java)?.adapter?:run{result="This phone has no Bluetooth adapter";pendingTest=false;return}
        if(!adapter.isEnabled){startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),91);return}
        pendingTest=false;stopProbe();testing=true;result="Applying ${RadioPower.signed(request())} dBm…"
        val epoch=generation;ui.postDelayed({if(epoch==generation)runProbe(adapter)},300)
    }
    @android.annotation.SuppressLint("MissingPermission") // called only after prepareTest checks both Bluetooth permissions
    private fun runProbe(adapter:BluetoothAdapter){
        val epoch=generation;val request=RadioPower.requested(this@PowerSettingsActivity);val revision=RadioPower.revision(this@PowerSettingsActivity)
        try{
            val owner=adapter.bluetoothLeAdvertiser?:error("BLE advertising unavailable");advertiser=owner
            probe=object:AdvertisingSetCallback(){override fun onAdvertisingSetStarted(set:AdvertisingSet?,power:Int,status:Int){
                try { owner.stopAdvertisingSet(this) } catch (_: SecurityException) { if(epoch==generation){result="Nearby devices permission was revoked";testing=false};return };if(epoch!=generation)return
                if(status==0)RadioPower.report(this@PowerSettingsActivity,revision,request,power,"Settings test")
                else RadioPower.failure(this@PowerSettingsActivity,revision,"Request ${RadioPower.signed(request)} dBm rejected · status $status")
                result=RadioPower.lastResult(this@PowerSettingsActivity);testing=false;probe=null
            }}
            owner.startAdvertisingSet(RadioPower.parameters(request),AdvertiseData.Builder().build(),null,null,null,probe)
            ui.postDelayed({if(epoch==generation&&probe!=null){RadioPower.failure(this@PowerSettingsActivity,revision,"Power test timed out. Wait and retry.");stopProbe();result=RadioPower.lastResult(this@PowerSettingsActivity)}},8000)
        }catch(e:Exception){RadioPower.failure(this@PowerSettingsActivity,revision,"Request failed: ${e.message}");stopProbe();result=RadioPower.lastResult(this@PowerSettingsActivity)}
    }
    @android.annotation.SuppressLint("MissingPermission") // best-effort cleanup protected by runCatching
    private fun stopProbe(){generation++;ui.removeCallbacksAndMessages(null);runCatching{probe?.let{advertiser?.stopAdvertisingSet(it)}};probe=null;testing=false}
    private fun checkRoot(){
        checking=true;rootStatus="Checking root. Allow the Superuser prompt if shown."
        Thread({
            var verified=false
            val status=try{
                val process=ProcessBuilder("su","-c","id").redirectErrorStream(true).start()
                if(!process.waitFor(8,TimeUnit.SECONDS)){process.destroy();"Root check timed out"}
                else{val bytes=process.inputStream.use{it.readBytes()};verified=process.exitValue()==0&&bytes.toString(Charsets.UTF_8).contains("uid=0(");if(verified)"Root verified on this phone" else "Root was not granted"}
            }catch(e:Exception){"Root unavailable: ${e.javaClass.simpleName}"}
            runOnUiThread{if(!isFinishing&&!isDestroyed){rootVerified=verified;rootStatus=status;checking=false;if(!verified&&mode==3)mode=1;RadioPower.prefs(this@PowerSettingsActivity).edit().putBoolean("root_verified",verified).putLong("root_checked_at",if(verified)System.currentTimeMillis() else 0).putString("root_status",status).apply()}}
        },"root-capability-check").start()
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<String>,results:IntArray){super.onRequestPermissionsResult(code,permissions,results);if(code==90){if(results.any{it!=PackageManager.PERMISSION_GRANTED}){pendingTest=false;result="Nearby devices permission is required for a power test"}else if(pendingTest)prepareTest()}}
    @Deprecated("Compatibility with Android Bluetooth enable flow")
    override fun onActivityResult(code:Int,result:Int,data:Intent?){super.onActivityResult(code,result,data);if(code==91&&result==RESULT_OK&&pendingTest)prepareTest()}
    override fun onSaveInstanceState(out:Bundle){out.putString("name",name);out.putInt("slider",slider);out.putInt("mode",mode);super.onSaveInstanceState(out)}
    override fun onStop(){stopProbe();super.onStop()}
}
