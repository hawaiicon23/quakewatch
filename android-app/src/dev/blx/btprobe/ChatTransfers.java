package dev.blx.btprobe;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded reassembly and per-peer selective acknowledgments; no Android dependencies. */
public final class ChatTransfers {
    public static final long EXPIRE_MS=10*60*1000;
    /** Per-fragment send limit. Transfers end earlier if ACK progress stalls (STALL_MS). */
    public static final int MAX_ATTEMPTS=24;
    /** A fragment is only repeated after this gap, so ACKs can arrive first (no wasted repairs). */
    public static final long REPAIR_GAP_MS=1200;
    /** Short messages (<= this many fragments) send every fragment twice up front: a lost copy rarely needs a repair round. */
    public static final int REDUNDANT_MAX=16;
    /** Give up when no receiver has acknowledged anything new for this long. */
    public static final long STALL_MS=15000;
    public static final class Reception {
        public final ChatProtocol.Message message; public final int window,bitmap,received,count;
        Reception(ChatProtocol.Message m,int w,int b,int r,int c) { message=m; window=w; bitmap=b; received=r; count=c; }
    }
    private static final class Assembly {
        final byte[][] parts; final BitSet seen=new BitSet(); long updated;
        Assembly(int count,long now) { parts=new byte[count][]; updated=now; }
    }
    private static final class Completed {
        final int count; final long time;
        Completed(int count,long time) { this.count=count; this.time=time; }
    }
    /** What has arrived so far of an incomplete transfer: shown live, before the CRC check. */
    public static final class Preview {
        public final int sender,id,type,received,count; public final String name,text;
        Preview(int s,int i,int t,String n,String x,int r,int c) { sender=s; id=i; type=t; name=n; text=x; received=r; count=c; }
    }
    /** Decode a UTF-8 slice, dropping a character that is cut off at the end. */
    static String utf8Prefix(byte[] b,int start,int end) {
        int cut=end;
        for(int i=end-1;i>=start && i>=end-4;i--) {
            int v=b[i]&255; if((v&0xC0)==0x80) continue;
            int need=v<0x80?1:v>=0xF0?4:v>=0xE0?3:v>=0xC0?2:1;
            if(i+need>end) cut=i; break;
        }
        return new String(b,start,Math.max(0,cut-start),StandardCharsets.UTF_8);
    }
    public static final class Inbox {
        private final LinkedHashMap<String,Assembly> active=new LinkedHashMap<>();
        private final LinkedHashMap<String,Completed> done=new LinkedHashMap<>();
        public int activeCount() { return active.size(); }
        /** Live view of an incomplete transfer, or null. Uses only the contiguous prefix from fragment 0. */
        public Preview preview(int sender,int id) {
            Assembly a=active.get(sender+":"+id); if(a==null) return null;
            ByteArrayOutputStream o=new ByteArrayOutputStream();
            for(byte[] part:a.parts) { if(part==null) break; o.write(part,0,part.length); }
            byte[] b=o.toByteArray(); int received=a.seen.cardinality(), count=a.parts.length;
            if(b.length<10) return new Preview(sender,id,0,null,"",received,count);
            int type=b[0]&255, n=b[1]&255, len=ByteBuffer.wrap(b,2,4).getInt();
            if(n<1 || n>12 || len<1 || (type!=ChatProtocol.TEXT && type!=ChatProtocol.JPEG && type!=ChatProtocol.JXL)) return null;
            if(b.length<10+n) return new Preview(sender,id,type,null,"",received,count);
            String name=utf8Prefix(b,10,10+n), text="";
            if(type==ChatProtocol.TEXT) text=utf8Prefix(b,10+n,Math.min(b.length,10+n+len));
            return new Preview(sender,id,type,name,text,received,count);
        }
        /** Keys (sender:id) of transfers still being received. */
        public List<int[]> activeTransfers() {
            List<int[]> out=new ArrayList<>();
            for(String k:active.keySet()) { String[] p=k.split(":"); out.add(new int[]{Integer.parseInt(p[0]),Integer.parseInt(p[1])}); }
            return out;
        }
        public void expire(long now) { active.entrySet().removeIf(e->now-e.getValue().updated>EXPIRE_MS); }
        public void clear() { active.clear(); done.clear(); }
        public void forget(int sender,int id) { String key=sender+":"+id; active.remove(key); done.remove(key); }
        public Reception receive(ChatProtocol.Packet p,long now) {
            if(p==null || p.kind!=ChatProtocol.DATA) return null;
            active.entrySet().removeIf(e->now-e.getValue().updated>EXPIRE_MS);
            done.entrySet().removeIf(e->now-e.getValue().time>EXPIRE_MS);
            String key=p.sender+":"+p.id; int window=p.index/32*32;
            Completed completed=done.get(key);
            if(completed!=null) return completed.count==p.count?new Reception(null,window,full(window,p.count),p.count,p.count):null;
            Assembly a=active.get(key);
            if(a==null) {
                // Bound concurrent allocation, including incomplete malicious transfers.
                if(active.size()>=8) active.remove(active.keySet().iterator().next());
                a=new Assembly(p.count,now); active.put(key,a);
            }
            if(a.parts.length!=p.count) return null;
            a.updated=now;
            if(a.parts[p.index]!=null && !Arrays.equals(a.parts[p.index],p.body)) return null;
            a.parts[p.index]=p.body.clone(); a.seen.set(p.index);
            ChatProtocol.Message message=null;
            if(a.seen.cardinality()==p.count) {
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                for(byte[] part:a.parts) bytes.write(part,0,part.length);
                try { message=ChatProtocol.message(bytes.toByteArray()); }
                catch(IllegalArgumentException invalid) { active.remove(key); return null; }
                active.remove(key); done.put(key,new Completed(p.count,now));
                while(done.size()>128) done.remove(done.keySet().iterator().next());
            }
            int bitmap=0;
            for(int i=0;i<32 && window+i<p.count;i++) if(a.seen.get(window+i)) bitmap|=1<<i;
            return new Reception(message,window,bitmap,a.seen.cardinality(),p.count);
        }
    }
    private static int full(int window,int count) { int n=Math.min(32,count-window); return n==32?-1:(1<<n)-1; }

    public static final class Outgoing {
        public final int sender,id; public final List<byte[]> frames;
        private final Map<Integer,BitSet> peers=new LinkedHashMap<>();
        private final Set<Integer> required=new LinkedHashSet<>();
        private final int[] attempts;
        private final long[] lastTry;
        private int cursor;
        private long lastProgress;
        public final long started;
        public long lastSent;
        public Outgoing(int sender,int id,byte[] envelope,Collection<Integer> expected,long now) { this(sender,id,envelope,expected,now,1); }
        /** version 2 = compact BC/2 frames (id must be 0..65535). */
        public Outgoing(int sender,int id,byte[] envelope,Collection<Integer> expected,long now,int version) {
            this.sender=sender; this.id=id; frames=version==2?ChatProtocol.fragment2(sender,id,envelope):ChatProtocol.fragment(sender,id,envelope); attempts=new int[frames.size()]; lastTry=new long[frames.size()]; started=now; lastProgress=now;
            // Snapshot peers seen in chat, not unrelated Bluetooth advertisers.
            for(int peer:expected) if(peer!=sender && required.size()<16) { required.add(peer); peers.put(peer,new BitSet()); }
        }
        public void acknowledge(ChatProtocol.Packet p) {
            if(p==null || p.kind!=ChatProtocol.ACK || p.id!=id || p.target()!=sender || p.sender==sender || p.count!=frames.size()) return;
            BitSet bits=peers.get(p.sender);
            if(bits==null) { if(peers.size()>=16) return; bits=new BitSet(); peers.put(p.sender,bits); }
            int before=bits.cardinality();
            for(int i=0;i<32 && p.index+i<frames.size();i++) if((p.bitmap()&(1<<i))!=0) bits.set(p.index+i);
            if(bits.cardinality()>before) lastProgress=Math.max(lastProgress,lastSent);
        }
        /** A connected receiver acknowledged saving this same envelope, so no broadcast repair is needed for it. */
        public void deliveredViaConnection(int peer,long now) {
            if(peer==sender)return;
            BitSet bits=peers.get(peer);
            if(bits==null){if(peers.size()>=16)return;bits=new BitSet();peers.put(peer,bits);}
            bits.set(0,frames.size());lastProgress=now;
        }
        public void resumeAfterConnection(long now){lastProgress=now;}
        public int delivered() { int n=0; for(BitSet bits:peers.values()) if(bits.cardinality()==frames.size()) n++; return n; }
        public boolean complete() {
            if(required.isEmpty()) return delivered()>0;
            for(int peer:required) if(peers.get(peer).cardinality()!=frames.size()) return false;
            return true;
        }
        private boolean needed(int index) {
            if(required.isEmpty()) {
                if(peers.isEmpty()) return true;
                // Repair toward the receiver with the most progress, while
                // broadcasting each repair to every nearby receiver.
                BitSet best=null;
                for(BitSet bits:peers.values()) if(best==null || bits.cardinality()>best.cardinality()) best=bits;
                return !best.get(index);
            }
            for(int peer:required) if(!peers.get(peer).get(index)) return true;
            return false;
        }
        public int acknowledged() {
            int best=0; for(BitSet bits:peers.values()) best=Math.max(best,bits.cardinality()); return best;
        }
        public int sent() { int n=0; for(int attempt:attempts) if(attempt>0) n++; return n; }
        public byte[] next(long now) {
            if(complete()) return null;
            // Pass 0: every fragment once, in order (receivers show text as it streams in).
            // Pass 1: short messages repeat each fragment once more right away.
            // Pass 2: repairs for fragments not yet acknowledged, spaced so ACKs can arrive first.
            int target=frames.size()<=REDUNDANT_MAX?2:1;
            long gap=Math.max(500,Math.min(REPAIR_GAP_MS,300+50L*frames.size()));
            for(int pass=0;pass<3;pass++) {
                for(int j=0;j<frames.size();j++) {
                    int i=(cursor+j)%frames.size();
                    boolean due=pass==0?attempts[i]==0:pass==1?attempts[i]<target:now-lastTry[i]>=gap;
                    if(needed(i) && attempts[i]<MAX_ATTEMPTS && due) {
                        attempts[i]++; lastTry[i]=now; cursor=(i+1)%frames.size(); lastSent=now; return frames.get(i);
                    }
                }
            }
            return null;
        }
        public boolean failed(long now) {
            if(complete()) return false;
            boolean remaining=false;
            for(int i=0;i<attempts.length;i++) if(needed(i) && attempts[i]<MAX_ATTEMPTS) { remaining=true; break; }
            boolean stalled=now-lastProgress>STALL_MS && now-started>STALL_MS;
            return (!remaining && now-lastSent>3000) || stalled || now-started>EXPIRE_MS;
        }
    }
}
