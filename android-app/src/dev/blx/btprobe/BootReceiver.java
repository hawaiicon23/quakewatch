package dev.blx.btprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Restarts listening after a reboot or an app update, but only if the user left it
 * on (MeshService stores "autoStart"; tapping Stop clears it). Starting a
 * connectedDevice foreground service from these broadcasts is allowed on Android 12+.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent) {
        String action=intent.getAction();
        String reason;
        if(Intent.ACTION_BOOT_COMPLETED.equals(action)) reason="phone restarted";
        else if(Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) reason="app updated";
        else return;
        if(!MeshService.settings(context).getBoolean("autoStart",false)) return;
        Intent start=new Intent(context,MeshService.class).setAction(MeshService.ACTION_START)
            .putExtra(MeshService.EXTRA_AUTO,true).putExtra(MeshService.EXTRA_AUTO_REASON,reason);
        try { context.startForegroundService(start); }
        catch(RuntimeException e) { Log.w("QuakeMesh","Auto-start failed: "+e); }
    }
}
