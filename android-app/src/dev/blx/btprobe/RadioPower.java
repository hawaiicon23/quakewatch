package dev.blx.btprobe;

import android.bluetooth.le.AdvertisingSetParameters;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Parcel;
import android.os.Build;
import java.util.Arrays;
import java.util.Locale;

public final class RadioPower {
    public static SharedPreferences prefs(Context c) { return c.getSharedPreferences("radio_power",Context.MODE_PRIVATE); }
    public static long revision(Context c) { return prefs(c).getLong("revision",0); }
    public static String signed(int n) { return String.format(Locale.US,"%+d",n); }
    public static String device() { return Build.MANUFACTURER+" "+Build.MODEL+" · Android "+Build.VERSION.RELEASE; }
    public static String method(int request) {
        try { builder(request).build(); return "Android controller API"; }
        catch(IllegalArgumentException e) { return "Validated compatibility path · "+Build.DEVICE; }
    }
    public static String lastResult(Context c) {
        SharedPreferences p=prefs(c);
        if(!Build.FINGERPRINT.equals(p.getString("last_device",Build.FINGERPRINT))) return "Not tested on this phone";
        return p.getString("last_status","No power request applied yet");
    }
    public static void save(Context c,int slider,boolean maximum,boolean boost,boolean root) {
        int request=PowerPolicy.requested(slider,maximum,boost,root);
        SharedPreferences p=prefs(c);
        if(!p.edit().putInt("slider",slider).putBoolean("force20",maximum).putBoolean("boost",boost)
            .putBoolean("root_verified",root).putLong("revision",revision(c)+1).putString("last_device",Build.FINGERPRINT)
            .putString("last_status","Saved "+signed(request)+" dBm · waiting for controller").commit())
            throw new IllegalStateException("Could not save power settings");
    }
    public static void report(Context c,long revision,int request,int reported,String owner) {
        if(revision!=revision(c)) return;
        String value="Requested "+signed(request)+" dBm · controller "+(reported==127?"unavailable":signed(reported)+" dBm");
        prefs(c).edit().putString("last_status",value).putInt("last_requested",request).putInt("last_reported",reported)
            .putString("last_method",method(request)).putString("last_owner",owner).putString("last_device",Build.FINGERPRINT).putLong("last_applied",System.currentTimeMillis()).apply();
    }
    public static void failure(Context c,long revision,String error) {
        if(revision==revision(c)) prefs(c).edit().putString("last_status",error).putString("last_device",Build.FINGERPRINT).apply();
    }
    public static int requested(Context c) {
        SharedPreferences p=prefs(c);
        return PowerPolicy.requested(p.getInt("slider",1),p.getBoolean("force20",false),p.getBoolean("boost",false),p.getBoolean("root_verified",false));
    }
    private static AdvertisingSetParameters.Builder builder(int power) {return builder(power,false);}
    private static AdvertisingSetParameters.Builder builder(int power,boolean connectable) {
        return new AdvertisingSetParameters.Builder().setLegacyMode(true).setConnectable(connectable)
            .setScannable(connectable).setInterval(AdvertisingSetParameters.INTERVAL_LOW).setTxPowerLevel(power);
    }
    public static AdvertisingSetParameters parameters(int requested) {return parameters(requested,false);}
    public static AdvertisingSetParameters parameters(int requested,boolean connectable) {
        try { return builder(requested,connectable).build(); }
        catch(IllegalArgumentException limitedBuilder) {
            if(requested<0 || requested>21) throw limitedBuilder;
            // Android 11 restricts its Java builder to +1 even though its
            // Parcelable/controller parameter carries an integer dBm request.
            // Locate the field by comparing two valid objects, rather than
            // assuming a private parcel layout. Never patch firmware or limits.
            Parcel zero=Parcel.obtain(), one=Parcel.obtain();
            try {
                builder(0,connectable).build().writeToParcel(zero,0);
                builder(1,connectable).build().writeToParcel(one,0);
                if(zero.dataSize()!=one.dataSize() || zero.dataSize()%4!=0) throw limitedBuilder;
                int offset=-1;
                for(int i=0;i<zero.dataSize();i+=4) {
                    zero.setDataPosition(i); one.setDataPosition(i);
                    int a=zero.readInt(), b=one.readInt();
                    if(a!=b) {
                        if(offset!=-1 || a!=0 || b!=1) throw limitedBuilder;
                        offset=i;
                    }
                }
                if(offset<0) throw limitedBuilder;
                zero.setDataPosition(offset); zero.writeInt(requested); zero.setDataPosition(0);
                byte[] expected=zero.marshall();
                AdvertisingSetParameters result=AdvertisingSetParameters.CREATOR.createFromParcel(zero);
                if(zero.dataAvail()!=0 || result.getTxPowerLevel()!=requested || !result.isLegacy() || result.isConnectable()!=connectable || result.isScannable()!=connectable) throw limitedBuilder;
                one.setDataSize(0); one.setDataPosition(0); result.writeToParcel(one,0);
                if(!Arrays.equals(expected,one.marshall())) throw limitedBuilder;
                return result;
            } finally { zero.recycle(); one.recycle(); }
        }
    }
}
