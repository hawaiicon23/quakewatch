import dev.blx.btprobe.*;
import java.io.*;
import java.util.*;
public class DataProtocolTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static byte[] wire(DataProtocol.Frame f)throws Exception{ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataProtocol.write(new DataOutputStream(bytes),f);return bytes.toByteArray();}
    static void rejected(byte[] data,int target)throws Exception{try{DataProtocol.read(new DataInputStream(new ByteArrayInputStream(data)),target);throw new AssertionError("Accepted bad frame");}catch(IOException expected){}}
    public static void main(String[] args)throws Exception{
        byte[] chat=ChatProtocol.envelope(ChatProtocol.TEXT,"Node","fast text".getBytes("UTF-8"));
        ByteArrayOutputStream stream=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(stream);
        int[] kinds={DataProtocol.HELLO,DataProtocol.CHAT,DataProtocol.PACKET,DataProtocol.VOICE,DataProtocol.ACK,DataProtocol.PING,DataProtocol.PONG};
        byte[][] payloads={new byte[0],chat,new byte[24],VoiceProtocol.tone(0),new byte[]{1},new byte[0],new byte[0]};
        for(int i=0;i<kinds.length;i++)DataProtocol.write(out,new DataProtocol.Frame(kinds[i],1,2,i,payloads[i]));
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(stream.toByteArray()));
        for(int i=0;i<kinds.length;i++){DataProtocol.Frame f=DataProtocol.read(in,2);check(f.kind==kinds[i]&&f.sender==1&&f.target==2&&f.id==i&&Arrays.equals(f.body,payloads[i]));}
        check(in.available()==0);
        byte[] valid=wire(new DataProtocol.Frame(DataProtocol.CHAT,1,2,42,chat));
        rejected(valid,3);rejected(valid,1);
        for(int cut=0;cut<valid.length;cut++)rejected(Arrays.copyOf(valid,cut),2);
        byte[] wrong=valid.clone();wrong[0]=0;rejected(wrong,2);
        for(int kind:new int[]{0,8,255}){wrong=valid.clone();wrong[4]=(byte)kind;rejected(wrong,2);}
        for(int n:new int[]{-1,ChatProtocol.MAX_ENVELOPE+1,Integer.MAX_VALUE}){wrong=valid.clone();java.nio.ByteBuffer.wrap(wrong).putInt(17,n);rejected(wrong,2);}
        byte[] hello=ChatProtocol.linkHello(17,129,131);ChatProtocol.Packet beacon=ChatProtocol.decode(hello);check(beacon!=null&&beacon.kind==ChatProtocol.LINK_HELLO&&beacon.body.length==4);
        hello[14]=0;hello[15]=0;check(ChatProtocol.decode(hello)==null);
        byte[] imageHello=ChatProtocol.imageHello(17,129);check(ChatProtocol.decode(imageHello).kind==ChatProtocol.IMAGE_HELLO);
        for(int invalid:new int[]{0,65536})try{ChatProtocol.linkHello(17,129,invalid);throw new AssertionError();}catch(IllegalArgumentException expected){}
        System.out.println("DataProtocol OK");
    }
}
