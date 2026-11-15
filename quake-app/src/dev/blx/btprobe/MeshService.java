package dev.blx.btprobe;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * The Bluetooth mesh engine: scanning, advertising, relaying, alert playback and
 * safety check-ins. Runs as a foreground service so alerts keep working when the
 * screen is off, the app is in the background, or the Activity is destroyed.
 * MainActivity only renders this service's state (same process, main thread).
 */
public class MeshService extends Service {
    static final String ACTION_START="dev.blx.quakemesh.START", ACTION_STOP="dev.blx.quakemesh.STOP";
    static final String EXTRA_CONTINUOUS="continuous", EXTRA_AUTO="auto", EXTRA_AUTO_REASON="autoReason";
    static final String SAFE_CODE="OK", CHECK_SENT="CHK_SENT";
    private static final int RELAY_SUPPRESS=3, RELAY_MIN_MS=100, RELAY_JITTER_MS=400;
    /** Bursts (alert + signature fragments) cycle one payload every ROTATE_MS; wait this long for a signature. */
    // Each payload stays on air ~2 advertising events per cycle; 5 cycles. Receivers wait for the whole burst.
    private static final int ROTATE_MS=200, BURST_CYCLES=5, VERIFY_TIMEOUT_MS=8000;
    static final String UNVERIFIED="UNVERIFIED";
    private static final int NOTE_RUNNING=1, NOTE_ALERT=2;
    private static final String CH_RUNNING="mesh", CH_ALERTS="alerts";

    // ---- Link to the UI (same process, main thread only) ----
    private static MeshService instance;
    private static Runnable listener;
    private static boolean changePosted;
    private static final Handler main=new Handler(Looper.getMainLooper());
    /** Log survives the service stopping, so the UI can show why it stopped. */
    static final ArrayDeque<String> lines=new ArrayDeque<>();
    /** True while MainActivity is on screen: alerts then skip the notification. */
    static boolean uiVisible;

    static MeshService get() { return instance; }
    static void setListener(Runnable l) { listener=l; }
    static void changed() {
        if(changePosted) return;
        changePosted=true;
        main.post(()-> { changePosted=false; if(listener!=null) listener.run(); });
    }
    static void note(String text) {
        String time=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date());
        lines.addFirst(time+"  "+text); while(lines.size()>60) lines.removeLast(); changed();
    }
    static void clearLog() { lines.clear(); changed(); }
    static SharedPreferences settings(Context c) { return c.getSharedPreferences("settings",MODE_PRIVATE); }

    /** What the alert banner shows. */
    static final class Banner { String code="", title="", body="", meta=""; int tone; boolean showOk, okSent; }
    final Banner banner=new Banner();
    boolean bannerVisible;
    /** Phones that answered the current safety check with "I'm OK". */
    final Set<Integer> safe=new LinkedHashSet<>();

    // ---- Engine state (ported from the original BT Probe Activity) ----
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final SecureRandom random=new SecureRandom();
    int sender; String deviceLabel;
    boolean running, continuous, initializing;
    int txCount, rxCount, ackCount, relayCount, beaconCount;
    String stateText="Stopped";
    final PeerStats peers=new PeerStats();
    private boolean beaconAdvertising, transmitting, scanReady;
    private int beaconSequence, generation;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    /** Each item is a burst: 1 packet, or an alert followed by its signature fragments. */
    private final ArrayDeque<byte[][]> queue=new ArrayDeque<>();
    private final DeliveryTracker deliveries=new DeliveryTracker();
    private AdvertisingSetCallback active;
    private AdvertisingSet advertisingSet;
    private byte[] currentData;
    private byte[][] currentBurst;
    private int burstIndex;
    /** setAdvertisingData calls made by rotate() whose callback hasn't arrived yet. */
    private int pendingRotations, enableFailures;
    private List<SignedAlerts.Authority> trusted=new ArrayList<>();
    /** Signature fragments per alert (sender:id), and official alerts waiting for their signature. */
    private final LinkedHashMap<String,SignedAlerts.Collector> signatures=new LinkedHashMap<>();
    private final Map<String,PendingAlert> pendingAlerts=new HashMap<>();
    private static final class PendingAlert {
        final Protocol.Packet packet; final byte[] raw; final int rssi; final String from; byte[] ack;
        PendingAlert(Protocol.Packet p,byte[] r,int s,String f) { packet=p; raw=r; rssi=s; from=f; }
    }
    /** Alerts waiting to be relayed -> Bluetooth addresses heard broadcasting them (origin + relays). */
    private final Map<String,Set<String>> relayHeard=new HashMap<>();
    private AlertPlayer alerts;
    private PowerManager.WakeLock wakeLock;
    private boolean wantRunning;
    /**
     * Android 10-11 deny location (and so BLE scan results) to a foreground service started
     * from the background, e.g. at boot, unless the app has background location. Opening the
     * app re-starts the service from the foreground, which restores scanning.
     */
    boolean needsForegroundRestart;
    static final String EXTRA_REFRESH="refresh";
    private boolean scanBlockedInBackground() {
        return Build.VERSION.SDK_INT>=29 && Build.VERSION.SDK_INT<=30
            && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)!=PackageManager.PERMISSION_GRANTED;
    }
    /** Follow Bluetooth being switched off/on so listening resumes by itself. */
    private final BroadcastReceiver bluetoothState=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent i) {
            int st=i.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1);
            if(st==BluetoothAdapter.STATE_TURNING_OFF || st==BluetoothAdapter.STATE_OFF) {
                if(running) { stopRadio(false); }
                if(wantRunning && !"Waiting for Bluetooth".equals(stateText)) { stateText="Waiting for Bluetooth"; note("Bluetooth turned off · will resume when it is back on"); changed(); }
            } else if(st==BluetoothAdapter.STATE_ON && wantRunning && !running) {
                note("Bluetooth is on · starting radio"); handler.postDelayed(()-> { if(wantRunning && !running) startRadio(); },1000);
            }
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance=this;
        SharedPreferences prefs=getSharedPreferences("identity",MODE_PRIVATE);
        sender=prefs.getInt("id",random.nextInt());
        deviceLabel=prefs.getString("label",String.format(Locale.US,"Phone-%04X",sender&65535));
        prefs.edit().putInt("id",sender).putString("label",deviceLabel).apply();
        BluetoothManager manager=getSystemService(BluetoothManager.class);
        adapter=manager==null?null:manager.getAdapter();
        alerts=new AlertPlayer(this);
        loadTrusted();
        registerReceiver(bluetoothState,new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        NotificationManager nm=getSystemService(NotificationManager.class);
        NotificationChannel running=new NotificationChannel(CH_RUNNING,"Mesh listening",NotificationManager.IMPORTANCE_LOW);
        running.setDescription("Shown while Quake Mesh listens for alerts in the background");
        NotificationChannel alertCh=new NotificationChannel(CH_ALERTS,"Earthquake alerts",NotificationManager.IMPORTANCE_HIGH);
        alertCh.setDescription("Alerts received from nearby phones");
        alertCh.setSound(null,null); alertCh.enableVibration(false); // AlertPlayer plays voice + vibration itself
        alertCh.setBypassDnd(true); alertCh.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(running); nm.createNotificationChannel(alertCh);
    }

    private void loadTrusted() {
        int res=getResources().getIdentifier("trusted_keys","raw",getPackageName());
        if(res==0) return;
        try(java.io.InputStream in=getResources().openRawResource(res)) {
            java.io.ByteArrayOutputStream o=new java.io.ByteArrayOutputStream(); byte[] b=new byte[4096]; int n;
            while((n=in.read(b))>0) o.write(b,0,n);
            trusted=SignedAlerts.parseTrusted(o.toString("UTF-8"));
        } catch(java.io.IOException e) { note("Cannot read trusted keys: "+e.getMessage()); }
    }
    int trustedCount() { return trusted.size(); }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null||intent.getAction()==null?ACTION_START:intent.getAction(); // null intent = sticky restart
        if(ACTION_STOP.equals(action)) {
            settings(this).edit().putBoolean("autoStart",false).apply(); // stays off after reboot
            wantRunning=false; stopMesh(true); stopSelf(); return START_NOT_STICKY;
        }
        settings(this).edit().putBoolean("autoStart",true).apply(); // restart after reboot / app update
        wantRunning=true;
        boolean auto=intent==null || intent.getBooleanExtra(EXTRA_AUTO,false); // null = system restart (also background)
        if(auto) {
            note("Started automatically ("+(intent==null?"restarted by Android":intent.getStringExtra(EXTRA_AUTO_REASON))+").");
            if(scanBlockedInBackground()) {
                needsForegroundRestart=true;
                note("Android 11 blocks Bluetooth scanning for apps started in the background. Open Quake Mesh once (tap the notification), or allow location \"All the time\".");
            }
        } else if(needsForegroundRestart) {
            // Started again from the visible app: Android now grants location, so restart the scan.
            needsForegroundRestart=false;
            if(running && scanReady) { try { scanner.stopScan(scans); } catch(Exception ignored) {} scanReady=false; resumeScan(); note("Scanning resumed."); }
        }
        boolean cont=intent!=null && intent.getBooleanExtra(EXTRA_CONTINUOUS,false);
        try { goForeground(); }
        catch(RuntimeException e) { note("Cannot run in the background: "+e.getMessage()); stopSelf(); return START_NOT_STICKY; }
        if(running && cont!=continuous) stopRadio(false);
        continuous=cont;
        if(!running) startRadio();
        if(!running && adapter!=null && !adapter.isEnabled() && hasPermissions()) {
            stateText="Waiting for Bluetooth"; note("Waiting for Bluetooth to turn on…"); changed();
            return START_STICKY; // bluetoothState receiver starts the radio
        }
        if(!running) { stopMesh(false); stopSelf(); return START_NOT_STICKY; }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        wantRunning=false; try { unregisterReceiver(bluetoothState); } catch(IllegalArgumentException ignored) {}
        stopMesh(false); alerts.release(); instance=null; changed(); super.onDestroy();
    }

    // ---- Foreground notification, wake lock ----
    /** Status-bar icons must be a single-colour silhouette; the app icon renders as a white square. */
    private int icon() { return android.R.drawable.stat_sys_data_bluetooth; }
    private PendingIntent openApp() {
        Intent i=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private void goForeground() {
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MeshService.class).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CH_RUNNING).setSmallIcon(icon())
            .setContentTitle(needsForegroundRestart?"Tap to resume receiving alerts":"Listening for earthquake alerts")
            .setContentText(needsForegroundRestart?"Android needs Quake Mesh opened once after a restart (or location \"Allow all the time\")."
                :"Relaying alerts to nearby phones. Tap to open.")
            .setOngoing(true).setContentIntent(openApp())
            .addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
        if(Build.VERSION.SDK_INT>=29) {
            int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            // Android 10-11 need the location type for BLE scans while in the background.
            if(Build.VERSION.SDK_INT<31) type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            startForeground(NOTE_RUNNING,n,type);
        } else startForeground(NOTE_RUNNING,n);
        if(wakeLock==null) {
            wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"quakemesh:mesh");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire();
    }
    private void stopMesh(boolean byUser) {
        stopRadio(byUser);
        if(wakeLock!=null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
    }

    // ---- Radio ----
    private boolean hasPermissions() {
        String[] perms=Build.VERSION.SDK_INT>=31
            ? new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.BLUETOOTH_CONNECT}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for(String p:perms) if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    private final ScanCallback scans=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult r) { handler.post(()->receive(r)); }
        @Override public void onBatchScanResults(List<ScanResult> results) { for(ScanResult r:results) handler.post(()->receive(r)); }
        @Override public void onScanFailed(int code) { handler.post(()-> { if(running) { note("Scan failed ("+code+"). Stop, wait a few seconds, and retry."); stopRadio(false); }}); }
    };
    private void startRadio() {
        if(adapter==null) { note("This device has no Bluetooth adapter."); return; }
        if(!hasPermissions()) { note("Nearby devices permission is missing. Open the app and tap Start Bluetooth."); return; }
        if(!adapter.isEnabled()) { note("Bluetooth is off. Turn it on and start again."); return; }
        deviceLabel=getSharedPreferences("identity",MODE_PRIVATE).getString("label",deviceLabel);
        try {
            scanner=adapter.getBluetoothLeScanner(); advertiser=adapter.getBluetoothLeAdvertiser();
            if(scanner==null || advertiser==null) { note("Scanning and BLE advertising must both be supported."); return; }
            running=true; generation++; initializing=true; stateText="Starting radio"; changed();
            // Allocate the advertising address BEFORE starting the one continuous scan.
            // Retain this set for the session: recreating it per message triggers HCI 12,
            // while stopping/restarting scanning per message triggers Android scan throttling.
            final int epoch=generation;
            active=new AdvertisingSetCallback() {
                private boolean valid() { return running && generation==epoch && active==this; }
                @Override public void onAdvertisingSetStarted(AdvertisingSet set,int power,int status) {
                    if(!valid()) { advertiser.stopAdvertisingSet(this); return; }
                    if(status!=0) { note("Radio startup failed ("+status+"): "+advertiseError(status)); stopRadio(false); return; }
                    advertisingSet=set;
                    note("Radio ready · requested high power · controller "+power+" dBm");
                    set.enableAdvertising(false,0,0);
                }
                @Override public void onAdvertisingDataSet(AdvertisingSet set,int status) {
                    if(!valid()) return;
                    // Callbacks arrive in order: the first N belong to burst rotations, even after the burst ended.
                    if(pendingRotations>0) { pendingRotations--; if(status!=0) note("Burst update failed ("+status+")"); return; }
                    if(!transmitting) return;
                    if(status!=0) { failedTransmission(status); return; }
                    if(continuous && beaconAdvertising) transmissionStarted(epoch);
                    else set.enableAdvertising(true,0,0);
                }
                @Override public void onAdvertisingEnabled(AdvertisingSet set,boolean enabled,int status) {
                    if(!valid()) return;
                    if(status!=0) {
                        if(initializing) { note("Radio enable failed ("+status+")"); stopRadio(false); return; }
                        // A busy controller (e.g. HCI 12) must not take the whole mesh down: retry, then skip this packet.
                        if(++enableFailures<=3) {
                            note("Radio busy ("+status+") · retry "+enableFailures);
                            handler.postDelayed(()-> {
                                if(!valid()) return;
                                try { set.enableAdvertising(enabled,0,0); } catch(Exception e) { enableFailures=0; beaconAdvertising=false; finishTransmission(); }
                            },300L*enableFailures);
                        } else {
                            note("Broadcast skipped after repeated radio errors ("+status+")");
                            enableFailures=0; beaconAdvertising=false; finishTransmission();
                        }
                        return;
                    }
                    enableFailures=0;
                    if(initializing) {
                        if(enabled) return;
                        initializing=false;
                        try { resumeScan(); } catch(Exception e) { note("Scan failed: "+e.getMessage()); stopRadio(false); return; }
                        stateText="Listening nearby";
                        note("Listening for all BT Probe peers, also in the background. No pairing.");
                        note(trusted.size()+" trusted alert authorities. Official alerts need their signature."); pump();
                    } else if(enabled) { beaconAdvertising=true; transmissionStarted(epoch); }
                    else finishTransmission();
                }
            };
            AdvertisingSetParameters parameters=new AdvertisingSetParameters.Builder()
                .setLegacyMode(true).setConnectable(false).setScannable(false)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH).build();
            advertiser.startAdvertisingSet(parameters,new AdvertiseData.Builder().build(),null,null,null,active);
        } catch(Exception e) { note("Cannot start: "+e.getMessage()); stopRadio(false); }
    }
    private void resumeScan() {
        if(!running || scanReady) return;
        // A hardware filter keeps scan results flowing with the screen off.
        ScanFilter f=new ScanFilter.Builder().setManufacturerData(Protocol.COMPANY,Protocol.PREFIX,new byte[]{-1,-1,-1}).build();
        scanner.startScan(Collections.singletonList(f),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scans);
        scanReady=true;
    }
    private void stopRadio(boolean byUser) {
        boolean wasRunning=running; running=false; generation++; handler.removeCallbacksAndMessages(null);
        try { if(scanReady && scanner!=null) scanner.stopScan(scans); } catch(Exception ignored) {}
        try { if(active!=null && advertiser!=null) advertiser.stopAdvertisingSet(active); } catch(Exception ignored) {}
        beaconAdvertising=false; if(byUser) continuous=false;
        active=null; advertisingSet=null; currentData=null; initializing=false; transmitting=false; scanReady=false;
        queue.clear(); deliveries.clear(); relayHeard.clear(); signatures.clear(); pendingAlerts.clear();
        currentBurst=null; pendingRotations=0; enableFailures=0;
        stateText="Stopped";
        if(wasRunning) note("Bluetooth stopped.");
        changed();
    }

    // ---- Sending ----
    /** Queue a text broadcast from this phone. Returns false if not running or the queue is full. */
    boolean broadcast(String text) {
        if(!running) { note("Start Bluetooth first."); return false; }
        if(queue.size()>=16) { note("Send queue full. Wait and retry."); return false; }
        queue.add(one(withHops(Protocol.text(sender,random.nextInt(),text),text))); pump(); return true;
    }
    /** Alert codes and check-in answers get a hop limit; other test text is sent as-is. */
    private static byte[] withHops(byte[] packet,String text) {
        if(AlertPlayer.parse(text)==null && !SAFE_CODE.equals(text.trim())) return packet;
        try { return Protocol.withTtl(packet,Protocol.DEFAULT_TTL); } catch(IllegalArgumentException e) { return packet; }
    }
    private static byte[][] one(byte[] packet) { return new byte[][]{packet}; }
    /** Send an alert code; official codes are signed if this phone holds an authority key. */
    boolean sendAlert(String text) {
        if(!SignedAlerts.needsAuthority(text)) return broadcast(text);
        if(!PhoneAuthority.exists()) {
            note("Sent "+text+" UNSIGNED: other phones show it as unverified and stay silent. Create an authority key to sign.");
            return broadcast(text);
        }
        if(!running) { note("Start Bluetooth first."); return false; }
        if(queue.size()>=16) { note("Send queue full. Wait and retry."); return false; }
        try {
            SharedPreferences st=getSharedPreferences("authority",MODE_PRIVATE);
            long last=st.getLong("lastId",0), id=Math.max(System.currentTimeMillis()/1000,last+1)&0xffffffffL;
            st.edit().putLong("lastId",id).apply();
            byte[] sig=PhoneAuthority.sign(SignedAlerts.message(sender,(int)id,text));
            byte[][] frags=SignedAlerts.fragments(sender,(int)id,sig);
            byte[][] burst=new byte[1+frags.length][];
            burst[0]=withHops(Protocol.text(sender,(int)id,text),text); System.arraycopy(frags,0,burst,1,frags.length);
            queue.add(burst); note("SIGNED "+text+" with this phone's authority key"); pump(); return true;
        } catch(GeneralSecurityException e) { note("Signing failed: "+e.getMessage()); return false; }
    }
    private void pump() {
        if(!running || initializing || advertisingSet==null || transmitting || (!continuous && queue.isEmpty())) return;
        do {
            currentBurst=queue.isEmpty()?one(Protocol.beacon(sender,beaconSequence++,deviceLabel)):queue.remove();
            burstIndex=0; currentData=currentBurst[0];
            Protocol.Packet packet=Protocol.decode(currentData);
            // Relayed packets keep the origin's sender ID; only our own tests go through delivery tracking.
            if(packet.kind!=Protocol.TEST || packet.sender!=sender || deliveries.canTransmit(packet.id,SystemClock.elapsedRealtime())) break;
            currentData=null; currentBurst=null;
        } while(!queue.isEmpty());
        if(currentData==null && continuous) { currentBurst=one(Protocol.beacon(sender,beaconSequence++,deviceLabel)); currentData=currentBurst[0]; burstIndex=0; }
        if(currentData==null) return;
        transmitting=true;
        try { advertisingSet.setAdvertisingData(new AdvertiseData.Builder().addManufacturerData(Protocol.COMPANY,currentData).build()); }
        catch(Exception e) { note("Broadcast error: "+e.getMessage()); finishTransmission(); }
    }
    private void transmissionStarted(int epoch) {
        if(!transmitting) return;
        final byte[][] burst=currentBurst;
        Protocol.Packet packet=Protocol.decode(burst[0]);
        if(burst.length>1) rotate(epoch,burst);
        if(packet.kind==Protocol.TEST && packet.sender!=sender) {
            relayCount++; note(String.format(Locale.US,"RELAY “%s” · origin %08X · %d hops left",packet.alertText(),packet.sender,packet.ttl()));
        } else if(packet.kind==Protocol.TEST) {
            DeliveryTracker.Pending pending=deliveries.transmitted(packet.id,SystemClock.elapsedRealtime());
            if(pending.attempts==1) {
                txCount++; note("TX “"+packet.alertText()+"” · collecting replies from all peers");
                handler.postDelayed(()-> {
                    if(generation==epoch && deliveries.expire(packet.id)) note("No reply after 20 seconds and up to 3 broadcasts.");
                },DeliveryTracker.WINDOW_MS);
            } else note("Retry “"+packet.alertText()+"” · attempt "+pending.attempts);
            handler.postDelayed(()-> {
                if(running && generation==epoch && deliveries.retry(packet.id,SystemClock.elapsedRealtime()) && queue.size()<16) {
                    queue.add(burst); pump();
                }
            },5000);
        } else if(packet.kind==Protocol.ACK) note("TX acknowledgment");
        else { beaconCount++; changed(); }
        handler.postDelayed(()-> {
            if(running && generation==epoch && transmitting && currentBurst==burst) {
                if(continuous) finishTransmission();
                else try { beaconAdvertising=false; advertisingSet.enableAdvertising(false,0,0); }
                catch(Exception e) { note("Stop broadcast: "+e.getMessage()); stopRadio(false); }
            }
        },burst.length>1?Math.max(3000,burst.length*ROTATE_MS*BURST_CYCLES):continuous?500:3000);
    }
    /** Cycle the advertised payload through the burst so every fragment goes out several times. */
    private void rotate(int epoch,byte[][] burst) {
        handler.postDelayed(()-> {
            if(!running || generation!=epoch || !transmitting || currentBurst!=burst || advertisingSet==null) return;
            burstIndex=(burstIndex+1)%burst.length; currentData=burst[burstIndex]; pendingRotations++;
            try { advertisingSet.setAdvertisingData(new AdvertiseData.Builder().addManufacturerData(Protocol.COMPANY,currentData).build()); }
            catch(Exception e) { pendingRotations--; note("Burst update error: "+e.getMessage()); }
            rotate(epoch,burst);
        },ROTATE_MS);
    }
    private void failedTransmission(int status) {
        note("Broadcast failed ("+status+"): "+advertiseError(status));
        if(continuous) { stopRadio(false); return; }
        finishTransmission();
    }
    private void finishTransmission() { currentData=null; currentBurst=null; transmitting=false; pump(); }
    private static String advertiseError(int c) {
        switch(c) {case 1:return "payload too large"; case 2:return "too many advertisers"; case 3:return "already started";
            case 4:return "Bluetooth internal error"; case 5:return "advertising unsupported";
            case 12:return "controller command disallowed while setting advertising address"; default:return "unknown error";}
    }

    // ---- Receiving ----
    private boolean autoAck() { return settings(this).getBoolean("autoAck",true); }
    private boolean relayOn() { return settings(this).getBoolean("relay",true); }
    private static String address(ScanResult r) { return r.getDevice()==null?"?":r.getDevice().getAddress(); }
    private void receive(ScanResult r) {
        if(!running || r.getScanRecord()==null) return;
        byte[] raw=r.getScanRecord().getManufacturerSpecificData(Protocol.COMPANY);
        Protocol.Packet p=Protocol.decode(raw);
        if(p==null || p.sender==sender) return;
        long now=SystemClock.elapsedRealtime();
        peers.observe(p,r.getRssi(),now);
        if(p.kind==Protocol.BEACON) { changed(); return; }
        if(p.kind==Protocol.SIG) { onSignatureFragment(p,raw); return; }
        if(p.kind==Protocol.TEST) {
            if(deliveries.first(p,now)) {
                rxCount++; note(String.format(Locale.US,"RX “%s” · %08X · %d dBm%s",p.alertText(),p.sender,r.getRssi(),p.ttl()>=0?" · "+p.ttl()+" hops left":""));
                if(SignedAlerts.needsAuthority(p.alertText())) startVerification(p,raw,r.getRssi(),address(r));
                else {
                    onAlertText(p.alertText(),p.sender,r.getRssi(),false,"unverified community message");
                    if(SAFE_CODE.equals(p.alertText())) onSafe(p.sender);
                    scheduleRelay(p,one(raw),address(r));
                }
            } else heardAgain(p,address(r));
            // A retransmission may mean our previous ACK was lost. Display once,
            // but ACK it again at a bounded rate instead of silently discarding it.
            if(autoAck() && queue.size()<16 && deliveries.replyDue(p,now)) {
                deliveries.replied(p,now); final byte[] ack=Protocol.ack(sender,p.id,p.sender);
                PendingAlert waiting=pendingAlerts.get(sigKey(p.sender,p.id));
                if(waiting!=null) waiting.ack=ack; // sent once the signature check (and relay) is done
                else if(relayHeard.containsKey(p.key())) {
                    // Spreading the alert matters more than confirming it: ACK after our relay is queued.
                    final int epoch=generation;
                    handler.postDelayed(()-> { if(running && generation==epoch && queue.size()<16) { queue.add(one(ack)); pump(); } },RELAY_MIN_MS+RELAY_JITTER_MS+20);
                } else { queue.addFirst(one(ack)); pump(); }
            }
        } else if(p.recipient()==sender) {
            Long elapsed=deliveries.acknowledge(p.id,p.sender,now);
            if(elapsed!=null) { ackCount++; note(String.format(Locale.US,"Reply from %08X · %d ms · %d dBm",p.sender,elapsed,r.getRssi())); }
        }
    }

    /**
     * Flood relay: rebroadcast each alert once, unchanged (same origin sender + message ID),
     * so every phone's first-seen cache recognises copies and loops die out. A random delay
     * spreads relays out, and we skip ours if enough nearby phones already relayed it.
     */
    private void scheduleRelay(Protocol.Packet p,byte[][] burst,String from) {
        boolean relayable=AlertPlayer.parse(p.alertText())!=null || SAFE_CODE.equals(p.alertText());
        if(!relayOn() || burst==null || burst[0]==null || !relayable) return;
        int ttl=p.ttl()<0?Protocol.DEFAULT_TTL:p.ttl();
        if(ttl==0) { note("Not relayed · hop limit reached for “"+p.alertText()+"”"); return; }
        final String key=p.key(); final byte[][] copy=burst.clone(); final int epoch=generation;
        // Count the hop down; signatures don't cover this byte, so the signature pieces stay valid.
        try { copy[0]=Protocol.withTtl(burst[0],ttl-1); } catch(IllegalArgumentException e) { /* no room: relay unchanged */ }
        if(relayHeard.size()>=64) relayHeard.clear();
        Set<String> sources=new HashSet<>(); sources.add(from); relayHeard.put(key,sources);
        handler.postDelayed(()-> {
            Set<String> heardFrom=relayHeard.remove(key);
            int heard=heardFrom==null?0:heardFrom.size()-1; // relays heard, not counting the first source
            if(!running || generation!=epoch || !relayOn()) return;
            if(heard>=RELAY_SUPPRESS) { note("Relay skipped · "+heard+" nearby copies already"); return; }
            if(queue.size()>=16) { note("Relay dropped · send queue full"); return; }
            queue.add(copy); pump();
        },RELAY_MIN_MS+random.nextInt(RELAY_JITTER_MS));
    }
    private void heardAgain(Protocol.Packet p,String from) {
        Set<String> sources=relayHeard.get(p.key());
        if(sources!=null) sources.add(from);
    }

    // ---- Alerts, banner, check-ins ----
    private void onAlertText(String text,int from,int rssi,boolean verified,String trust) {
        AlertPlayer.Parsed parsed=AlertPlayer.parse(text);
        if(parsed==null) return;
        boolean played=alerts.play(parsed);
        AlertPlayer.Alert a=parsed.alert;
        note("ALERT "+a.code+" · "+a.title+(played?"":" (repeat, not replayed)"));
        if(!played && bannerVisible) return; // repeat or lower priority: keep current banner
        String time=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date());
        banner.code=a.code; banner.tone=a.tone; banner.title=(verified?"✓ ":"")+a.title.toUpperCase(Locale.US);
        banner.body=a.text+(a.extra.isEmpty()?"":"\n\n"+a.extra);
        banner.meta=(parsed.detail.isEmpty()?"":parsed.detail+"\n")+String.format(Locale.US,"%s · origin phone %08X · nearest copy %d dBm · %s",time,from,rssi,trust);
        banner.showOk=a.code.equals("CHK"); banner.okSent=false;
        if(banner.showOk) safe.clear();
        bannerVisible=true; changed();
        if(!uiVisible) notifyAlert(a,parsed.detail);
    }

    // ---- Authority signatures (see SignedAlerts) ----
    private static String sigKey(int sender,int id) { return sender+":"+id; }
    private boolean playUnsigned() { return settings(this).getBoolean("playUnsigned",false); }
    private void onSignatureFragment(Protocol.Packet p,byte[] raw) {
        String key=sigKey(p.sender,p.id);
        SignedAlerts.Collector c=signatures.get(key);
        if(c==null) {
            if(signatures.size()>=32) signatures.remove(signatures.keySet().iterator().next());
            c=new SignedAlerts.Collector(); signatures.put(key,c);
        }
        if(c.add(p,raw) && c.complete() && pendingAlerts.containsKey(key)) resolve(key);
    }
    /** An official alert arrived: hold it until its signature fragments are in (or time out). */
    private void startVerification(Protocol.Packet p,byte[] raw,int rssi,String from) {
        String key=sigKey(p.sender,p.id);
        pendingAlerts.put(key,new PendingAlert(p,raw.clone(),rssi,from));
        SignedAlerts.Collector c=signatures.get(key);
        if(c!=null && c.complete()) { resolve(key); return; }
        final int epoch=generation;
        handler.postDelayed(()-> { if(generation==epoch) resolve(key); },VERIFY_TIMEOUT_MS);
    }
    private void resolve(String key) {
        PendingAlert pa=pendingAlerts.remove(key);
        if(pa==null) return;
        Protocol.Packet p=pa.packet; String text=p.alertText();
        SignedAlerts.Collector c=signatures.get(key);
        boolean complete=c!=null && c.complete();
        SignedAlerts.Authority who=complete?SignedAlerts.verify(trusted,p.sender,p.id,text,c.signature()):null;
        if(who!=null) {
            String fp=SignedAlerts.fingerprint(who.key);
            SharedPreferences replay=getSharedPreferences("replay",MODE_PRIVATE);
            long id=Integer.toUnsignedLong(p.id), last=replay.getLong(fp,-1);
            if(id<=last) {
                note("REJECTED replayed "+text+" · signed by "+who.name+" but older than its last alert");
            } else {
                replay.edit().putLong(fp,id).apply();
                note("VERIFIED "+text+" · signed by "+who.name+" ["+fp+"]");
                onAlertText(text,p.sender,pa.rssi,true,"VERIFIED · "+who.name);
                byte[][] burst=new byte[1+SignedAlerts.FRAGMENTS][];
                burst[0]=pa.raw; System.arraycopy(c.raw,0,burst,1,SignedAlerts.FRAGMENTS);
                scheduleRelay(p,burst,pa.from); // pass the signature on with the alert
            }
        } else {
            String why=complete?"invalid signature":"no signature";
            if(playUnsigned()) {
                note("UNVERIFIED "+text+" · "+why+" · played anyway (testing mode)");
                onAlertText(text,p.sender,pa.rssi,false,"UNVERIFIED ("+why+") · testing mode");
                scheduleRelay(p,one(pa.raw),pa.from);
            } else {
                note("UNVERIFIED "+text+" · "+why+" · not sounded or relayed");
                showUnverified(text,p.sender,pa.rssi,why);
            }
        }
        if(pa.ack!=null) {
            final byte[] ack=pa.ack; final int epoch=generation;
            handler.postDelayed(()-> { if(running && generation==epoch && queue.size()<16) { queue.add(one(ack)); pump(); } },RELAY_MIN_MS+RELAY_JITTER_MS+20);
        }
    }
    /** Silent grey banner: people can still see it, but it raises no alarm and is not passed on. */
    private void showUnverified(String text,int from,int rssi,String why) {
        AlertPlayer.Parsed parsed=AlertPlayer.parse(text);
        if(parsed==null) return;
        if(bannerVisible && banner.title.startsWith("✓")) return; // never cover a verified alert
        String time=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date());
        banner.code=UNVERIFIED; banner.tone=AlertPlayer.CALM; banner.showOk=false; banner.okSent=false;
        banner.title="UNVERIFIED: "+parsed.alert.title.toUpperCase(Locale.US);
        banner.body="A nearby phone broadcast this official alert without a valid authority signature, so it was not sounded or passed on. It may be a test or a fake. Follow official alerts.";
        banner.meta=String.format(Locale.US,"%s · origin phone %08X · %d dBm · %s",time,from,rssi,why);
        bannerVisible=true; changed();
    }
    /** Heads-up notification; urgent alerts also open the app over the lock screen. */
    private void notifyAlert(AlertPlayer.Alert a,String detail) {
        PendingIntent open=openApp();
        Notification.Builder b=new Notification.Builder(this,CH_ALERTS).setSmallIcon(icon())
            .setContentTitle(a.title).setContentText(a.text)
            .setStyle(new Notification.BigTextStyle().bigText(a.text+(detail.isEmpty()?"":"\n"+detail)+"\nUnverified alert from a nearby phone."))
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open).setAutoCancel(true);
        if(a.tone==AlertPlayer.URGENT) b.setFullScreenIntent(open,true);
        getSystemService(NotificationManager.class).notify(NOTE_ALERT,b.build());
    }
    void dismissBanner() {
        alerts.stopPlayback(); bannerVisible=false; banner.code="";
        getSystemService(NotificationManager.class).cancel(NOTE_ALERT); changed();
    }
    /** Answer a safety check: broadcast "OK" (relayed like an alert, but silent). */
    void markSafe() {
        if(!broadcast(SAFE_CODE)) return;
        banner.okSent=true; note("You marked yourself safe"); changed();
    }
    /** Send a check-in and show a banner that collects the answers. */
    void sendCheck() {
        if(!broadcast("CHK")) return;
        alerts.stopPlayback(); safe.clear();
        banner.code=CHECK_SENT; banner.tone=AlertPlayer.CALM; banner.title="SAFETY CHECK SENT";
        banner.body="Nearby phones were asked to tap \"I'm OK\". Answers appear here as they arrive.";
        banner.meta=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+" · sent from this phone";
        banner.showOk=false; bannerVisible=true; changed();
    }
    private void onSafe(int from) {
        if(!safe.add(from)) return;
        if(safe.size()>256) safe.remove(safe.iterator().next());
        note(String.format(Locale.US,"SAFE · %08X is OK (%d total)",from,safe.size()));
    }
    boolean alarmMuted() { return alerts.alarmMuted(); }
}
