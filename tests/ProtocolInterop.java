import dev.blx.btprobe.Protocol;
import java.io.*;
import java.util.*;

public class ProtocolInterop {
    public static void main(String[] args) throws Exception {
        BufferedReader reader=new BufferedReader(new InputStreamReader(System.in));
        String line;
        while((line=reader.readLine())!=null) {
            byte[] raw=new byte[line.length()/2];
            for(int i=0;i<raw.length;i++) raw[i]=(byte)Integer.parseInt(line.substring(i*2,i*2+2),16);
            Protocol.Packet p=Protocol.decode(raw);
            if(p==null) { System.out.println("invalid"); continue; }
            if(!Arrays.equals(raw,Protocol.encode(p.kind,p.sender,p.id,p.body,p.hops))) throw new AssertionError("Round trip failed");
            StringBuilder body=new StringBuilder(); for(byte b:p.body) body.append(String.format("%02x",b&255));
            System.out.println(p.kind+" "+Integer.toUnsignedString(p.sender)+" "+Integer.toUnsignedString(p.id)+" "+body);
        }
    }
}
