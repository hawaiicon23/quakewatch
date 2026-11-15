package dev.blx.btprobe;
import java.io.*;
/** Bounded multiplexed frames on a persistent unpaired LE CoC stream. */
public final class DataProtocol {
    public static final int MAGIC=0x42445031,CHAT=1,PACKET=2,VOICE=3,ACK=4,HELLO=5,PING=6,PONG=7;
    public static final class Frame {
        public final int kind,sender,target,id; public final byte[] body;
        public Frame(int k,int s,int t,int i,byte[] b){kind=k;sender=s;target=t;id=i;body=b;}
    }
    private static void validate(int kind,int size)throws IOException {
        if(kind<CHAT||kind>PONG||size<0||size>ChatProtocol.MAX_ENVELOPE
            ||(kind==PACKET&&(size<1||size>24))||(kind==VOICE&&(size>VoiceProtocol.MAX_BYTES||size%2!=0))
            ||(kind==ACK&&size!=1)||(kind>=HELLO&&size!=0)||(kind==CHAT&&size<11))throw new IOException("Invalid data frame");
    }
    public static void write(DataOutputStream out,Frame f)throws IOException {
        validate(f.kind,f.body.length);out.writeInt(MAGIC);out.writeByte(f.kind);out.writeInt(f.sender);out.writeInt(f.target);out.writeInt(f.id);out.writeInt(f.body.length);out.write(f.body);out.flush();
    }
    public static Frame read(DataInputStream in,int target)throws IOException {
        if(in.readInt()!=MAGIC)throw new IOException("Incompatible data link");
        int kind=in.readUnsignedByte(),sender=in.readInt(),to=in.readInt(),id=in.readInt(),size=in.readInt();validate(kind,size);
        if(to!=target||sender==target)throw new IOException("Wrong data endpoint");
        byte[] body=new byte[size];in.readFully(body);return new Frame(kind,sender,to,id,body);
    }
}
