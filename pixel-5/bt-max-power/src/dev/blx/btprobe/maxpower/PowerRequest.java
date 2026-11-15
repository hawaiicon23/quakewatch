package dev.blx.btprobe.maxpower;

import android.bluetooth.le.AdvertisingSetParameters;
import android.os.Parcel;
import java.util.Arrays;

/** Experimental compatibility path, bounded to the modern API's 20 dBm ceiling. */
final class PowerRequest {
    private static byte[] encode(AdvertisingSetParameters value) {
        Parcel parcel=Parcel.obtain();
        try { value.writeToParcel(parcel,0); return parcel.marshall(); }
        finally { parcel.recycle(); }
    }
    private static AdvertisingSetParameters decode(byte[] value) {
        Parcel parcel=Parcel.obtain();
        try {
            parcel.unmarshall(value,0,value.length); parcel.setDataPosition(0);
            AdvertisingSetParameters result=AdvertisingSetParameters.CREATOR.createFromParcel(parcel);
            if(parcel.dataAvail()!=0) throw new IllegalStateException("Unknown parameter layout");
            return result;
        } finally { parcel.recycle(); }
    }
    static AdvertisingSetParameters withPower(AdvertisingSetParameters original,int requested) {
        if(requested < -127 || requested > 20) throw new IllegalArgumentException("Power outside experiment range");
        if(original.getTxPowerLevel()!=1) throw new IllegalArgumentException("Expected baseline power 1");
        // Discover the power field by comparing two otherwise identical valid
        // parcels. Do not rely on an Android-version-specific byte offset.
        byte[] one=encode(original);
        AdvertisingSetParameters zero=new AdvertisingSetParameters.Builder()
            .setLegacyMode(original.isLegacy()).setConnectable(original.isConnectable())
            .setScannable(original.isScannable()).setAnonymous(original.isAnonymous())
            .setIncludeTxPower(original.includeTxPower()).setPrimaryPhy(original.getPrimaryPhy())
            .setSecondaryPhy(original.getSecondaryPhy()).setInterval(original.getInterval())
            .setTxPowerLevel(0).build();
        byte[] other=encode(zero);
        if(one.length!=other.length || one.length%4!=0) throw new IllegalStateException("Unknown parameter size");
        int offset=-1;
        for(int i=0;i<one.length;i+=4) {
            boolean different=false;
            for(int j=0;j<4;j++) if(one[i+j]!=other[i+j]) different=true;
            if(different) {
                if(offset!=-1) throw new IllegalStateException("More than one parameter differs");
                offset=i;
            }
        }
        if(offset<0) throw new IllegalStateException("No power field found");
        byte[] patched=Arrays.copyOf(one,one.length);
        // Android Parcel.writeInt uses the platform's native little endian layout.
        Parcel word=Parcel.obtain();
        try {
            word.writeInt(1); byte[] check=word.marshall();
            for(int j=0;j<4;j++) if(one[offset+j]!=check[j] || other[offset+j]!=0)
                throw new IllegalStateException("Power field validation failed");
            word.setDataPosition(0); word.writeInt(requested); byte[] replacement=word.marshall();
            System.arraycopy(replacement,0,patched,offset,4);
        } finally { word.recycle(); }
        AdvertisingSetParameters result=decode(patched);
        if(result.getTxPowerLevel()!=requested || !Arrays.equals(encode(result),patched))
            throw new IllegalStateException("Power round-trip validation failed");
        return result;
    }
}
