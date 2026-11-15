package dev.blx.btprobe;

public final class PowerPolicy {
    public static int requested(int slider,boolean force20,boolean boost,boolean rootVerified) {
        if(slider<0 || slider>20) throw new IllegalArgumentException("Power must be 0–20 dBm");
        if(boost) {
            if(!rootVerified) throw new IllegalStateException("Check root before requesting more than +20 dBm");
            return 21;
        }
        return force20?20:slider;
    }
}
