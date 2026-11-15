import dev.blx.btprobe.VoiceProtocol;
import java.io.*;
import java.util.*;

public class VoiceProtocolTest {
    static void check(boolean b) { if(!b) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        byte[] hello={0,0,0,1,'P','h','o','n','e'};
        VoiceProtocol.write(out,VoiceProtocol.HELLO,0,hello);
        for(int i=0;i<25;i++) VoiceProtocol.write(out,VoiceProtocol.AUDIO,i+1,VoiceProtocol.tone(i));
        VoiceProtocol.write(out,VoiceProtocol.END,26,new byte[0]);
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        check(Arrays.equals(hello,VoiceProtocol.read(in).data));
        for(int i=0;i<25;i++) {
            VoiceProtocol.Frame frame=VoiceProtocol.read(in);
            check(frame.type==VoiceProtocol.AUDIO && frame.sequence==i+1 && frame.data.length==320);
            check(VoiceProtocol.peak(frame.data)<=1800 && VoiceProtocol.peak(frame.data)>1700);
        }
        check(VoiceProtocol.read(in).type==VoiceProtocol.END);
        check(VoiceProtocol.peak(new byte[]{0,(byte)128})==32768);
        for(int type:new int[]{0,4,255}) {
            boolean rejected=false;
            try { VoiceProtocol.write(out,type,0,new byte[0]); } catch(IOException expected) { rejected=true; }
            check(rejected);
        }
        boolean rejected=false;
        try { VoiceProtocol.write(out,VoiceProtocol.AUDIO,0,new byte[641]); } catch(IOException expected) { rejected=true; }
        check(rejected);
        rejected=false;
        try { VoiceProtocol.read(new DataInputStream(new ByteArrayInputStream(new byte[11]))); } catch(IOException expected) { rejected=true; }
        check(rejected);
        System.out.println("VoiceProtocol OK: 25 audio frames / 500 ms, bounded framing and PCM peaks");
    }
}
