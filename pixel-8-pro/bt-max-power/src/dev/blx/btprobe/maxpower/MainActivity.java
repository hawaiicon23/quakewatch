package dev.blx.btprobe.maxpower;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.location.LocationManager;
import android.view.*;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    // Android 17 / API 37 TX_POWER_MAX_AVAILABLE. Compile SDK 35 has no named
    // constant yet; the manifest restricts this experiment to API 37 and newer.
    // This requests the controller maximum; it does not force 20 dBm RF output.
    private static final int MAX_AVAILABLE_TX_POWER = 20;
    private final int BG=0xff101c22, CARD=0xff1b2c34, MINT=0xff79e3c0, MUTED=0xff9aafb8;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();
    private int sender;
    private String deviceLabel;
    private boolean continuous, beaconAdvertising;
    private int beaconSequence, beaconCount;
    private Button testMenu;
    private TextView peerView;
    private final PeerStats peers=new PeerStats();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private boolean running, transmitting, scanReady, initializing;
    private int generation, txCount, rxCount, ackCount;
    private TextView state, counters, eventLog, powerStatus;
    private EditText message;
    private Button toggle, send;
    private CheckBox autoAck;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private final DeliveryTracker deliveries = new DeliveryTracker();
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private AdvertisingSetCallback active;
    private AdvertisingSet advertisingSet;
    private byte[] currentData;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        android.content.SharedPreferences prefs=getSharedPreferences("identity",MODE_PRIVATE);
        sender=prefs.getInt("id",random.nextInt());
        deviceLabel=prefs.getString("label",String.format(Locale.US,"Phone-%04X",sender&65535));
        prefs.edit().putInt("id",sender).putString("label",deviceLabel).apply();
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        buildUi();
        log("Ready. Maximum available TX power experiment.");
        if(getIntent().getBooleanExtra("start_test",false)) handler.post(this::prepare);
    }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable shape(int color) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(18)); return d;
    }
    private TextView label(String text, int size, int color) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); return v;
    }
    private void gap(LinearLayout p, int h) { View v=new View(this); p.addView(v,new LinearLayout.LayoutParams(1,dp(h))); }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c=new LinearLayout(this); c.setOrientation(1); c.setPadding(dp(20),dp(18),dp(20),dp(18));
        c.setBackground(shape(CARD)); parent.addView(c,new LinearLayout.LayoutParams(-1,-2)); return c;
    }
    private Button button(String text, boolean primary) {
        Button b=new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(16);
        b.setTextColor(primary ? BG : Color.WHITE); b.setBackground(shape(primary ? MINT : 0xff304751));
        b.setPadding(dp(12),dp(12),dp(12),dp(12)); return b;
    }
    private void buildUi() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout page=new LinearLayout(this); page.setOrientation(1); page.setPadding(dp(22),dp(22),dp(22),dp(30));
        // Handle Android 15 edge-to-edge system bars without covering controls.
        scroll.setOnApplyWindowInsetsListener((v,insets)-> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
                page.setPadding(dp(22)+bars.left,dp(18)+bars.top,dp(22)+bars.right,dp(24)+bars.bottom);
            } else page.setPadding(dp(22),dp(18)+insets.getSystemWindowInsetTop(),dp(22),dp(24)+insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.addView(page); setContentView(scroll);
        page.addView(label("NEARBY / OFFLINE",12,MINT)); gap(page,7);
        TextView title=label("BT Probe Max",36,Color.WHITE); title.setTypeface(null,Typeface.BOLD); page.addView(title);
        page.addView(label("Maximum available power · Pixel experiment",16,MUTED)); gap(page,22);
        LinearLayout statusCard=card(page);
        statusCard.addView(label("BLUETOOTH BROADCAST",11,MUTED)); gap(statusCard,8);
        state=label("Stopped",22,Color.WHITE); statusCard.addView(state); gap(statusCard,6);
        statusCard.addView(label(String.format(Locale.US,"Device ID · %08X",sender),13,MUTED)); gap(statusCard,14);
        powerStatus=label("Requested: maximum available (+20 dBm request)\nController selected: waiting",14,MINT);
        statusCard.addView(powerStatus); gap(statusCard,14);
        toggle=button("Start Bluetooth",true); statusCard.addView(toggle,new LinearLayout.LayoutParams(-1,dp(52)));
        toggle.setOnClickListener(v->{ if(running) stop(); else prepare(); });
        gap(statusCard,10);
        testMenu=button("Continuous test…",false); statusCard.addView(testMenu,new LinearLayout.LayoutParams(-1,dp(52)));
        testMenu.setOnClickListener(v->showTestMenu());
        gap(page,14); LinearLayout peerCard=card(page);
        peerCard.addView(label("NEARBY DEVICES / SIGNAL",11,MUTED)); gap(peerCard,8);
        peerView=label("No peers yet. Start a test on another device.",15,Color.WHITE); peerCard.addView(peerView);
        gap(page,14);
        LinearLayout sendCard=card(page); sendCard.addView(label("SEND A TEST",11,MUTED)); gap(sendCard,8);
        message=new EditText(this); message.setSingleLine(true); message.setText("TEST"); message.setTextColor(Color.WHITE);
        message.setTextSize(22); message.setSelectAllOnFocus(true); sendCard.addView(message,new LinearLayout.LayoutParams(-1,dp(56)));
        sendCard.addView(label("Up to 12 UTF-8 bytes · public test data",12,MUTED)); gap(sendCard,12);
        send=button("Broadcast test",true); send.setEnabled(false); sendCard.addView(send,new LinearLayout.LayoutParams(-1,dp(52)));
        send.setOnClickListener(v->sendTest()); gap(sendCard,10);
        autoAck=new CheckBox(this); autoAck.setText("Reply to incoming tests automatically"); autoAck.setTextColor(Color.WHITE); autoAck.setChecked(true);
        sendCard.addView(autoAck);
        gap(page,14); LinearLayout feed=card(page);
        counters=label("0 sent     0 received     0 replies",14,MINT); feed.addView(counters); gap(feed,14);
        eventLog=label("",13,Color.WHITE); eventLog.setTypeface(Typeface.MONOSPACE); eventLog.setTextIsSelectable(true); feed.addView(eventLog);
        gap(feed,12); Button clear=button("Clear log",false); feed.addView(clear); clear.setOnClickListener(v->{lines.clear(); eventLog.setText("");});
        gap(page,16); page.addView(label("No pairing or internet. Keep this app open on both devices. Broadcasts display test text only; they never execute commands.",13,MUTED));
    }
    private void log(String text) {
        android.util.Log.i("BTProbeMax",text);
        String time=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date());
        lines.addFirst(time+"  "+text); while(lines.size()>60) lines.removeLast(); eventLog.setText(String.join("\n\n",lines));
        counters.setText(txCount+" sent     "+rxCount+" received     "+ackCount+" replies");
    }
    private void showTestMenu() {
        LinearLayout content=new LinearLayout(this); content.setOrientation(1); content.setPadding(dp(24),dp(8),dp(24),dp(8));
        TextView info=new TextView(this); info.setText(String.format(Locale.US,"Device ID: %08X\nBroadcast every ~0.5 s while this app is open.\nPeers show received signal strength in dBm.\nName: up to 12 UTF-8 bytes.",sender)); content.addView(info);
        EditText name=new EditText(this); name.setSingleLine(true); name.setText(deviceLabel); content.addView(name);
        new AlertDialog.Builder(this).setTitle("Continuous range test").setView(content)
            .setNegativeButton("Cancel",null)
            .setNeutralButton("Save name",(d,w)->saveLabel(name.getText().toString()))
            .setPositiveButton(continuous?"Stop continuous test":"Start continuous test",(d,w)-> {
                if(!saveLabel(name.getText().toString())) return;
                boolean next=!continuous; if(running) stop(); continuous=next;
                testMenu.setText(continuous?"Continuous test · ON":"Continuous test…");
                prepare();
            }).show();
    }
    private boolean saveLabel(String name) {
        name=name.trim();
        if(name.isEmpty() || name.getBytes(StandardCharsets.UTF_8).length>12) { log("Device name must use 1–12 UTF-8 bytes."); return false; }
        deviceLabel=name; getSharedPreferences("identity",MODE_PRIVATE).edit().putString("label",name).apply(); return true;
    }
    private void refreshPeers() {
        long now=SystemClock.elapsedRealtime(); StringBuilder text=new StringBuilder();
        for(PeerStats.Peer peer:peers.values()) {
            if(text.length()>0) text.append("\n\n");
            text.append(String.format(Locale.US,"%s [%08X]\n%d dBm · %d samples · seen %d s ago",peer.label.isEmpty()?"Peer":peer.label,peer.id,peer.rssi,peer.samples,Math.max(0,(now-peer.lastSeen)/1000)));
        }
        if(text.length()==0) text.append("No peers yet. Start a test on another device.");
        peerView.setText(text);
        if(continuous) state.setText("Continuous · ~0.5 s · "+beaconCount+" beacons");
        if(running) handler.postDelayed(this::refreshPeers,500);
    }
    private String[] permissions() {
        return Build.VERSION.SDK_INT>=31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.BLUETOOTH_CONNECT}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    private void prepare() {
        for(String p:permissions()) if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(permissions(),10); return; }
        if(adapter==null) { log("This device has no Bluetooth adapter."); return; }
        if(!adapter.isEnabled()) { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),11); return; }
        if(Build.VERSION.SDK_INT<31 && !locationEnabled()) {
            log("Enable Location in Android settings for BLE scanning, then start again.");
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return;
        }
        start();
    }
    private boolean locationEnabled() {
        LocationManager manager=getSystemService(LocationManager.class);
        if(Build.VERSION.SDK_INT>=28) return manager.isLocationEnabled();
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if(r!=10) return;
        for(String perm:permissions()) if(checkSelfPermission(perm)!=PackageManager.PERMISSION_GRANTED) { log("Nearby devices permission is required. Allow it in App settings."); return; }
        prepare();
    }
    @Override protected void onActivityResult(int r,int c,Intent d) { super.onActivityResult(r,c,d); if(r==11 && c==RESULT_OK) prepare(); }
    private final ScanCallback scans=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult r) { handler.post(()->receive(r)); }
        @Override public void onBatchScanResults(List<ScanResult> results) { for(ScanResult r:results) handler.post(()->receive(r)); }
        @Override public void onScanFailed(int code) { handler.post(()-> { if(running) { log("Scan failed ("+code+"). Stop, wait a few seconds, and retry."); stop(); }}); }
    };
    private void start() {
        try {
            scanner=adapter.getBluetoothLeScanner(); advertiser=adapter.getBluetoothLeAdvertiser();
            if(scanner==null || advertiser==null) { log("Scanning and BLE advertising must both be supported."); return; }
            running=true; generation++; initializing=true;
            state.setText("Starting radio"); toggle.setText("Stop Bluetooth");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            // Allocate the advertising address BEFORE starting the one continuous scan.
            // Retain this set for the session: recreating it per message triggers HCI 12,
            // while stopping/restarting scanning per message triggers Android scan throttling.
            final int epoch=generation;
            active=new AdvertisingSetCallback() {
                private boolean valid() { return running && generation==epoch && active==this; }
                @Override public void onAdvertisingSetStarted(AdvertisingSet set,int power,int status) {
                    if(!valid()) { advertiser.stopAdvertisingSet(this); return; }
                    if(status!=0) { log("Radio startup failed ("+status+"): "+advertiseError(status)); stop(); return; }
                    advertisingSet=set;
                    reportPower(power);
                    log("Radio ready · requested maximum available (+20 dBm) · controller "+power+" dBm");
                    set.enableAdvertising(false,0,0);
                }
                @Override public void onAdvertisingParametersUpdated(AdvertisingSet set,int power,int status) {
                    if(!valid()) return;
                    if(status==0) reportPower(power);
                    else log("Power parameter update failed ("+status+")");
                }
                @Override public void onAdvertisingDataSet(AdvertisingSet set,int status) {
                    if(!valid() || !transmitting) return;
                    if(status!=0) { failedTransmission(status); return; }
                    if(continuous && beaconAdvertising) transmissionStarted(epoch);
                    else set.enableAdvertising(true,0,0);
                }
                @Override public void onAdvertisingEnabled(AdvertisingSet set,boolean enabled,int status) {
                    if(!valid()) return;
                    if(status!=0) { log("Radio enable failed ("+status+")"); stop(); return; }
                    if(initializing) {
                        if(enabled) return;
                        initializing=false;
                        try { resumeScan(); } catch(Exception e) { log("Scan failed: "+e.getMessage()); stop(); return; }
                        state.setText("Listening nearby"); send.setEnabled(true);
                        log("Listening for all BT Probe peers. No pairing."); refreshPeers(); pump();
                    } else if(enabled) { beaconAdvertising=true; transmissionStarted(epoch); }
                    else finishTransmission();
                }
            };
            AdvertisingSetParameters parameters=new AdvertisingSetParameters.Builder()
                .setLegacyMode(true).setConnectable(false).setScannable(false)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(MAX_AVAILABLE_TX_POWER).build();
            advertiser.startAdvertisingSet(parameters,new AdvertiseData.Builder().build(),null,null,null,active);
        } catch(Exception e) { log("Cannot start: "+e.getMessage()); stop(); }
    }
    private void reportPower(int power) {
        String selected=power==127 ? "unavailable" : String.format(Locale.US,"%+d dBm",power);
        powerStatus.setText("Requested: maximum available (+20 dBm request)\nController selected: "+selected+"\nController report; antenna output is not measured.");
        log("Controller-selected TX power: "+selected);
    }
    private void resumeScan() {
        if(!running || scanReady) return;
        ScanFilter f=new ScanFilter.Builder().setManufacturerData(Protocol.COMPANY,Protocol.PREFIX,new byte[]{-1,-1,-1}).build();
        scanner.startScan(Collections.singletonList(f),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scans);
        scanReady=true;
    }
    private void sendTest() {
        if(!running) return;
        String text=message.getText().toString(); byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(bytes.length==0 || bytes.length>Protocol.MAX_TEXT) { message.setError("Enter 1–12 UTF-8 bytes"); return; }
        if(queue.size()>=16) { log("Send queue full. Wait and retry."); return; }
        int id=random.nextInt(); queue.add(Protocol.text(sender,id,text)); pump();
    }
    private void receive(ScanResult r) {
        if(!running || r.getScanRecord()==null) return;
        Protocol.Packet p=Protocol.decode(r.getScanRecord().getManufacturerSpecificData(Protocol.COMPANY));
        if(p==null || p.sender==sender) return;
        long now=SystemClock.elapsedRealtime();
        peers.observe(p,r.getRssi(),now);
        if(p.kind==Protocol.BEACON) return;
        if(p.kind==Protocol.TEST) {
            if(deliveries.first(p,now)) {
                rxCount++; log(String.format(Locale.US,"RX “%s” · %08X · %d dBm",p.text(),p.sender,r.getRssi()));
            }
            // A retransmission may mean our previous ACK was lost. Display once,
            // but ACK it again at a bounded rate instead of silently discarding it.
            if(autoAck.isChecked() && queue.size()<16 && deliveries.replyDue(p,now)) {
                deliveries.replied(p,now); queue.addFirst(Protocol.ack(sender,p.id,p.sender)); pump();
            }
        } else if(p.recipient()==sender) {
            Long elapsed=deliveries.acknowledge(p.id,p.sender,now);
            if(elapsed!=null) { ackCount++; log(String.format(Locale.US,"Reply from %08X · %d ms · %d dBm",p.sender,elapsed,r.getRssi())); }
        }
    }
    private void pump() {
        if(!running || initializing || advertisingSet==null || transmitting || (!continuous && queue.isEmpty())) return;
        do {
            currentData=queue.isEmpty()?Protocol.beacon(sender,beaconSequence++,deviceLabel):queue.remove();
            Protocol.Packet packet=Protocol.decode(currentData);
            if(packet.kind!=Protocol.TEST || deliveries.canTransmit(packet.id,SystemClock.elapsedRealtime())) break;
            currentData=null;
        } while(!queue.isEmpty());
        if(currentData==null && continuous) currentData=Protocol.beacon(sender,beaconSequence++,deviceLabel);
        if(currentData==null) return;
        transmitting=true;
        try { advertisingSet.setAdvertisingData(new AdvertiseData.Builder().addManufacturerData(Protocol.COMPANY,currentData).build()); }
        catch(Exception e) { log("Broadcast error: "+e.getMessage()); finishTransmission(); }
    }
    private void transmissionStarted(int epoch) {
        if(!transmitting) return;
        final byte[] data=currentData;
        Protocol.Packet packet=Protocol.decode(data);
        if(packet.kind==Protocol.TEST) {
            DeliveryTracker.Pending pending=deliveries.transmitted(packet.id,SystemClock.elapsedRealtime());
            if(pending.attempts==1) {
                txCount++; log("TX “"+packet.text()+"” · collecting replies from all peers");
                handler.postDelayed(()-> {
                    if(generation==epoch && deliveries.expire(packet.id)) log("No reply after 20 seconds and up to 3 broadcasts.");
                },DeliveryTracker.WINDOW_MS);
            } else log("Retry “"+packet.text()+"” · attempt "+pending.attempts);
            handler.postDelayed(()-> {
                if(running && generation==epoch && deliveries.retry(packet.id,SystemClock.elapsedRealtime()) && queue.size()<16) {
                    queue.add(data); pump();
                }
            },5000);
        } else if(packet.kind==Protocol.ACK) log("TX acknowledgment");
        else beaconCount++;
        handler.postDelayed(()-> {
            if(running && generation==epoch && transmitting && currentData==data) {
                if(continuous) finishTransmission();
                else try { beaconAdvertising=false; advertisingSet.enableAdvertising(false,0,0); }
                catch(Exception e) { log("Stop broadcast: "+e.getMessage()); stop(); }
            }
        },continuous?500:3000);
    }
    private void failedTransmission(int status) {
        log("Broadcast failed ("+status+"): "+advertiseError(status));
        if(continuous) { stop(); return; }
        finishTransmission();
    }
    private void finishTransmission() {
        currentData=null; transmitting=false; pump();
    }
    private String advertiseError(int c) {
        switch(c) {case 1:return "payload too large"; case 2:return "too many advertisers"; case 3:return "already started";
            case 4:return "Bluetooth internal error"; case 5:return "advertising unsupported";
            case 12:return "controller command disallowed while setting advertising address"; default:return "unknown error";}
    }
    private void stop() {
        boolean wasRunning=running; running=false; generation++; handler.removeCallbacksAndMessages(null);
        try { if(scanReady && scanner!=null) scanner.stopScan(scans); } catch(Exception ignored) {}
        try { if(active!=null && advertiser!=null) advertiser.stopAdvertisingSet(active); } catch(Exception ignored) {}
        beaconAdvertising=false; continuous=false; testMenu.setText("Continuous test…");
        active=null; advertisingSet=null; currentData=null; initializing=false; transmitting=false; scanReady=false; queue.clear(); deliveries.clear();
        state.setText("Stopped"); powerStatus.setText("Requested: maximum available (+20 dBm request)\nController selected: inactive"); toggle.setText("Start Bluetooth"); send.setEnabled(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(wasRunning) log("Bluetooth test stopped.");
    }
    @Override protected void onStop() { stop(); super.onStop(); }
}
