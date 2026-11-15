package dev.blx.btprobe;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

public final class DeviceIdentity {
    public static synchronized int id(Context c) {
        SharedPreferences p=c.getSharedPreferences("identity",Context.MODE_PRIVATE);
        if(!p.contains("id")) p.edit().putInt("id",new SecureRandom().nextInt()).commit();
        return p.getInt("id",0);
    }
    public static String name(Context c) {
        return c.getSharedPreferences("identity",Context.MODE_PRIVATE).getString("label",String.format(Locale.US,"Phone-%04X",id(c)&65535));
    }
    public static boolean validName(String name) {
        return name!=null && !name.trim().isEmpty() && name.trim().getBytes(StandardCharsets.UTF_8).length<=12
            && !name.matches("(?s).*\\p{Cntrl}.*");
    }
    public static void saveName(Context c,String name) {
        if(!validName(name)) throw new IllegalArgumentException("Use 1–12 UTF-8 bytes without control characters");
        if(!c.getSharedPreferences("identity",Context.MODE_PRIVATE).edit().putString("label",name.trim()).commit())
            throw new IllegalStateException("Could not save device name");
    }
    public static String display(String name,int id) { return String.format(Locale.US,"%s · %08X",name,id); }
}
