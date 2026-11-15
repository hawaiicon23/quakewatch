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
 * The one Bluetooth radio for the whole app: chat, earthquake alerts, test messages,
 * message relays, peer beacons and continuous range tests. Runs as a foreground service
 * so everything keeps working with the screen off, the app in the background or swiped
 * away. Activities only render this service's state (same process, main thread).
 *
 * Radio model: one legacy advertising set that stays enabled for the whole session and one
 * scan that is never restarted (restarting triggers Android scan throttling; enabling and
 * disabling per packet triggers HCI 12 on some controllers). Every "slot" (120-150 ms) the
 * scheduler picks the next payload by priority and only changes the advertising data:
 *   1. earthquake alerts and their signature fragments
 *   2. acknowledgments (chat ACKs share slots with chat data so neither starves)
 *   3. chat data (BC/2, sent in order so receivers can show text as it arrives)
 *   4. message relays and test messages
 *   5. presence: named beacon + chat hello (or 0.5 s beacons in continuous test mode)
 */
public class MeshService extends Service implements ChatEngine.Host, BulkImages.Host, DataLinks.Host {
    static final String ACTION_START="dev.blx.btprobe.START", ACTION_STOP="dev.blx.btprobe.STOP", ACTION_RESTART="dev.blx.btprobe.RESTART";
    static final String EXTRA_CONTINUOUS="continuous", EXTRA_AUTO="auto", EXTRA_AUTO_REASON="autoReason";
    static final String SAFE_CODE="OK", CHECK_SENT="CHK_SENT", UNVERIFIED="UNVERIFIED";
    private static final int NOTE_RUNNING=1, NOTE_ALERT=2, NOTE_CHAT=3;
    private static final String CH_RUNNING="mesh", CH_ALERTS="alerts", CH_CHAT="chat";
    private static final int CHAT_SLOT_MS=120, SLOT_MS=150, BEACON_MS=2000, CONTINUOUS_BEACON_MS=500;
    private static final int RELAY_SUPPRESS=3, RELAY_MIN_MS=100, RELAY_JITTER_MS=400;
    private static final int BURST_SLOT_MS=200, BURST_CYCLES=5, VERIFY_TIMEOUT_MS=8000;
    private static final int P_ALERT=0, P_ACK=1, P_RELAY=2, P_TEST=3;

    // ---------------------------------------------------------------- UI link
    private static MeshService instance;
    private static final List<Runnable> listeners=new ArrayList<>();
    private static boolean changePosted;
    private static final Handler main=new Handler(Looper.getMainLooper());
    static final ArrayDeque<String> lines=new ArrayDeque<>();
    /** Set by activities while visible; alert/chat notifications are skipped for the visible screen. */
    static boolean mainVisible, chatVisible;

    static MeshService get() { return instance; }
    static void addListener(Runnable l) { if(!listeners.contains(l)) listeners.add(l); }
    static void removeListener(Runnable l) { listeners.remove(l); }
    static void changed() {
        if(changePosted) return;
        changePosted=true;
        main.post(()-> { changePosted=false; for(Runnable l:new ArrayList<>(listeners)) l.run(); });
    }
    static void note(String text) {
        String time=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date());
        android.util.Log.i("BTProbe",text); // also in logcat, for debugging with adb
        lines.addFirst(time+"  "+text); while(lines.size()>80) lines.removeLast(); changed();
    }
    static void clearLog() { lines.clear(); changed(); }
    static SharedPreferences settings(Context c) { return c.getSharedPreferences("settings",MODE_PRIVATE); }
    static boolean messageRelayOn(Context c) { return c.getSharedPreferences("identity",MODE_PRIVATE).getBoolean("relay",false); }

    // ---------------------------------------------------------------- alert banner / check-ins
    static final class Banner { String code="", title="", body="", meta=""; int tone; boolean showOk, okSent; }
    final Banner banner=new Banner();
    boolean bannerVisible;
    final Set<Integer> safe=new LinkedHashSet<>();

    // ---------------------------------------------------------------- engine state
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final SecureRandom random=new SecureRandom();
    int sender; String deviceLabel;
    boolean running, ready, continuous;
    int txCount, rxCount, ackCount, relayCount, beaconCount;
    String stateText="Stopped";
    final PeerStats peers=new PeerStats();
    ChatEngine chat;
    BulkImages bulk;
    DataLinks data;
    int dataPacketsReceived,dataAlertsReceived;
    interface VoiceListener {void received(int peer,byte[] pcm);}
    volatile VoiceListener voiceListener;
    QuakeMonitor quake;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private AdvertisingSet set;
    private AdvertisingSetCallback setCallback;
    private int generation, beaconSequence;
    private boolean scanning, dataPending, idleWait;
    private Runnable pendingTick;
    private byte[] onAir;
    private Runnable afterDataSet;
    private Runnable dataTimeout;
    private long lastPresence, powerRevision, lastChatHello;
    private int slotCounter;
    private final DeliveryTracker deliveries=new DeliveryTracker();
    private final RelayPolicy relayPolicy=new RelayPolicy();
    private PowerManager.WakeLock wakeLock;
    private AlertPlayer alerts;
    private boolean wantRunning;
    boolean needsForegroundRestart;

    /** A broadcast that stays on the schedule until `until`: one packet, or a rotating burst. */
    private static final class Item {
        final byte[][] payloads; final int priority; final long until; int next; boolean aired; Runnable onFirstAir;
        Item(int priority,long until,byte[]... payloads) { this.priority=priority; this.until=until; this.payloads=payloads; }
    }
    private final ArrayList<Item> items=new ArrayList<>();
    private int itemCursor;

    /** Alert relays waiting for their random delay -> Bluetooth addresses heard broadcasting them. */
    private final Map<String,Set<String>> relayHeard=new HashMap<>();
    private List<SignedAlerts.Authority> trusted=new ArrayList<>();
    private final LinkedHashMap<String,SignedAlerts.Collector> signatures=new LinkedHashMap<>();
    private final Map<String,PendingAlert> pendingAlerts=new HashMap<>();
    private static final class PendingAlert {
        final Protocol.Packet packet; final byte[] raw; final int rssi; final String from; byte[] ack;
        PendingAlert(Protocol.Packet p,byte[] r,int s,String f) { packet=p; raw=r; rssi=s; from=f; }
    }

    private final BroadcastReceiver bluetoothState=new BroadcastReceiver() {
        @Override public void onReceive(Context c,Intent i) {
            int st=i.getIntExtra(BluetoothAdapter.EXTRA_STATE,-1);
            if(st==BluetoothAdapter.STATE_TURNING_OFF || st==BluetoothAdapter.STATE_OFF) {
                if(running) stopRadio(false);
                if(wantRunning && !"Waiting for Bluetooth".equals(stateText)) { stateText="Waiting for Bluetooth"; note("Bluetooth turned off · will resume when it is back on"); }
            } else if(st==BluetoothAdapter.STATE_ON && wantRunning && !running) {
                note("Bluetooth is on · starting radio"); handler.postDelayed(()-> { if(wantRunning && !running) startRadio(); },1000);
            }
        }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        instance=this;
        sender=DeviceIdentity.id(this); deviceLabel=DeviceIdentity.name(this);
        BluetoothManager manager=getSystemService(BluetoothManager.class);
        adapter=manager==null?null:manager.getAdapter();
        alerts=new AlertPlayer(this);
        chat=new ChatEngine(this,this);
        quake=new QuakeMonitor(this);
        loadTrusted();
        registerReceiver(bluetoothState,new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        NotificationManager nm=getSystemService(NotificationManager.class);
        NotificationChannel run=new NotificationChannel(CH_RUNNING,"Bluetooth listening",NotificationManager.IMPORTANCE_LOW);
        run.setDescription("Shown while the app listens for chat and alerts in the background");
        NotificationChannel alertCh=new NotificationChannel(CH_ALERTS,"Earthquake alerts",NotificationManager.IMPORTANCE_HIGH);
        alertCh.setDescription("Alerts received from nearby phones");
        alertCh.setSound(null,null); alertCh.enableVibration(false); // AlertPlayer plays voice + vibration
        alertCh.setBypassDnd(true); alertCh.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationChannel chatCh=new NotificationChannel(CH_CHAT,"Nearby chat",NotificationManager.IMPORTANCE_HIGH);
        chatCh.setDescription("Messages from nearby phones");
        nm.createNotificationChannel(run); nm.createNotificationChannel(alertCh); nm.createNotificationChannel(chatCh);
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

    // ---------------------------------------------------------------- lifecycle
    private boolean scanBlockedInBackground() {
        return Build.VERSION.SDK_INT>=29 && Build.VERSION.SDK_INT<=30
            && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)!=PackageManager.PERMISSION_GRANTED;
    }

    // Entry is permission-gated; cleanup and async work handle permission revocation.
    @android.annotation.SuppressLint("MissingPermission")
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        String action=intent==null||intent.getAction()==null?ACTION_START:intent.getAction(); // null = sticky restart
        if(ACTION_STOP.equals(action)) {
            settings(this).edit().putBoolean("autoStart",true).putBoolean("radioEnabled",false).apply();
            wantRunning=false;stopRadio(true);
            try{goForeground();quake.start();}catch(RuntimeException e){note("Cannot keep sensing active: "+e.getMessage());stopSelf();return START_NOT_STICKY;}
            stateText="Sensing active · Bluetooth paused";changed();return START_STICKY;
        }
        settings(this).edit().putBoolean("autoStart",true).apply();
        boolean auto=intent==null || intent.getBooleanExtra(EXTRA_AUTO,false);
        wantRunning=!auto||settings(this).getBoolean("radioEnabled",true);
        if(!auto)settings(this).edit().putBoolean("radioEnabled",true).apply();
        if(auto) {
            note("Started automatically ("+(intent==null?"restarted by Android":intent.getStringExtra(EXTRA_AUTO_REASON))+").");
            if(scanBlockedInBackground()) {
                needsForegroundRestart=true;
                note("Android 10-11 block Bluetooth scanning for apps started in the background. Open the app once (tap the notification), or allow location \"All the time\".");
            }
        } else if(needsForegroundRestart) {
            needsForegroundRestart=false;
            if(running && scanning) { try { scanner.stopScan(scans); } catch(Exception ignored) {} scanning=false; startScan(); note("Scanning resumed."); }
        }
        try { goForeground(); quake.start(); }
        catch(RuntimeException e) { note("Cannot run in the background: "+e.getMessage()); stopSelf(); return START_NOT_STICKY; }
        if(!wantRunning){stateText="Sensing active · Bluetooth paused";changed();return START_STICKY;}
        boolean cont=intent!=null && intent.getBooleanExtra(EXTRA_CONTINUOUS,false);
        if(ACTION_RESTART.equals(action) && running) stopRadio(false);  // e.g. new power setting
        else if(intent!=null && intent.hasExtra(EXTRA_CONTINUOUS) && cont!=continuous) { continuous=cont; note(continuous?"Continuous test ON · beacon every ~0.5 s":"Continuous test OFF"); }
        if(!running) startRadio();
        if(!running && adapter!=null && !adapter.isEnabled() && hasPermissions()) {
            stateText="Waiting for Bluetooth"; note("Waiting for Bluetooth to turn on…");
            return START_STICKY;
        }
        if(!running) { stateText="Sensing active · Bluetooth unavailable";changed();return START_STICKY; }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        wantRunning=false; try { unregisterReceiver(bluetoothState); } catch(IllegalArgumentException ignored) {}
        stopMesh(false); quake.stop(); chat.close(); alerts.release(); instance=null; changed(); super.onDestroy();
    }

    private int icon() { return android.R.drawable.stat_sys_data_bluetooth; }
    private PendingIntent open(Class<?> activity,int request) {
        Intent i=new Intent(this,activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this,request,i,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private void goForeground() {
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MeshService.class).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CH_RUNNING).setSmallIcon(icon())
            .setContentTitle(!wantRunning?"Earthquake sensing active · Bluetooth paused":needsForegroundRestart?"Tap to resume chat and alerts":"Listening for chat and earthquake alerts")
            .setContentText(!wantRunning?"Accelerometer active. Bluetooth delivery is paused.":needsForegroundRestart?"Android needs the app opened once after a restart (or location \"Allow all the time\")."
                :"Nearby Bluetooth · relaying alerts. Tap to open.")
            .setOngoing(true).setContentIntent(open(MainActivity.class,0))
            .addAction(new Notification.Action.Builder(null,"Pause Bluetooth",stop).build()).build();
        if(Build.VERSION.SDK_INT>=29) {
            int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if(Build.VERSION.SDK_INT<31) type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION; // BLE scan = location on 10-11
            startForeground(NOTE_RUNNING,n,type);
        } else startForeground(NOTE_RUNNING,n);
        if(wakeLock==null) {
            wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"btprobe:mesh");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire();
    }
    private void stopMesh(boolean byUser) {
        stopRadio(byUser);
        if(wakeLock!=null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
    }
    boolean hasPermissions() {
        String[] perms=Build.VERSION.SDK_INT>=31
            ? new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.BLUETOOTH_CONNECT}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for(String p:perms) if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    // ---------------------------------------------------------------- radio
    private final ScanCallback scans=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult r) { handler.post(()->receive(r)); }
        @Override public void onBatchScanResults(List<ScanResult> results) { for(ScanResult r:results) handler.post(()->receive(r)); }
        @Override public void onScanFailed(int code) { handler.post(()-> { if(running) { note("Scan failed ("+code+"). Restarting the radio in 5 s."); stopRadio(false); handler.postDelayed(()-> { if(wantRunning && !running) startRadio(); },5000); } }); }
    };
    // Entry is permission-gated; cleanup and async work handle permission revocation.
    @android.annotation.SuppressLint("MissingPermission")
    private void startRadio() {
        if(adapter==null) { note("This device has no Bluetooth adapter."); return; }
        if(!hasPermissions()) { note("Nearby devices permission is missing. Open the app and tap Start Bluetooth."); return; }
        if(!adapter.isEnabled()) { note("Bluetooth is off."); return; }
        deviceLabel=DeviceIdentity.name(this);
        final int request; final long revision;
        try { request=RadioPower.requested(this); revision=RadioPower.revision(this); }
        catch(RuntimeException e) { note("Power setting invalid: "+e.getMessage()); return; }
        powerRevision=revision;
        try {
            scanner=adapter.getBluetoothLeScanner(); advertiser=adapter.getBluetoothLeAdvertiser();
            if(scanner==null || advertiser==null) { note("Scanning and BLE advertising must both be supported."); return; }
            running=true; ready=false; generation++; stateText="Starting radio"; changed();
            final int epoch=generation; final BluetoothLeAdvertiser owner=advertiser;
            onAir=Protocol.beacon(sender,beaconSequence++,deviceLabel);
            // Allocate the advertising address BEFORE starting the one continuous scan, and keep the set
            // enabled for the whole session (enable/disable while scanning triggers HCI 12 on some phones).
            setCallback=new AdvertisingSetCallback() {
                boolean valid() { return running && generation==epoch && setCallback==this; }
                @Override public void onAdvertisingSetStarted(AdvertisingSet next,int power,int status) {
                    if(!valid()) { try { owner.stopAdvertisingSet(this); } catch(SecurityException ignored) {} return; }
                    if(status!=0) {
                        String r="Request "+RadioPower.signed(request)+" dBm rejected (status "+status+")";
                        RadioPower.failure(MeshService.this,revision,r); note(r); stopRadio(false); return;
                    }
                    set=next;
                    RadioPower.report(MeshService.this,revision,request,power,"Background radio");
                    note("Radio ready · requested "+RadioPower.signed(request)+" dBm · controller "+(power==127?"unavailable":RadioPower.signed(power)+" dBm"));
                    startScan();
                    if(!running) return;
                    ready=true; data=new DataLinks(MeshService.this);data.start(adapter);bulk=new BulkImages(MeshService.this,MeshService.this);bulk.start(adapter); quake.start(); stateText="Listening nearby"; lastPresence=SystemClock.elapsedRealtime();
                    note("Listening for chat, alerts and BT Probe peers · also in the background");
                    note(trusted.size()+" trusted alert authorities. Official alerts need their signature.");
                    tick();
                }
                @Override public void onAdvertisingDataSet(AdvertisingSet next,int status) {
                    if(!valid()) return;
                    if(dataTimeout!=null) handler.removeCallbacks(dataTimeout);
                    dataTimeout=null;
                    dataPending=false;
                    if(status!=0) { onAir=null; note("Broadcast update failed ("+status+")"); }
                    Runnable r=afterDataSet; afterDataSet=null;
                    if(r!=null && status==0) r.run(); else scheduleTick(40);
                }
                @Override public void onAdvertisingEnabled(AdvertisingSet next,boolean enabled,int status) {
                    if(valid() && status!=0) note("Radio enable error ("+status+")");
                }
            };
            AdvertiseData first=new AdvertiseData.Builder().addManufacturerData(Protocol.COMPANY,onAir).build();
            // duration 0 / maxEvents 0: advertise until stopped.
            advertiser.startAdvertisingSet(RadioPower.parameters(request),first,null,null,null,0,0,setCallback);
            handler.postDelayed(()-> {
                if(running && generation==epoch && !ready) {
                    String r="Radio startup timed out. Retrying in 5 s.";
                    RadioPower.failure(this,revision,r); note(r); stopRadio(false);
                    handler.postDelayed(()-> { if(wantRunning && !running) startRadio(); },5000);
                }
            },8000);
        } catch(Exception e) {
            String r="Power/radio request failed: "+e.getMessage();
            RadioPower.prefs(this).edit().putString("last_status",r).apply(); note(r); stopRadio(false);
        }
    }
    // Entry is permission-gated; cleanup and async work handle permission revocation.
    @android.annotation.SuppressLint("MissingPermission")
    private void startScan() {
        try {
            List<ScanFilter> filters=new ArrayList<>();
            // BT/1 (tests, alerts, relays, beacons) and BC/* (chat v1 and v2).
            filters.add(new ScanFilter.Builder().setManufacturerData(Protocol.COMPANY,Protocol.PREFIX,new byte[]{-1,-1,-1}).build());
            filters.add(new ScanFilter.Builder().setManufacturerData(Protocol.COMPANY,new byte[]{'B','C'},new byte[]{-1,-1}).build());
            scanner.startScan(filters,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scans);
            scanning=true;
        } catch(Exception e) { note("Scan failed: "+e.getMessage()); stopRadio(false); }
    }
    // Entry is permission-gated; cleanup and async work handle permission revocation.
    @android.annotation.SuppressLint("MissingPermission")
    void stopRadio(boolean byUser) {
        if(bulk!=null){bulk.stop();bulk=null;}
        if(data!=null){data.stop();data=null;}
        boolean was=running; running=false; ready=false; generation++; handler.removeCallbacksAndMessages(null);
        try { if(scanning && scanner!=null) scanner.stopScan(scans); } catch(Exception ignored) {}
        try { if(setCallback!=null && advertiser!=null) advertiser.stopAdvertisingSet(setCallback); } catch(Exception ignored) {}
        set=null; setCallback=null; scanning=false; dataPending=false; pendingTick=null; idleWait=false; afterDataSet=null; dataTimeout=null;
        items.clear(); deliveries.clear(); relayPolicy.clear(); relayHeard.clear(); signatures.clear(); pendingAlerts.clear();
        if(byUser) continuous=false;
        chat.radioStopped();
        stateText="Stopped";
        if(was) note("Bluetooth stopped; accelerometer sensing continues.");
        if(wantRunning)handler.postDelayed(()->{if(wantRunning&&!running)startRadio();},5000);
        changed();
    }
    /** Re-apply settings such as transmit power by restarting the radio session. */
    void restartRadio() { if(running) { stopRadio(false); handler.postDelayed(()-> { if(wantRunning && !running) startRadio(); },400); } }

    // ---------------------------------------------------------------- scheduler
    private void scheduleTick(long delay) { scheduleTick(delay,false); }
    /** idle=true: an interruptible wait (nothing to send); false: hold the current slot on air. */
    private void scheduleTick(long delay,boolean idle) {
        if(pendingTick!=null || !running) return;
        final int epoch=generation; idleWait=idle;
        pendingTick=()-> { pendingTick=null; idleWait=false; if(generation==epoch) tick(); };
        handler.postDelayed(pendingTick,delay);
    }
    /** New work (chat, ACK, alert): send at once if idle, else at the end of the current slot. No polling. */
    @Override public void wake() {
        if(!ready || dataPending) return;
        if(pendingTick==null) scheduleTick(0);
        else if(idleWait) { handler.removeCallbacks(pendingTick); pendingTick=null; scheduleTick(0); }
    }
    private void tick() {
        if(!ready || set==null || dataPending) return;
        long now=SystemClock.elapsedRealtime(); slotCounter++;
        chat.finishTransfers(); // Expire partial delivery even when higher-priority broadcasts occupy the radio.
        items.removeIf(i->now>=i.until && i.aired);
        Item item=pick(P_ALERT,now);
        byte[] payload=null; int slot=SLOT_MS; Runnable firstAir=null;
        if(item!=null) { payload=item.payloads[item.next]; item.next=(item.next+1)%item.payloads.length; slot=item.payloads.length>1?BURST_SLOT_MS:SLOT_MS; if(!item.aired) { item.aired=true; firstAir=item.onFirstAir; } }
        if(payload==null && quake!=null) payload=quake.broadcastPacket();
        if(payload==null) {
            boolean chatData=chat.hasData();
            // ACKs (legacy and chat) take at most two of every three slots while chat data waits.
            if(!chatData || slotCounter%3!=0) {
                Item ack=pick(P_ACK,now);
                if(ack!=null) { payload=ack.payloads[0]; if(!ack.aired) { ack.aired=true; firstAir=ack.onFirstAir; } }
                else if(chat.hasAck()) payload=chat.nextAck();
            }
            if(payload==null && chatData) { payload=chat.nextData(now); slot=CHAT_SLOT_MS; }
        }
        if(payload==null) {
            Item other=pick(P_RELAY,now); if(other==null) other=pick(P_TEST,now);
            if(other!=null) { payload=other.payloads[other.next]; other.next=(other.next+1)%other.payloads.length; if(!other.aired) { other.aired=true; firstAir=other.onFirstAir; } }
        }
        if(payload==null) {
            long every=continuous?CONTINUOUS_BEACON_MS:BEACON_MS;
            if(now-lastPresence>=every) {
                lastPresence=now;
                // Alternate a named BT/1 beacon (peer lists, Linux app) with a chat-capability hello.
                // No chat hello while Android blocks our scanning: others would wait for ACKs we can't send.
                if(!continuous && !needsForegroundRestart && now-lastChatHello>=2*BEACON_MS-100) { payload=ChatProtocol.hello(sender,beaconSequence++); lastChatHello=now; }
                else { payload=Protocol.beacon(sender,beaconSequence++,deviceLabel); beaconCount++; if(continuous) changed(); }
            }
        }
        if(payload==null) {
            // Nothing new: the last payload keeps advertising. Never leave chat data or ACKs repeating.
            if(onAir!=null && !isPresence(onAir)) payload=Protocol.beacon(sender,beaconSequence++,deviceLabel);
            else {
                // Sleep until the next presence beacon or pending item deadline; wake() interrupts for new work.
                long wait=(continuous?CONTINUOUS_BEACON_MS:BEACON_MS)-(now-lastPresence);
                if(chat.hasData() || chat.acksPending()) wait=Math.min(wait,100); // a chat repair or ACK repeat is due soon
                scheduleTick(Math.max(20,Math.min(1000,wait)),true); return;
            }
        }
        final int hold=slot; final Runnable announce=firstAir;
        transmit(payload,()-> { if(announce!=null) announce.run(); scheduleTick(hold); });
    }
    private boolean isPresence(byte[] b) {
        Protocol.Packet p=Protocol.decode(b); if(p!=null) return p.kind==Protocol.BEACON;
        ChatProtocol.Packet c=ChatProtocol.decode(b); return c!=null && c.kind==ChatProtocol.HELLO;
    }
    // Entry is permission-gated; cleanup and async work handle permission revocation.
    @android.annotation.SuppressLint("MissingPermission")
    private void transmit(byte[] payload,Runnable afterOnAir) {
        if(onAir!=null && Arrays.equals(onAir,payload)) { afterOnAir.run(); return; }
        onAir=payload; dataPending=true; afterDataSet=afterOnAir;
        final int epoch=generation;
        dataTimeout=()-> {
            if(generation!=epoch || !dataPending) return;
            note("Broadcast update timed out · restarting radio to discard late callbacks");
            stopRadio(false);
            handler.postDelayed(()-> { if(wantRunning && !running) startRadio(); },1000);
        };
        handler.postDelayed(dataTimeout,3000);
        try { set.setAdvertisingData(new AdvertiseData.Builder().addManufacturerData(Protocol.COMPANY,payload).build()); }
        catch(Exception e) {
            if(dataTimeout!=null) handler.removeCallbacks(dataTimeout);
            dataTimeout=null; dataPending=false; afterDataSet=null; onAir=null;
            note("Broadcast error: "+e.getMessage()); scheduleTick(100);
        }
    }
    private Item pick(int priority,long now) {
        int n=items.size();
        for(int k=0;k<n;k++) {
            Item i=items.get((itemCursor+k)%n);
            if(i.priority==priority && (now<i.until || !i.aired)) { itemCursor=(itemCursor+k+1)%Math.max(1,n); return i; }
        }
        return null;
    }
    private void schedule(Item item) {
        if(items.size()>=48) { note("Send queue full · dropped a broadcast"); return; }
        items.add(item); if(data!=null)for(byte[] raw:item.payloads)data.packet(raw);wake();
    }

    // ---------------------------------------------------------------- sending (UI API)
    /** A test or alert-code broadcast from this phone, kept on air ~3 s and retried like BT Probe. */
    boolean broadcast(String text) {
        if(!running) { note("Start Bluetooth first."); return false; }
        byte[] packet=Protocol.text(sender,random.nextInt(),text);
        if(AlertPlayer.parse(text)!=null || SAFE_CODE.equals(text.trim())) {
            try { packet=Protocol.withAlertHops(packet,Protocol.ALERT_DEFAULT_HOPS); } catch(IllegalArgumentException ignored) {}
        }
        sendTracked(packet,AlertPlayer.parse(text)!=null?P_ALERT:P_TEST); return true;
    }
    /** BT Probe relay message (RELAY_TEXT, max 3 relays) to a device ID, or 0 for everyone. */
    boolean sendRelay(String text,int target) {
        if(!running) { note("Start Bluetooth first."); return false; }
        sendTracked(Protocol.relayText(sender,random.nextInt(),target,text),P_TEST); return true;
    }
    private void sendTracked(byte[] packet,int priority) {
        final Protocol.Packet p=Protocol.decode(packet); final int epoch=generation;
        Item item=new Item(priority,SystemClock.elapsedRealtime()+3000,packet);
        item.onFirstAir=()-> {
            DeliveryTracker.Pending pending=deliveries.transmitted(p.id,SystemClock.elapsedRealtime());
            if(pending.attempts==1) {
                txCount++; note("TX “"+p.alertText()+"” · collecting replies from all peers");
                handler.postDelayed(()-> { if(generation==epoch && deliveries.expire(p.id)) note("No reply after 20 seconds and up to 3 broadcasts."); },DeliveryTracker.WINDOW_MS);
            } else note("Retry “"+p.alertText()+"” · attempt "+pending.attempts);
            handler.postDelayed(()-> {
                if(running && generation==epoch && deliveries.retry(p.id,SystemClock.elapsedRealtime())) sendTracked(packet,priority);
            },5000);
        };
        if(!deliveries.canTransmit(p.id,SystemClock.elapsedRealtime())) return;
        schedule(item);
    }
    /** Send an alert code; official codes are signed if this phone holds an authority key. */
    boolean sendAlert(String text) {
        if(!SignedAlerts.needsAuthority(text)) return broadcast(text);
        if(!PhoneAuthority.exists()) {
            note("Sent "+text+" UNSIGNED: other phones show it as unverified and stay silent. Create an authority key to sign.");
            return broadcast(text);
        }
        if(!running) { note("Start Bluetooth first."); return false; }
        try {
            SharedPreferences st=getSharedPreferences("authority",MODE_PRIVATE);
            long last=st.getLong("lastId",0), id=Math.max(System.currentTimeMillis()/1000,last+1)&0xffffffffL;
            st.edit().putLong("lastId",id).apply();
            byte[] sig=PhoneAuthority.sign(SignedAlerts.message(sender,(int)id,text));
            byte[][] frags=SignedAlerts.fragments(sender,(int)id,sig);
            byte[][] burst=new byte[1+frags.length][];
            burst[0]=Protocol.withAlertHops(Protocol.text(sender,(int)id,text),Protocol.ALERT_DEFAULT_HOPS);
            System.arraycopy(frags,0,burst,1,frags.length);
            Item item=new Item(P_ALERT,SystemClock.elapsedRealtime()+burst.length*BURST_SLOT_MS*BURST_CYCLES,burst);
            item.onFirstAir=()-> { txCount++; note("TX SIGNED “"+text+"” · alert + "+frags.length+" signature packets"); };
            schedule(item); return true;
        } catch(GeneralSecurityException|IllegalArgumentException e) { note("Signing failed: "+e.getMessage()); return false; }
    }
    private void queueAck(byte[] ack) { schedule(new Item(P_ACK,SystemClock.elapsedRealtime()+SLOT_MS,ack)); }

    // ---------------------------------------------------------------- receiving
    private static String address(ScanResult r) { return r.getDevice()==null?"?":r.getDevice().getAddress(); }
    private boolean autoAck() { return settings(this).getBoolean("autoAck",true); }
    private boolean alertRelayOn() { return settings(this).getBoolean("relay",true); }
    private boolean playUnsigned() { return settings(this).getBoolean("playUnsigned",false); }

    private void receive(ScanResult r) {
        if(!running || r.getScanRecord()==null) return;
        byte[] raw=r.getScanRecord().getManufacturerSpecificData(Protocol.COMPANY);
        if(raw==null) return;
        ChatProtocol.Packet c=ChatProtocol.decode(raw);
        if(c!=null) {
            if(c.sender!=sender){
                if((c.kind==ChatProtocol.IMAGE_HELLO||c.kind==ChatProtocol.LINK_HELLO)&&bulk!=null)bulk.observe(c.sender,((c.body[0]&255)<<8)|(c.body[1]&255),r.getDevice());
                if(c.kind==ChatProtocol.LINK_HELLO&&data!=null)data.observe(c.sender,((c.body[2]&255)<<8)|(c.body[3]&255),r.getDevice());
                chat.received(c,r.getRssi());
            }
            return;
        }
        receivePacket(raw,r.getRssi(),address(r));
    }
    private void receivePacket(byte[] raw,int rssi,String source) {
        Protocol.Packet p=Protocol.decode(raw);
        if(p==null || p.sender==sender) return;
        long now=SystemClock.elapsedRealtime();
        if(!p.relay()&&!source.startsWith("data:"))chat.observePresence(p.sender,rssi);
        if(p.kind==Protocol.SENSOR) { quake.receive(p); return; }
        // A forwarded packet's RSSI belongs to its last hop, not its origin.
        if(!source.startsWith("data:")&&(!p.relay() || p.hops==Protocol.MAX_HOPS)) peers.observe(p,rssi,now);
        if(p.relay()) { receiveRelay(p,rssi,now); return; }
        if(p.kind==Protocol.BEACON) { chat.observeBeacon(p.sender,p.text(),rssi); changed(); return; }
        if(p.kind==Protocol.SIG) { onSignatureFragment(p,raw); return; }
        if(p.kind==Protocol.TEST) {
            if(deliveries.first(p,now)) {
                rxCount++; note(String.format(Locale.US,"RX “%s” · %08X · %d dBm%s",p.alertText(),p.sender,rssi,p.alertHops()>=0?" · "+p.alertHops()+" hops left":""));
                if(SignedAlerts.needsAuthority(p.alertText())) startVerification(p,raw,rssi,source);
                else {
                    onAlertText(p.alertText(),p.sender,rssi,false,"unverified community message");
                    if(SAFE_CODE.equals(p.alertText())) onSafe(p.sender);
                    scheduleRelay(p,new byte[][]{raw},source);
                }
            } else heardAgain(p,source);
            // A retransmission may mean our previous ACK was lost: ACK again at a bounded rate.
            if(autoAck() && deliveries.replyDue(p,now)) {
                deliveries.replied(p,now); final byte[] ack=Protocol.ack(sender,p.id,p.sender);
                PendingAlert waiting=pendingAlerts.get(sigKey(p.sender,p.id));
                if(waiting!=null) waiting.ack=ack; // sent once the signature check (and relay) is done
                else if(relayHeard.containsKey(p.key())) {
                    final int epoch=generation; // spreading an alert matters more than confirming it
                    handler.postDelayed(()-> { if(running && generation==epoch) queueAck(ack); },RELAY_MIN_MS+RELAY_JITTER_MS+20);
                } else queueAck(ack);
            }
        } else if(p.kind==Protocol.ACK && p.recipient()==sender) {
            Long elapsed=deliveries.acknowledge(p.id,p.sender,now);
            if(elapsed!=null) { ackCount++; note(String.format(Locale.US,"Reply from %08X · %d ms · %d dBm",p.sender,elapsed,rssi)); }
        }
    }
    /** BT Probe message relays (kinds 4/5): deliver, acknowledge and forward with RelayPolicy. */
    private void receiveRelay(Protocol.Packet p,int rssi,long now) {
        boolean local=p.recipient()==sender, everyone=p.recipient()==0;
        if(p.kind==Protocol.RELAY_TEXT && (local || everyone)) {
            if(deliveries.first(p,now)) { rxCount++; note(String.format(Locale.US,"DELIVERED “%s” · origin %08X · %d relay(s) · last hop %d dBm",p.text(),p.sender,Protocol.MAX_HOPS-p.hops,rssi)); }
            if(autoAck() && deliveries.replyDue(p,now)) { deliveries.replied(p,now); queueAck(Protocol.relayAck(sender,p.id,p.sender)); }
        } else if(p.kind==Protocol.RELAY_ACK && local) {
            Long elapsed=deliveries.acknowledge(p.id,p.sender,now);
            if(elapsed!=null) { ackCount++; note(String.format(Locale.US,"Destination reply %08X · %d ms · %d relay(s)",p.sender,elapsed,Protocol.MAX_HOPS-p.hops)); }
        }
        if(local || !messageRelayOn(this)) return;
        final byte[] forwarded=relayPolicy.forward(p,now);
        if(forwarded==null) return;
        final int epoch=generation;
        handler.postDelayed(()-> {
            if(!running || generation!=epoch || !messageRelayOn(this)) return;
            Item item=new Item(P_RELAY,SystemClock.elapsedRealtime()+500,forwarded);
            item.onFirstAir=()-> { relayCount++; note(String.format(Locale.US,"RELAY %s · origin %08X → %08X · %d hops left",p.kind==Protocol.RELAY_ACK?"reply":"message",p.sender,p.recipient(),p.hops-1)); };
            schedule(item);
        },100+random.nextInt(251));
    }

    /**
     * Earthquake-alert flood relay: rebroadcast each alert once, unchanged except for the hop
     * byte, so every phone's first-seen cache recognises copies. Random delay; skip if enough
     * nearby phones already relayed it. Signed alerts are relayed with their signature.
     */
    private void scheduleRelay(Protocol.Packet p,byte[][] burst,String from) {
        boolean relayable=AlertPlayer.parse(p.alertText())!=null || SAFE_CODE.equals(p.alertText());
        if(!alertRelayOn() || burst==null || burst[0]==null || !relayable) return;
        int hops=p.alertHops()<0?Protocol.ALERT_DEFAULT_HOPS:p.alertHops();
        if(hops==0) { note("Not relayed · hop limit reached for “"+p.alertText()+"”"); return; }
        final String key=p.key(); final byte[][] copy=burst.clone(); final int epoch=generation;
        try { copy[0]=Protocol.withAlertHops(burst[0],hops-1); } catch(IllegalArgumentException e) { /* no room: unchanged */ }
        if(relayHeard.size()>=64) relayHeard.clear();
        Set<String> sources=new HashSet<>(); sources.add(from); relayHeard.put(key,sources);
        handler.postDelayed(()-> {
            Set<String> heardFrom=relayHeard.remove(key);
            int heard=heardFrom==null?0:heardFrom.size()-1;
            if(!running || generation!=epoch || !alertRelayOn()) return;
            if(heard>=RELAY_SUPPRESS) { note("Relay skipped · "+heard+" nearby copies already"); return; }
            long until=SystemClock.elapsedRealtime()+(copy.length>1?copy.length*BURST_SLOT_MS*BURST_CYCLES:3000);
            Item item=new Item(P_ALERT,until,copy);
            item.onFirstAir=()-> { relayCount++; note(String.format(Locale.US,"RELAY “%s” · origin %08X · %d hops left",p.alertText(),p.sender,hops-1)); };
            schedule(item);
        },RELAY_MIN_MS+random.nextInt(RELAY_JITTER_MS));
    }
    private void heardAgain(Protocol.Packet p,String from) {
        Set<String> sources=relayHeard.get(p.key());
        if(sources!=null) sources.add(from);
    }

    // ---------------------------------------------------------------- authority signatures
    private static String sigKey(int s,int id) { return s+":"+id; }
    private void onSignatureFragment(Protocol.Packet p,byte[] raw) {
        String key=sigKey(p.sender,p.id);
        SignedAlerts.Collector c=signatures.get(key);
        if(c==null) {
            if(signatures.size()>=32) signatures.remove(signatures.keySet().iterator().next());
            c=new SignedAlerts.Collector(); signatures.put(key,c);
        }
        if(c.add(p,raw) && c.complete() && pendingAlerts.containsKey(key)) resolve(key);
    }
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
            if(id<=last) note("REJECTED replayed "+text+" · signed by "+who.name+" but older than its last alert");
            else {
                replay.edit().putLong(fp,id).apply();
                note("VERIFIED "+text+" · signed by "+who.name+" ["+fp+"]");
                onAlertText(text,p.sender,pa.rssi,true,"VERIFIED · "+who.name);
                byte[][] burst=new byte[1+SignedAlerts.FRAGMENTS][];
                burst[0]=pa.raw; System.arraycopy(c.raw,0,burst,1,SignedAlerts.FRAGMENTS);
                scheduleRelay(p,burst,pa.from);
            }
        } else {
            String why=complete?"invalid signature":"no signature";
            if(playUnsigned()) {
                note("UNVERIFIED "+text+" · "+why+" · played anyway (testing mode)");
                onAlertText(text,p.sender,pa.rssi,false,"UNVERIFIED ("+why+") · testing mode");
                scheduleRelay(p,new byte[][]{pa.raw},pa.from);
            } else { note("UNVERIFIED "+text+" · "+why+" · not sounded or relayed"); showUnverified(text,p.sender,pa.rssi,why); }
        }
        if(pa.ack!=null) {
            final byte[] ack=pa.ack; final int epoch=generation;
            handler.postDelayed(()-> { if(running && generation==epoch) queueAck(ack); },RELAY_MIN_MS+RELAY_JITTER_MS+20);
        }
    }

    // ---------------------------------------------------------------- alert banner
    private final LinkedHashMap<String,Long> presentedAlerts=new LinkedHashMap<>();
    private void onAlertText(String text,int from,int rssi,boolean verified,String trust) {
        if(alertMode().equals("off"))return;
        long now=SystemClock.elapsedRealtime();String key=from+":"+text;Long last=presentedAlerts.get(key);
        if(last!=null&&now-last<60000)return;presentedAlerts.put(key,now);while(presentedAlerts.size()>64)presentedAlerts.remove(presentedAlerts.keySet().iterator().next());
        AlertPlayer.Parsed parsed=AlertPlayer.parse(text);
        if(parsed==null) return;
        boolean played=alertMode().equals("audio")&&alerts.play(parsed);
        AlertPlayer.Alert a=parsed.alert;
        note("ALERT "+a.code+" · "+a.title+(played?"":" (repeat, not replayed)"));

        String time=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date());
        banner.code=a.code; banner.tone=a.tone; banner.title=(verified?"✓ ":"")+a.title.toUpperCase(Locale.US);
        banner.body=a.text+(a.extra.isEmpty()?"":"\n\n"+a.extra);
        banner.meta=(parsed.detail.isEmpty()?"":parsed.detail+"\n")+String.format(Locale.US,"%s · origin phone %08X · nearest copy %d dBm · %s",time,from,rssi,trust);
        banner.showOk=a.code.equals("CHK"); banner.okSent=false;
        if(banner.showOk) safe.clear();
        bannerVisible=true; changed();
        if(!alertMode().equals("off")) notifyAlert(a,parsed.detail);
    }
    private void showUnverified(String text,int from,int rssi,String why) {
        if(alertMode().equals("off"))return;
        AlertPlayer.Parsed parsed=AlertPlayer.parse(text);
        if(parsed==null || (bannerVisible && banner.title.startsWith("✓"))) return; // never cover a verified alert
        String time=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date());
        banner.code=UNVERIFIED; banner.tone=AlertPlayer.CALM; banner.showOk=false; banner.okSent=false;
        banner.title="UNVERIFIED: "+parsed.alert.title.toUpperCase(Locale.US);
        banner.body="A nearby phone broadcast this official alert without a valid authority signature, so it was not sounded or passed on. It may be a test or a fake. Follow official alerts.";
        banner.meta=String.format(Locale.US,"%s · origin phone %08X · %d dBm · %s",time,from,rssi,why);
        bannerVisible=true; changed();
    }
    String alertMode(){return settings(this).getString("quake_alert_mode","notification");}
    void alertSettingsChanged(){alerts.stopPlayback();getSystemService(NotificationManager.class).cancel(NOTE_ALERT);getSystemService(NotificationManager.class).cancel(4);}
    private void notifyAlert(AlertPlayer.Alert a,String detail) {
        PendingIntent open=open(MainActivity.class,2);
        Notification.Builder b=new Notification.Builder(this,CH_ALERTS).setSmallIcon(icon())
            .setContentTitle(a.title).setContentText(a.text)
            .setStyle(new Notification.BigTextStyle().bigText(a.text+(detail.isEmpty()?"":"\n"+detail)))
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open).setAutoCancel(true);

        getSystemService(NotificationManager.class).notify(NOTE_ALERT,b.build());
    }
    void dismissBanner() {
        alerts.stopPlayback(); bannerVisible=false; banner.code="";
        getSystemService(NotificationManager.class).cancel(NOTE_ALERT); changed();
    }
    void markSafe() { if(broadcast(SAFE_CODE)) { banner.okSent=true; note("You marked yourself safe"); changed(); } }
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

    /** Sensor corroboration is an experimental advisory; never an authority alert. */
    void sensorAdvisory(boolean test,String summary) {
        if(alertMode().equals("off"))return;
        if(alertMode().equals("audio"))alerts.play(AlertPlayer.parse(test?"TEST":"EQA"));
        NotificationManager nm=getSystemService(NotificationManager.class);
        NotificationChannel ch=new NotificationChannel("sensor_alerts_v2","Experimental sensor observations",NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Advisories from the local accelerometer voting group");
        ch.setSound(null,null); ch.enableVibration(false); nm.createNotificationChannel(ch);
        Notification n=new Notification.Builder(this,"sensor_alerts_v2").setSmallIcon(icon())
            .setContentTitle(test?"TEST: coincident table shaking":"Possible earthquake: coincident shaking")
            .setContentText(summary+" · experimental sensor result, not an official warning")
            .setStyle(new Notification.BigTextStyle().bigText(summary+". Open the sensing page for individual node votes. This does not verify an earthquake."))
            .setContentIntent(open(QuakeActivity.class,4)).setOnlyAlertOnce(true).setAutoCancel(true).build();
        nm.notify(4,n);
    }

    // ---------------------------------------------------------------- ChatEngine.Host
    @Override public int sender() { return sender; }
    @Override public void dataAdvertisingResume(){if(bulk!=null)bulk.reenable();}
    @Override public int dataPsm(){return data==null?0:data.psm();}
    @Override public void dataPacket(int peer,byte[] raw){
        Protocol.Packet packet=Protocol.decode(raw);if(packet==null)return;
        // A direct sensor vote must originate from the connected node. Relayed alerts keep existing verification.
        if(packet.kind==Protocol.SENSOR&&packet.sender!=peer)return;
        dataPacketsReceived++;if(packet.kind==Protocol.TEST&&AlertPlayer.parse(packet.alertText())!=null)dataAlertsReceived++;
        receivePacket(raw,0,"data:"+Integer.toHexString(peer));
    }
    @Override public void voicePacket(int peer,byte[] pcm){VoiceListener listener=voiceListener;if(listener!=null)listener.received(peer,pcm);}
    boolean sendVoice(byte[] pcm){return data!=null&&data.voice(pcm);}
    int activeDataLinks(){return (data==null?0:data.active())+(bulk==null?0:bulk.activeLinks());}
    @Override public boolean imageAdvertising(){return settings(this).getBoolean("image_advertising",true);}
    @Override public boolean connectedImageAvailable(Collection<Integer> expected){return ready&&((data!=null&&data.active()>0)||(bulk!=null&&bulk.available(expected)));}
    @Override public boolean sendImage(int id,byte[] envelope,Collection<Integer> expected,BulkImages.Completion done){
        if(ready&&data!=null&&data.sendChat(id,envelope,expected,done))return true;
        return ready&&ChatProtocol.message(envelope).type!=ChatProtocol.TEXT&&bulk!=null&&bulk.send(id,envelope,expected,done);
    }
    @Override public void cancelImageTransfers(){if(bulk!=null)bulk.cancel();if(data!=null)data.cancel();}
    @Override public void imageLinkStatus(String status,float fraction,boolean busy){chat.imageLinkStatus(status,fraction,busy);}
    @Override public boolean saveImage(int sender,int id,byte[] envelope) throws Exception {
        ChatProtocol.Message message=ChatProtocol.message(envelope);
        if(message.type!=ChatProtocol.TEXT){android.graphics.Bitmap check=ChatImage.decode(message.content);
        if(check==null)return false;check.recycle();}
        java.util.concurrent.CompletableFuture<Boolean> result=new java.util.concurrent.CompletableFuture<>();
        main.post(()->{try{result.complete(running&&ready&&chat.saveConnectedImage(sender,id,envelope));}catch(Exception e){result.completeExceptionally(e);}});
        return result.get(3,java.util.concurrent.TimeUnit.SECONDS);
    }
    @Override public void chatChanged() { changed(); }
    @Override public void incomingMessage(String name,int from,String preview) {
        if(chatVisible) return;
        Notification n=new Notification.Builder(this,CH_CHAT).setSmallIcon(icon())
            .setContentTitle(DeviceIdentity.display(name,from)).setContentText(preview)
            .setStyle(new Notification.BigTextStyle().bigText(preview))
            .setCategory(Notification.CATEGORY_MESSAGE).setAutoCancel(true)
            .setContentIntent(open(ChatActivity.class,3)).build();
        getSystemService(NotificationManager.class).notify(NOTE_CHAT,n);
    }
    void cancelChatNotification() { getSystemService(NotificationManager.class).cancel(NOTE_CHAT); }
}
