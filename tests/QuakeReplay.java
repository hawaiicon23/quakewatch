import dev.blx.btprobe.*;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;
/** Recorded single-component accelerograms, as sensor samples including gravity. */
public final class QuakeReplay {
    public static void main(String[] args) throws Exception {
        System.out.println("record,dt_s,samples,pga_m_s2,first_candidate_s,candidate_windows,two_node_corroborated,isolated_node_corroborated");
        for(String path:args) {
            String text=new String(Files.readAllBytes(Paths.get(path)),StandardCharsets.UTF_8);
            Matcher header=Pattern.compile("NPTS=\\s*(\\d+),\\s*DT=\\s*([.0-9]+)\\s*SEC").matcher(text);
            if(!header.find()) throw new IllegalArgumentException("Missing NPTS/DT: "+path);
            int expected=Integer.parseInt(header.group(1)); double dt=Double.parseDouble(header.group(2));
            String[] tokens=text.substring(header.end()).split("\\*\\*\\* End Data \\*\\*\\*")[0].trim().split("\\s+");
            if(tokens.length!=expected) throw new AssertionError("Sample count "+path);
            QuakeDetector d=new QuakeDetector(false), second=new QuakeDetector(false);
            QuakeConsensus a=new QuakeConsensus(1,Arrays.asList(1,2),false), b=new QuakeConsensus(2,Arrays.asList(1,2),false);
            QuakeConsensus isolated=new QuakeConsensus(1,Arrays.asList(1,2),false);
            boolean together=false, alone=false; int sequence=0; double lastHeartbeat=-1;
            for(int i=0;i<2000;i++) { double t=1+i*.02; d.sample((long)(t*1e9),.003*Math.sin(i*.71),.002*Math.sin(i*.3),9.80665,40); second.sample((long)(t*1e9),0,.003*Math.sin(i*.71),9.80665,40); }
            double peak=0, first=-1; int windows=0, lastState=2;
            for(int i=0;i<tokens.length;i++) {
                double value=Double.parseDouble(tokens[i])*9.80665; peak=Math.max(peak,Math.abs(value));
                d.sample((long)((41+i*dt)*1e9),value,0,9.80665,40);
                if(d.state==3) { if(first<0) first=i*dt; if(lastState!=3) windows++; }
                lastState=d.state;
                second.sample((long)((41+i*dt)*1e9),0,value*.7,9.80665,40);
                if(i*dt-lastHeartbeat>=.5) {
                    lastHeartbeat=i*dt; sequence++;
                    long wall=1700000000000L+(long)(i*dt*1000), elapsed=10000+(long)(i*dt*1000);
                    a.local(sequence,d.state,d.rms,wall,elapsed); b.local(sequence,second.state,second.rms,wall,elapsed);
                    isolated.local(sequence,d.state,d.rms,wall,elapsed);
                    Protocol.Packet pa=Protocol.decode(a.packet(sequence,d.state,d.rms,wall)), pb=Protocol.decode(b.packet(sequence,second.state,second.rms,wall));
                    if(sequence%3!=0) { a.accept(pb,wall,elapsed); b.accept(pa,wall,elapsed); a.accept(pb,wall,elapsed); }
                    isolated.accept(Protocol.decode(b.packet(sequence,QuakeDetector.QUIET,.003,wall)),wall,elapsed);
                    together|=a.positives(wall,elapsed)>=a.required && b.positives(wall,elapsed)>=b.required;
                    alone|=isolated.positives(wall,elapsed)>=isolated.required;
                }
            }
            System.out.printf(Locale.US,"%s,%.5f,%d,%.5f,%.3f,%d,%s,%s%n",Paths.get(path).getFileName(),dt,tokens.length,peak,first,windows,together,alone);
            if(!together||alone) throw new AssertionError("Multi-node replay decision incorrect: "+path);
            if(first<0) throw new AssertionError("No candidate for recorded strong motion: "+path);
        }
    }
}
