package dev.blx.btprobe;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;

/** Fixed membership, direct-neighbor observations. Never an authority/official alert. */
public final class QuakeConsensus {
    public static final int KIND=7, VERSION=1, MAX_NODES=16;
    public static final long MAX_AGE_MS=4000, RECEIVE_TTL_MS=2500, FUTURE_MS=1500;
    public final SortedSet<Integer> roster;
    public final int self, cohort, required;
    public final boolean test;
    private final Map<Integer,Report> reports=new HashMap<>();
    public static final class Report {
        public final int sender,sequence,state,rmsMilli; public final long wall,received;
        Report(int s,int q,int st,int rms,long w,long r) { sender=s; sequence=q; state=st; rmsMilli=rms; wall=w; received=r; }
    }
    public QuakeConsensus(int self,Collection<Integer> nodes,boolean test) {
        this.self=self; this.test=test; roster=new TreeSet<>(nodes); roster.add(self);
        if(roster.size()>MAX_NODES) throw new IllegalArgumentException("At most 16 nodes");
        required=Math.max(2,roster.size()/2+1);
        CRC32 c=new CRC32(); StringBuilder s=new StringBuilder("quake-v1:");
        for(int id:roster) s.append(String.format(Locale.US,"%08X,",id));
        c.update(s.toString().getBytes(StandardCharsets.US_ASCII)); cohort=(int)c.getValue();
    }
    public static SortedSet<Integer> parseRoster(String text,int self) {
        SortedSet<Integer> out=new TreeSet<>(); out.add(self);
        for(String t:text.trim().split("[\\s,;]+")) if(!t.isEmpty()) {
            if(!t.matches("[0-9a-fA-F]{8}")) throw new IllegalArgumentException("Use 8-digit node IDs separated by spaces or commas");
            out.add((int)Long.parseLong(t,16));
        }
        if(out.size()>MAX_NODES) throw new IllegalArgumentException("At most 16 nodes");
        return out;
    }
    public byte[] packet(int sequence,int state,double rms,long wall) {
        if(state<0||state>3||!Double.isFinite(rms)||rms<0) throw new IllegalArgumentException("Invalid observation");
        byte[] body=ByteBuffer.allocate(12).putInt(cohort).putInt((int)(wall/1000))
            .putShort((short)Math.min(65535,Math.round(rms*1000))).put((byte)((test?16:0)|state)).put((byte)VERSION).array();
        return Protocol.encode(KIND,self,sequence,body);
    }
    public boolean accept(Protocol.Packet p,long wall,long elapsed) {
        if(p==null||p.kind!=KIND||p.body.length!=12||!roster.contains(p.sender)||p.sender==self) return false;
        ByteBuffer b=ByteBuffer.wrap(p.body); if(b.getInt()!=cohort) return false;
        long time=Integer.toUnsignedLong(b.getInt())*1000;
        int rms=b.getShort()&65535, flags=b.get()&255, version=b.get()&255;
        if(version!=VERSION||(flags&~19)!=0||((flags&16)!=0)!=test||wall-time>MAX_AGE_MS||time-wall>FUTURE_MS) return false;
        Report old=reports.get(p.sender);
        if(old!=null && (time<old.wall || (time==old.wall && Integer.compareUnsigned(p.id,old.sequence)<=0))) return false;
        reports.put(p.sender,new Report(p.sender,p.id,flags&3,rms,time,elapsed)); return true;
    }
    public void local(int sequence,int state,double rms,long wall,long elapsed) {
        reports.put(self,new Report(self,sequence,state,(int)Math.min(65535,Math.max(0,Math.round(rms*1000))),wall,elapsed));
    }
    public List<Report> fresh(long wall,long elapsed) {
        List<Report> out=new ArrayList<>();
        for(Report r:reports.values()) if(elapsed>=r.received && elapsed-r.received<=RECEIVE_TTL_MS && wall-r.wall<=MAX_AGE_MS && r.wall-wall<=FUTURE_MS) out.add(r);
        return out;
    }
    public int positives(long wall,long elapsed) { int n=0; for(Report r:fresh(wall,elapsed)) if(r.state==QuakeDetector.CANDIDATE) n++; return n; }
    public String decision(long wall,long elapsed) {
        if(roster.size()<2) return "NEEDS NODES: configure at least two phones";
        int yes=positives(wall,elapsed), quiet=0;
        for(Report r:fresh(wall,elapsed)) if(r.state==QuakeDetector.QUIET) quiet++;
        if(yes>=required) return test?"TEST: correlated shaking detected":"CORRELATED SHAKING: possible earthquake";
        if(yes>0) return "UNCONFIRMED: "+yes+" candidate node(s); need "+required;
        if(quiet>=required) return "NO CORRELATED SHAKING observed";
        return "INSUFFICIENT DATA: waiting for ready nodes";
    }
}
