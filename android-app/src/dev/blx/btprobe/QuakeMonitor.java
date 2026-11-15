package dev.blx.btprobe;

import android.content.*;
import android.hardware.*;
import android.os.*;
import java.util.*;

/** Owns sensor lifecycle in the existing foreground mesh service. Main thread only. */
final class QuakeMonitor implements SensorEventListener {
    private final MeshService mesh;
    private final SensorManager manager;
    private final Handler timer=new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> recording=new ArrayDeque<>();
    QuakeDetector detector;
    QuakeConsensus consensus;
    private Sensor sensor;
    private int sequence;
    private long lastSent, lastAdvisory;
    private String lastDecision="";
    private boolean listening, reliable=true;
    QuakeMonitor(MeshService mesh) { this.mesh=mesh; manager=mesh.getSystemService(SensorManager.class); }
    boolean enabled() { return true; }
    boolean test() { return MeshService.settings(mesh).getBoolean("quakeTest",QuakeDefaults.TEST); }
    void configure() { stop(); start(); }
    void start() {
        if(listening || !enabled()) return;
        try {
            consensus=new QuakeConsensus(mesh.sender,QuakeConsensus.parseRoster(MeshService.settings(mesh).getString("quakeRoster",QuakeDefaults.ROSTER),mesh.sender),test());
            detector=new QuakeDetector(test());
            sensor=manager==null?null:manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if(sensor==null) { MeshService.note("Earthquake monitor: accelerometer unavailable"); return; }
            reliable=true;
            listening=manager.registerListener(this,sensor,20000,0);
            if(!listening) { MeshService.note("Earthquake monitor: sensor registration failed"); return; }
            MeshService.note("Earthquake monitor ON · "+(test()?"SHAKE TEST":"experimental monitoring")+" · quorum "+consensus.required+"/"+consensus.roster.size());
            timer.post(update);
        } catch(RuntimeException e) { MeshService.note("Earthquake monitor cannot start: "+e.getMessage()); }
    }
    void stop() {
        if(manager!=null) manager.unregisterListener(this);
        timer.removeCallbacksAndMessages(null); mesh.getSystemService(android.app.NotificationManager.class).cancel(4); listening=false; consensus=null; detector=null; lastSent=0; lastDecision="";
    }
    private final Runnable update=new Runnable() { @Override public void run() {
        if(!listening || !enabled()) return;
        long now=SystemClock.elapsedRealtime(), wall=System.currentTimeMillis();
        consensus.local(++sequence,detector.stateAt(SystemClock.elapsedRealtimeNanos()),detector.rms,wall,now);
        String d=consensus.decision(wall,now);
        if(!d.equals(lastDecision)) {
            lastDecision=d; MeshService.note("Sensor consensus · "+d);
            if(consensus.positives(wall,now)>=consensus.required && (lastAdvisory==0||now-lastAdvisory>=30000)) {
                lastAdvisory=now; mesh.sensorAdvisory(test(),d);
            }
        }
        byte[] vote=nextPacket(now);if(vote!=null){if(mesh.data!=null)mesh.data.packet(vote);pendingBroadcast=vote;}
        MeshService.changed(); mesh.wake(); timer.postDelayed(this,500);
    }};
    private byte[] pendingBroadcast;
    byte[] broadcastPacket(){byte[] p=pendingBroadcast;pendingBroadcast=null;return p;}
    byte[] nextPacket(long elapsed) {
        if(!listening||elapsed-lastSent<500) return null;
        lastSent=elapsed;
        return consensus.packet(++sequence,detector.stateAt(SystemClock.elapsedRealtimeNanos()),detector.rms,System.currentTimeMillis());
    }
    void receive(Protocol.Packet packet) {
        if(listening && consensus.accept(packet,System.currentTimeMillis(),SystemClock.elapsedRealtime())) MeshService.changed();
    }
    @Override public void onSensorChanged(SensorEvent e) {
        if(!listening || !reliable) return;
        detector.sample(e.timestamp,e.values[0],e.values[1],e.values[2],sensor.getMaximumRange());
        String row=String.format(Locale.US,"%d,%d,%.7f,%.7f,%.7f,%s,%d,%.6f",System.currentTimeMillis(),e.timestamp,e.values[0],e.values[1],e.values[2],test()?"TEST":"MONITOR",detector.state,detector.rms);
        recording.addLast(row); while(recording.size()>6000) recording.removeFirst();
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy) {
        boolean was=reliable; reliable=accuracy!=SensorManager.SENSOR_STATUS_UNRELIABLE;
        if(detector!=null && (was!=reliable||!reliable)) detector.reset(reliable?"Sensor recovered; recalibrating":"Sensor accuracy unreliable");
    }
    String csv() {
        StringBuilder b=new StringBuilder("# BT Probe experimental sensor capture; raw accelerometer includes gravity; SI m/s^2\n");
        b.append(String.format(Locale.US,"# node=%08X sensor=%s range_m_s2=%s resolution_m_s2=%s\n",mesh.sender,sensor==null?"unavailable":sensor.getName(),sensor==null?"unknown":sensor.getMaximumRange(),sensor==null?"unknown":sensor.getResolution()));
        b.append("utc_ms,sensor_elapsed_ns,x_m_s2,y_m_s2,z_m_s2,mode,local_state,filtered_rms_m_s2\n");
        for(String row:recording) b.append(row).append('\n'); return b.toString();
    }
    String status() {
        if(!enabled()) return "Monitoring OFF";
        if(!listening) return "Waiting for Bluetooth / accelerometer";
        long w=System.currentTimeMillis(), t=SystemClock.elapsedRealtime();
        StringBuilder b=new StringBuilder(consensus.decision(w,t));
        b.append(String.format(Locale.US,"\n%d/%d votes required · group %08X\nLocal state %d · %.1f Hz · RMS %.4f m/s²\nBaseline %.4f · amplitude ratio %.2f\n%s",consensus.required,consensus.roster.size(),consensus.cohort,detector.stateAt(SystemClock.elapsedRealtimeNanos()),detector.hz,detector.rms,detector.baseline,detector.ratio,detector.stateAt(SystemClock.elapsedRealtimeNanos())==0?"No recent reliable sensor samples":detector.reason));
        for(int id:consensus.roster) {
            QuakeConsensus.Report r=null;
            for(QuakeConsensus.Report candidate:consensus.fresh(w,t)) if(candidate.sender==id) r=candidate;
            b.append(String.format(Locale.US,"\n%08X%s · %s",id,id==mesh.sender?" (this phone)":"",r==null?"missing / stale":r.state==3?"CANDIDATE":r.state==2?"quiet":r.state==1?"calibrating":"unavailable"));
        }
        return b.toString();
    }
}
