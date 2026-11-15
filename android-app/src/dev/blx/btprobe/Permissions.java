package dev.blx.btprobe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import java.util.Arrays;

/** Shared "get everything ready, then start the background radio" flow for all screens. */
final class Permissions {
    static final int REQUEST_PERMISSIONS=10, REQUEST_ENABLE_BT=11, REQUEST_BACKGROUND=12;
    /** null = keep the current continuous-test mode. */
    private static Boolean pendingContinuous;

    static String[] required() {
        return Build.VERSION.SDK_INT>=31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.BLUETOOTH_CONNECT}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    private static String[] requested() {
        if(Build.VERSION.SDK_INT<33) return required();
        String[] r=required(); String[] all=Arrays.copyOf(r,r.length+1);
        all[r.length]=Manifest.permission.POST_NOTIFICATIONS; // optional: alert/chat notifications
        return all;
    }
    static void startMesh(Activity a,Boolean continuous) {
        pendingContinuous=continuous;
        for(String p:required()) if(a.checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED) { a.requestPermissions(requested(),REQUEST_PERMISSIONS); return; }
        BluetoothManager manager=a.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null) { MeshService.note("This device has no Bluetooth adapter."); return; }
        if(!adapter.isEnabled()) {
            // Sampling starts even if the user leaves the Bluetooth enable dialog unanswered.
            Intent sensing=new Intent(a,MeshService.class).setAction(MeshService.ACTION_START);
            if(continuous!=null)sensing.putExtra(MeshService.EXTRA_CONTINUOUS,continuous.booleanValue());
            a.startForegroundService(sensing);
            a.startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),REQUEST_ENABLE_BT);return;
        }
        if(Build.VERSION.SDK_INT<31 && !locationEnabled(a)) {
            MeshService.note("Enable Location in Android settings for BLE scanning, then start again.");
            a.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return;
        }
        askBatteryExemptionOnce(a);
        askBackgroundLocationOnce(a);
        Intent i=new Intent(a,MeshService.class).setAction(MeshService.ACTION_START);
        if(continuous!=null) i.putExtra(MeshService.EXTRA_CONTINUOUS,continuous.booleanValue());
        a.startForegroundService(i);
    }
    /**
     * Android 10-11: a service started in the background (boot, app update) gets no BLE scan
     * results. Any visible screen re-starts it from the foreground, which restores scanning.
     */
    static void resumeIfBlocked(Activity a) {
        MeshService s=MeshService.get();
        if(s!=null && s.needsForegroundRestart) a.startForegroundService(new Intent(a,MeshService.class).setAction(MeshService.ACTION_START));
    }
    static void onResult(Activity a,int request) {
        if(request!=REQUEST_PERMISSIONS) return;
        for(String p:required()) if(a.checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED) { MeshService.note("Nearby devices permission is required. Allow it in App settings."); return; }
        startMesh(a,pendingContinuous);
    }
    /** Returns true if the result belonged to this flow. */
    static boolean onActivityResult(Activity a,int request,int result) {
        if(request!=REQUEST_ENABLE_BT) return false;
        // Some phones report RESULT_CANCELED even after Bluetooth turned on: check the adapter instead.
        BluetoothManager m=a.getSystemService(BluetoothManager.class);
        boolean on=m!=null && m.getAdapter()!=null && m.getAdapter().isEnabled();
        if(result==Activity.RESULT_OK || on) startMesh(a,pendingContinuous);
        return true;
    }
    private static boolean locationEnabled(Activity a) {
        LocationManager m=a.getSystemService(LocationManager.class);
        if(Build.VERSION.SDK_INT>=28) return m.isLocationEnabled();
        return m.isProviderEnabled(LocationManager.GPS_PROVIDER) || m.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }
    /** Deep sleep (Doze) can delay alerts and chat; ask once to be exempt. */
    private static void askBatteryExemptionOnce(Activity a) {
        PowerManager pm=a.getSystemService(PowerManager.class);
        SharedPreferences s=MeshService.settings(a);
        if(pm==null || pm.isIgnoringBatteryOptimizations(a.getPackageName()) || s.getBoolean("askedBattery",false)) return;
        s.edit().putBoolean("askedBattery",true).apply();
        MeshService.note("Allow the app to ignore battery optimisation so alerts and messages arrive while the phone sleeps.");
        try { a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+a.getPackageName()))); }
        catch(ActivityNotFoundException ignored) {}
    }
    /** Android 10-11: without "Allow all the time", BLE scanning is blocked after a reboot. Ask once. */
    private static void askBackgroundLocationOnce(Activity a) {
        if(Build.VERSION.SDK_INT<29 || Build.VERSION.SDK_INT>30) return;
        if(a.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences s=MeshService.settings(a);
        if(s.getBoolean("askedBgLocation",false)) return;
        s.edit().putBoolean("askedBgLocation",true).apply();
        new AlertDialog.Builder(a).setTitle("Keep receiving after a restart")
            .setMessage("On this Android version, Bluetooth scanning counts as location access. To keep receiving chat and alerts after the phone restarts, choose \"Allow all the time\" on the next screen.\n\nThe app never reads or stores your location and has no internet access.")
            .setNegativeButton("Not now",(d,w)->MeshService.note("Without \"Allow all the time\", open the app once after each restart to resume."))
            .setPositiveButton("Continue",(d,w)->a.requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},REQUEST_BACKGROUND))
            .show();
    }
}
