package dev.blx.btprobe;

import java.io.*;

/** Small framed PCM stream over an authenticated Bluetooth RFCOMM connection. */
public final class VoiceProtocol {
    public static final int MAGIC=0x42545631, HELLO=1, AUDIO=2, END=3;
    public static final int SAMPLE_RATE=8000, FRAME_BYTES=320, MAX_BYTES=640;
    public static final class Frame {
        public final int type, sequence; public final byte[] data;
        Frame(int t,int s,byte[] d) { type=t; sequence=s; data=d; }
    }
    private static void validate(int type,int size) throws IOException {
        if(size<0 || size>MAX_BYTES || (type!=HELLO && type!=AUDIO && type!=END)
            || (type==AUDIO && (size==0 || size%2!=0)) || (type==HELLO && (size<4 || size>68))
            || (type==END && size!=0)) throw new IOException("Invalid voice frame");
    }
    public static void write(DataOutputStream out,int type,int sequence,byte[] data) throws IOException {
        validate(type,data.length);
        out.writeInt(MAGIC); out.writeByte(type); out.writeInt(sequence); out.writeShort(data.length); out.write(data); out.flush();
    }
    public static Frame read(DataInputStream in) throws IOException {
        if(in.readInt()!=MAGIC) throw new IOException("Peer is not a compatible BT Probe walkie-talkie");
        int type=in.readUnsignedByte(), sequence=in.readInt(), size=in.readUnsignedShort();
        validate(type,size); byte[] data=new byte[size]; in.readFully(data); return new Frame(type,sequence,data);
    }
    public static int peak(byte[] pcm) {
        int peak=0;
        for(int i=0;i+1<pcm.length;i+=2) {
            int sample=(short)((pcm[i]&255)|(pcm[i+1]<<8)); peak=Math.max(peak,Math.abs(sample));
        }
        return peak;
    }
    public static byte[] tone(int frame) {
        byte[] data=new byte[FRAME_BYTES];
        for(int i=0;i<data.length/2;i++) {
            short sample=(short)(1800*Math.sin(2*Math.PI*440*(frame*160+i)/SAMPLE_RATE));
            data[2*i]=(byte)sample; data[2*i+1]=(byte)(sample>>8);
        }
        return data;
    }
}
