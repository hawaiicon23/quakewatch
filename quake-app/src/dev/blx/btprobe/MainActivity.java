package dev.blx.btprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.location.LocationManager;
import android.view.*;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Screen only. All Bluetooth work, relaying and alert playback live in MeshService,
 * which keeps running in the background; this Activity renders its state.
 */
public class MainActivity extends Activity {
    private final int BG=0xff101c22, CARD=0xff1b2c34, MINT=0xff79e3c0, MUTED=0xff9aafb8;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private int sender;
    private String deviceLabel;
    private boolean pendingContinuous;
    private Button testMenu, toggle, send, okButton;
    private TextView peerView, state, counters, eventLog, alertTitle, alertBody, alertMeta, safeView;
    private EditText message;
    private CheckBox autoAck, relay, playUnsigned;
    private LinearLayout alertCard;
    private final Runnable tick=new Runnable() { @Override public void run() { render(); handler.postDelayed(this,500); } };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        // Let urgent alerts (full-screen notification) show this screen over the lock screen.
        if(Build.VERSION.SDK_INT>=27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        SharedPreferences prefs=getSharedPreferences("identity",MODE_PRIVATE);
        sender=prefs.getInt("id",new java.security.SecureRandom().nextInt());
        deviceLabel=prefs.getString("label",String.format(Locale.US,"Phone-%04X",sender&65535));
        prefs.edit().putInt("id",sender).putString("label",deviceLabel).apply();
        BluetoothManager manager=getSystemService(BluetoothManager.class);
        adapter=manager==null?null:manager.getAdapter();
        buildUi();
        if(MeshService.lines.isEmpty()) MeshService.note("Ready. Start Bluetooth: alerts keep working in the background.");
        AudioManager am=getSystemService(AudioManager.class);
        if(am!=null && am.getStreamVolume(AudioManager.STREAM_ALARM)==0)
            MeshService.note("Alarm volume is muted: earthquake alerts will be silent. Raise the alarm volume.");
    }
    @Override protected void onStart() {
        super.onStart();
        MeshService.uiVisible=true; MeshService.setListener(this::render);
        // The banner on screen replaces the notification.
        ((android.app.NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(2);
        handler.post(tick);
        // After a background (boot) start on Android 10-11, re-start from the foreground to restore scanning.
        MeshService s=MeshService.get();
        if(s!=null && s.needsForegroundRestart) startForegroundService(new Intent(this,MeshService.class).setAction(MeshService.ACTION_START));
    }
    @Override protected void onStop() {
        // Unlike BT Probe, leaving the screen does NOT stop Bluetooth: MeshService keeps listening.
        MeshService.uiVisible=false; MeshService.setListener(null); handler.removeCallbacks(tick);
        super.onStop();
    }

    // ---- Layout helpers ----
    private int dp(int n) { return (int)(n*getResources().getDisplayMetrics().density+.5f); }
    private GradientDrawable shape(int color) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(18)); return d; }
    private TextView label(String text,int size,int color) { TextView v=new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); return v; }
    private void gap(LinearLayout p,int h) { View v=new View(this); p.addView(v,new LinearLayout.LayoutParams(1,dp(h))); }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout c=new LinearLayout(this); c.setOrientation(1); c.setPadding(dp(20),dp(18),dp(20),dp(18));
        c.setBackground(shape(CARD)); parent.addView(c,new LinearLayout.LayoutParams(-1,-2)); return c;
    }
    private Button button(String text,boolean primary) {
        Button b=new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(16);
        b.setTextColor(primary?BG:Color.WHITE); b.setBackground(shape(primary?MINT:0xff304751));
        b.setPadding(dp(12),dp(12),dp(12),dp(12)); return b;
    }
    private void buildUi() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(BG);
        LinearLayout page=new LinearLayout(this); page.setOrientation(1); page.setPadding(dp(22),dp(22),dp(22),dp(30));
        // Handle Android 15 edge-to-edge system bars without covering controls.
        scroll.setOnApplyWindowInsetsListener((v,insets)-> {
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
                page.setPadding(dp(22)+bars.left,dp(18)+bars.top,dp(22)+bars.right,dp(24)+bars.bottom);
            } else page.setPadding(dp(22),dp(18)+insets.getSystemWindowInsetTop(),dp(22),dp(24)+insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.addView(page); setContentView(scroll);
        page.addView(label("NEARBY / OFFLINE",12,MINT)); gap(page,7);
        TextView title=label("Quake Mesh",36,Color.WHITE); title.setTypeface(null,Typeface.BOLD); page.addView(title);
        page.addView(label("Earthquake alerts between nearby phones.",16,MUTED)); gap(page,22);
        alertCard=card(page); alertCard.setVisibility(View.GONE);
        alertTitle=label("",28,Color.WHITE); alertTitle.setTypeface(null,Typeface.BOLD); alertCard.addView(alertTitle); gap(alertCard,8);
        alertBody=label("",19,Color.WHITE); alertCard.addView(alertBody); gap(alertCard,10);
        alertMeta=label("",13,Color.WHITE); alertCard.addView(alertMeta); gap(alertCard,12);
        safeView=label("",17,MINT); safeView.setVisibility(View.GONE); alertCard.addView(safeView);
        okButton=button("I'm OK",true); okButton.setTextSize(22); okButton.setVisibility(View.GONE);
        alertCard.addView(okButton,new LinearLayout.LayoutParams(-1,dp(64)));
        okButton.setOnClickListener(v->{ MeshService s=MeshService.get(); if(s!=null) s.markSafe(); }); gap(alertCard,10);
        Button dismiss=button("Dismiss",false); alertCard.addView(dismiss,new LinearLayout.LayoutParams(-1,dp(48)));
        dismiss.setOnClickListener(v->{ MeshService s=MeshService.get(); if(s!=null) s.dismissBanner(); else alertCard.setVisibility(View.GONE); });
        gap(page,14);
        LinearLayout statusCard=card(page);
        statusCard.addView(label("BLUETOOTH BROADCAST",11,MUTED)); gap(statusCard,8);
        state=label("Stopped",22,Color.WHITE); statusCard.addView(state); gap(statusCard,6);
        statusCard.addView(label(String.format(Locale.US,"Device ID · %08X",sender),13,MUTED)); gap(statusCard,14);
        toggle=button("Start Bluetooth",true); statusCard.addView(toggle,new LinearLayout.LayoutParams(-1,dp(52)));
        toggle.setOnClickListener(v->{ if(running()) stopService(); else prepare(false); });
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
        Button sendAlert=button("Send alert…",false); sendCard.addView(sendAlert,new LinearLayout.LayoutParams(-1,dp(52)));
        sendAlert.setOnClickListener(v->showAlertMenu()); gap(sendCard,10);
        SharedPreferences settings=MeshService.settings(this);
        autoAck=new CheckBox(this); autoAck.setText("Reply to incoming tests automatically"); autoAck.setTextColor(Color.WHITE);
        autoAck.setChecked(settings.getBoolean("autoAck",true)); sendCard.addView(autoAck);
        autoAck.setOnCheckedChangeListener((v,c)->settings.edit().putBoolean("autoAck",c).apply());
        relay=new CheckBox(this); relay.setText("Relay alerts to other phones (mesh)"); relay.setTextColor(Color.WHITE);
        relay.setChecked(settings.getBoolean("relay",true)); sendCard.addView(relay);
        relay.setOnCheckedChangeListener((v,c)->settings.edit().putBoolean("relay",c).apply());
        playUnsigned=new CheckBox(this); playUnsigned.setText("Play unsigned official alerts (testing only)"); playUnsigned.setTextColor(Color.WHITE);
        playUnsigned.setChecked(settings.getBoolean("playUnsigned",false)); sendCard.addView(playUnsigned);
        playUnsigned.setOnCheckedChangeListener((v,c)->settings.edit().putBoolean("playUnsigned",c).apply());
        gap(sendCard,10); Button authority=button("Authority key…",false); sendCard.addView(authority,new LinearLayout.LayoutParams(-1,dp(52)));
        authority.setOnClickListener(v->showAuthorityDialog());
        gap(page,14); LinearLayout feed=card(page);
        counters=label("",14,MINT); feed.addView(counters); gap(feed,14);
        eventLog=label("",13,Color.WHITE); eventLog.setTypeface(Typeface.MONOSPACE); eventLog.setTextIsSelectable(true); feed.addView(eventLog);
        gap(feed,12); Button clear=button("Clear log",false); feed.addView(clear); clear.setOnClickListener(v->MeshService.clearLog());
        gap(page,16); page.addView(label("No pairing or internet. Keeps listening in the background while its notification is shown; tap Stop Bluetooth to turn it off. Official alerts (EQW EQN EQA AFT TSU CLR CXL) only sound when signed by a trusted authority key; unsigned ones are shown silently and not passed on. Community messages (TEST, CHK, OK, SOS) are unverified. Alerts only show text and play built-in voice clips, and never execute commands. Follow official alerts when you get them.",13,MUTED));
    }

    // ---- Rendering service state ----
    private boolean running() { MeshService s=MeshService.get(); return s!=null && s.running; }
    private String lastLog="";
    private void render() {
        MeshService s=MeshService.get();
        boolean on=s!=null && s.running;
        if(s==null) state.setText("Stopped");
        else if(on && s.continuous && !s.initializing) state.setText("Continuous · ~0.5 s · "+s.beaconCount+" beacons");
        else state.setText(s.stateText);
        toggle.setText(on?"Stop Bluetooth":"Start Bluetooth");
        send.setEnabled(on && !s.initializing);
        testMenu.setText(on && s.continuous?"Continuous test · ON":"Continuous test…");
        counters.setText(s==null?"0 sent     0 received     0 replies     0 relayed"
            : s.txCount+" sent     "+s.rxCount+" received     "+s.ackCount+" replies     "+s.relayCount+" relayed");
        String log=String.join("\n\n",MeshService.lines);
        if(!log.equals(lastLog)) { lastLog=log; eventLog.setText(log); }
        StringBuilder text=new StringBuilder();
        if(s!=null) {
            long now=SystemClock.elapsedRealtime();
            for(PeerStats.Peer peer:s.peers.values()) {
                if(text.length()>0) text.append("\n\n");
                text.append(String.format(Locale.US,"%s [%08X]\n%d dBm · %d samples · seen %d s ago",peer.label.isEmpty()?"Peer":peer.label,peer.id,peer.rssi,peer.samples,Math.max(0,(now-peer.lastSeen)/1000)));
            }
        }
        if(text.length()==0) text.append("No peers yet. Start a test on another device.");
        peerView.setText(text);
        renderBanner(s);
    }
    private void renderBanner(MeshService s) {
        if(s==null || !s.bannerVisible) { alertCard.setVisibility(View.GONE); return; }
        MeshService.Banner b=s.banner;
        int color=b.tone==AlertPlayer.URGENT?0xffb3261e:b.tone==AlertPlayer.SERIOUS?0xff8a5a00:CARD;
        alertCard.setBackground(shape(color)); alertCard.setVisibility(View.VISIBLE);
        alertTitle.setText(b.title); alertBody.setText(b.body); alertMeta.setText(b.meta);
        okButton.setVisibility(b.showOk?View.VISIBLE:View.GONE);
        okButton.setEnabled(!b.okSent); okButton.setText(b.okSent?"Sent · you're marked safe":"I'm OK");
        boolean check=b.code.equals("CHK") || b.code.equals(MeshService.CHECK_SENT);
        safeView.setVisibility(check?View.VISIBLE:View.GONE);
        if(check) {
            StringBuilder t=new StringBuilder("✓ "+s.safe.size()+(s.safe.size()==1?" person":" people")+" nearby marked safe");
            int n=0;
            for(int id:s.safe) { if(n++==6) { t.append(" …"); break; } t.append(n==1?"\n":", ").append(String.format(Locale.US,"%08X",id)); }
            safeView.setText(t);
        }
    }

    // ---- Actions ----
    private void sendTest() {
        MeshService s=MeshService.get(); if(s==null || !s.running) return;
        String text=message.getText().toString(); byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if(bytes.length==0 || bytes.length>Protocol.MAX_TEXT) { message.setError("Enter 1–12 UTF-8 bytes"); return; }
        s.broadcast(text);
    }
    private void showAlertMenu() {
        final String[] codes=AlertPlayer.ALERTS.keySet().toArray(new String[0]);
        String[] items=new String[codes.length];
        for(int i=0;i<codes.length;i++) items[i]=codes[i]+" · "+AlertPlayer.ALERTS.get(codes[i]).title;
        new AlertDialog.Builder(this).setTitle(running()?"Broadcast alert to nearby phones":"Start Bluetooth first")
            .setItems(items,(d,w)-> {
                MeshService s=MeshService.get();
                if(s==null || !s.running) { MeshService.note("Start Bluetooth before sending an alert."); return; }
                if(codes[w].equals("CHK")) s.sendCheck(); else s.sendAlert(codes[w]);
            }).setNegativeButton("Cancel",null).show();
    }
    private void showTestMenu() {
        MeshService s=MeshService.get(); final boolean continuous=s!=null && s.running && s.continuous;
        LinearLayout content=new LinearLayout(this); content.setOrientation(1); content.setPadding(dp(24),dp(8),dp(24),dp(8));
        TextView info=new TextView(this); info.setText(String.format(Locale.US,"Device ID: %08X\nBroadcast every ~0.5 s.\nPeers show received signal strength in dBm.\nName: up to 12 UTF-8 bytes.",sender)); content.addView(info);
        EditText name=new EditText(this); name.setSingleLine(true); name.setText(deviceLabel); content.addView(name);
        new AlertDialog.Builder(this).setTitle("Continuous range test").setView(content)
            .setNegativeButton("Cancel",null)
            .setNeutralButton("Save name",(d,w)->saveLabel(name.getText().toString()))
            .setPositiveButton(continuous?"Stop continuous test":"Start continuous test",(d,w)-> {
                if(saveLabel(name.getText().toString())) prepare(!continuous);
            }).show();
    }
    private boolean saveLabel(String name) {
        name=name.trim();
        if(name.isEmpty() || name.getBytes(StandardCharsets.UTF_8).length>12) { MeshService.note("Device name must use 1–12 UTF-8 bytes."); return false; }
        deviceLabel=name; getSharedPreferences("identity",MODE_PRIVATE).edit().putString("label",name).apply(); return true;
    }
    /** Create or show this phone's authority key; its public key must go into trusted_keys.txt. */
    private void showAuthorityDialog() {
        boolean has=PhoneAuthority.exists();
        TextView info=new TextView(this); info.setPadding(dp(24),dp(8),dp(24),dp(8)); info.setTextIsSelectable(true);
        AlertDialog.Builder d=new AlertDialog.Builder(this).setTitle("Alert authority").setView(info).setNegativeButton("Close",null);
        if(has) {
            try {
                String line=PhoneAuthority.trustedLine(deviceLabel+"-authority");
                info.setText("This phone signs the official alerts it sends.\nKey "+SignedAlerts.fingerprint(PhoneAuthority.publicKey())
                    +"\n\nOther phones only trust it after this line is added to quake-app/res/raw/trusted_keys.txt and the app is rebuilt:\n\n"+line
                    +"\n\nAlso saved to "+exportPublicKey(line));
            } catch(Exception e) { info.setText("Key error: "+e.getMessage()); }
        } else {
            info.setText("Create a private key on this phone so the official alerts it sends are signed. The key never leaves the phone. Only create one on a phone you trust to sound alarms.");
            d.setPositiveButton("Create authority key",(x,w)-> {
                try { PhoneAuthority.create(); MeshService.note("Authority key created: "+SignedAlerts.fingerprint(PhoneAuthority.publicKey())); showAuthorityDialog(); }
                catch(Exception e) { MeshService.note("Cannot create key: "+e.getMessage()); }
            });
        }
        MeshService s=MeshService.get();
        if(s!=null) info.append("\n\n"+s.trustedCount()+" trusted authorities built into this app.");
        d.show();
    }
    private String exportPublicKey(String line) {
        android.util.Log.i("QuakeMesh","TRUSTED_KEY "+line);
        try {
            java.io.File f=new java.io.File(getExternalFilesDir(null),"authority_public_key.txt");
            try(java.io.FileWriter w=new java.io.FileWriter(f)) { w.write(line+"\n"); }
            return f.getAbsolutePath();
        } catch(Exception e) { return "(not saved: "+e.getMessage()+")"; }
    }
    private void stopService() {
        startService(new Intent(this,MeshService.class).setAction(MeshService.ACTION_STOP));
    }

    // ---- Permissions and start ----
    private String[] required() {
        return Build.VERSION.SDK_INT>=31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.BLUETOOTH_CONNECT}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    private String[] requested() {
        if(Build.VERSION.SDK_INT<33) return required();
        String[] r=required(); String[] all=Arrays.copyOf(r,r.length+1);
        all[r.length]=Manifest.permission.POST_NOTIFICATIONS; // optional: alert notifications
        return all;
    }
    private void prepare(boolean continuous) {
        pendingContinuous=continuous;
        for(String p:required()) if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(requested(),10); return; }
        if(adapter==null) { MeshService.note("This device has no Bluetooth adapter."); return; }
        if(!adapter.isEnabled()) { startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),11); return; }
        if(Build.VERSION.SDK_INT<31 && !locationEnabled()) {
            MeshService.note("Enable Location in Android settings for BLE scanning, then start again.");
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return;
        }
        askBatteryExemptionOnce();
        askBackgroundLocationOnce();
        Intent i=new Intent(this,MeshService.class).setAction(MeshService.ACTION_START).putExtra(MeshService.EXTRA_CONTINUOUS,continuous);
        startForegroundService(i);
    }
    /** Android 10-11: without "Allow all the time", Bluetooth scanning is blocked after a reboot. Ask once. */
    private void askBackgroundLocationOnce() {
        if(Build.VERSION.SDK_INT<29 || Build.VERSION.SDK_INT>30) return;
        if(checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences settings=MeshService.settings(this);
        if(settings.getBoolean("askedBgLocation",false)) return;
        settings.edit().putBoolean("askedBgLocation",true).apply();
        new AlertDialog.Builder(this).setTitle("Keep receiving alerts after a restart")
            .setMessage("On this Android version, Bluetooth scanning counts as location access. To keep receiving alerts after the phone restarts, choose \"Allow all the time\" on the next screen.\n\nQuake Mesh never reads or stores your location and has no internet access.")
            .setNegativeButton("Not now",(d,w)->MeshService.note("Without \"Allow all the time\", open Quake Mesh once after each restart to resume alerts."))
            .setPositiveButton("Continue",(d,w)->requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},12))
            .show();
    }
    /** Deep sleep (Doze) can delay alert handling; ask once to be exempt. */
    private void askBatteryExemptionOnce() {
        PowerManager pm=getSystemService(PowerManager.class);
        SharedPreferences settings=MeshService.settings(this);
        if(pm==null || pm.isIgnoringBatteryOptimizations(getPackageName()) || settings.getBoolean("askedBattery",false)) return;
        settings.edit().putBoolean("askedBattery",true).apply();
        MeshService.note("Allow Quake Mesh to ignore battery optimisation so alerts arrive while the phone sleeps.");
        try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName()))); }
        catch(ActivityNotFoundException ignored) {}
    }
    private boolean locationEnabled() {
        LocationManager manager=getSystemService(LocationManager.class);
        if(Build.VERSION.SDK_INT>=28) return manager.isLocationEnabled();
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if(r!=10) return;
        for(String perm:required()) if(checkSelfPermission(perm)!=PackageManager.PERMISSION_GRANTED) { MeshService.note("Nearby devices permission is required. Allow it in App settings."); return; }
        prepare(pendingContinuous);
    }
    @Override protected void onActivityResult(int r,int c,Intent d) { super.onActivityResult(r,c,d); if(r==11 && c==RESULT_OK) prepare(pendingContinuous); }
}
