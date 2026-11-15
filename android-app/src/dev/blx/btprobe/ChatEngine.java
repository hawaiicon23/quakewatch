package dev.blx.btprobe;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/**
 * Nearby chat logic (formerly inside ChatActivity), owned by MeshService so chat keeps
 * receiving in the background. No radio code: MeshService asks for the next ACK or data
 * frame each slot and hands over received chat packets.
 *
 * Speed: new transfers use compact BC/2 frames (12 content bytes, sent in order) and the
 * radio changes payload every ~120 ms, so short messages arrive in well under a second.
 * Live text: previews() exposes what has arrived of incomplete messages so the UI can show
 * the text as it streams in, before the CRC check completes the message.
 */
final class ChatEngine {
    interface Host {
        int sender();
        boolean sendImage(int id,byte[] envelope,Collection<Integer> expected,BulkImages.Completion done);
        void cancelImageTransfers();
        boolean imageAdvertising();
        boolean connectedImageAvailable(Collection<Integer> expected);
        void wake();
        void chatChanged();
        void incomingMessage(String name,int from,String preview);
    }
    static final class Peer { String name="Peer"; int rssi; long seen,chatSeen; }
    static final class Transfer { final ChatTransfers.Outgoing data; final long row; final boolean image; boolean bulkPending; int connected; Transfer(ChatTransfers.Outgoing d,long row,boolean image) { data=d; this.row=row; this.image=image; } }

    private final Context context;
    private final Host host;
    private final SecureRandom random=new SecureRandom();
    final ChatStore store;
    private final ChatTransfers.Inbox inbox=new ChatTransfers.Inbox();
    final LinkedHashMap<Integer,Peer> peers=new LinkedHashMap<>();
    /** ACKs waiting for a slot. A transfer's final ACK is repeated (FINAL_ACK_REPEATS) so a missed one doesn't stall the sender. */
    private static final class PendingAck { byte[] data; int repeats; long due; }
    private static final int FINAL_ACK_REPEATS=3; private static final long ACK_REPEAT_MS=300;
    private final LinkedHashMap<String,PendingAck> acknowledgments=new LinkedHashMap<>();
    private final LinkedHashMap<String,Long> lastAck=new LinkedHashMap<>();
    final ArrayDeque<Transfer> outgoing=new ArrayDeque<>();
    /** Bumped whenever stored history changes, so the UI only rebuilds the thread when needed. */
    int historyVersion;
    String progress="Messages stream in as they arrive. Images may take a while.";
    private long lastProgress;
    String imageLinkStatus="";float imageLinkProgress;boolean imageLinkBusy;
    void imageLinkStatus(String status,float fraction,boolean busy){imageLinkStatus=status;imageLinkProgress=fraction;imageLinkBusy=busy;host.chatChanged();}

    ChatEngine(Context context,Host host) {
        this.context=context; this.host=host; store=new ChatStore(context); store.interrupted();
    }
    void close() { for(Transfer t:outgoing) store.status(t.row,"Interrupted · tap Retry"); outgoing.clear(); store.close(); }
    void radioStopped() {
        // Queued transfers stay queued and resume when the radio is back.
        for(Transfer t:outgoing){t.bulkPending=false;t.data.resumeAfterConnection(SystemClock.elapsedRealtime());}
        imageLinkBusy=false;acknowledgments.clear(); host.chatChanged();
    }

    // ---------------------------------------------------------------- sending
    /** Returns null on success, or a message to show. */
    String enqueue(int type,byte[] content,ChatStore.Entry retry) {
        if(outgoing.size()>=4) return "Four transfers are already queued";
        int sender=host.sender();
        try {
            byte[] envelope=ChatProtocol.envelope(type,retry==null?DeviceIdentity.name(context):retry.name,content); int id;
            if(retry!=null) {
                id=retry.id;
                for(Transfer existing:outgoing) if(existing.data.id==id) return "This message is already queued";
            } else do { id=random.nextInt(65536); } while(store.contains(sender,id));
            List<Integer> expected=new ArrayList<>(); long now=SystemClock.elapsedRealtime();
            for(Map.Entry<Integer,Peer> e:peers.entrySet()) if(e.getValue().chatSeen>0 && now-e.getValue().chatSeen<30000) {if(expected.size()<16)expected.add(e.getKey());}
            if(type!=ChatProtocol.TEXT&&!host.imageAdvertising()&&!host.connectedImageAvailable(expected))return "No compatible connected image peers. Wait for discovery or enable image broadcast fallback in Settings.";
            // Old history rows may carry 32-bit IDs from BC/1: retry those in BC/1 so the receiver deduplicates.
            int version=(id>=0 && id<=65535)?2:1;
            ChatTransfers.Outgoing data=new ChatTransfers.Outgoing(sender,id,envelope,expected,now,version);
            long row;
            if(retry==null) row=store.add(sender,id,ChatProtocol.message(envelope),true,"Sending");
            else { row=retry.row; store.status(row,"Sending"); }
            if(row<0) return "Could not save outgoing message";
            android.util.Log.i("BTImage","Queue id="+id+" type="+type+" expected="+expected);
            Transfer transfer=new Transfer(data,row,type!=ChatProtocol.TEXT);outgoing.add(transfer);historyVersion++;
            transfer.bulkPending=host.sendImage(id,envelope,expected,delivered->{
                android.util.Log.i("BTImage","Apply completion id="+transfer.data.id+" queued="+outgoing.contains(transfer));
                if(!outgoing.contains(transfer))return;
                transfer.bulkPending=false;transfer.connected=delivered.size();
                long resumed=SystemClock.elapsedRealtime();
                for(int peer:delivered)transfer.data.deliveredViaConnection(peer,resumed);
                transfer.data.resumeAfterConnection(resumed);
                android.util.Log.i("BTImage","Applied id="+transfer.data.id+" complete="+transfer.data.complete()+" delivered="+transfer.data.delivered());
                finishTransfers();host.chatChanged();host.wake();
            });
            progress="Sending · "+data.frames.size()+" packets"+(expected.isEmpty()?" · no chat peers seen yet":" · to "+expected.size()+" nearby");
            host.chatChanged(); host.wake();
            return null;
        } catch(Exception e) { return "Cannot send: "+e.getMessage(); }
    }
    void cancelAll() {
        host.cancelImageTransfers();imageLinkBusy=false;imageLinkStatus="";
        for(Transfer t:outgoing) store.status(t.row,"Canceled · tap Retry");
        outgoing.clear(); historyVersion++; progress="Transfers canceled"; host.chatChanged();
    }
    boolean hasAck() {
        long now=SystemClock.elapsedRealtime();
        for(PendingAck a:acknowledgments.values()) if(a.due<=now) return true;
        return false;
    }
    /** True while ACKs (including repeats not yet due) are queued: keeps the radio checking back quickly. */
    boolean acksPending() { return !acknowledgments.isEmpty(); }
    byte[] nextAck() {
        long now=SystemClock.elapsedRealtime();
        for(Iterator<Map.Entry<String,PendingAck>> i=acknowledgments.entrySet().iterator();i.hasNext();) {
            Map.Entry<String,PendingAck> e=i.next(); PendingAck a=e.getValue();
            if(a.due>now) continue;
            i.remove();
            if(--a.repeats>0) { a.due=now+ACK_REPEAT_MS; acknowledgments.put(e.getKey(),a); }
            lastAck.put(e.getKey(),now);
            while(lastAck.size()>256) lastAck.remove(lastAck.keySet().iterator().next());
            return a.data;
        }
        return null;
    }
    boolean hasData() { finishTransfers(); for(Transfer t:outgoing)if(!t.bulkPending)return true;return false; }
    byte[] nextData(long now) {
        Transfer t=null;for(Transfer queued:outgoing)if(!queued.bulkPending){t=queued;break;}if(t==null)return null;
        byte[] frame=t.data.next(now);
        if(now-lastProgress>400) {
            progress="Sending · "+t.data.sent()+" / "+t.data.frames.size()+" packets · "+t.data.acknowledged()+" acknowledged"+(outgoing.size()>1?" · "+outgoing.size()+" queued":"");
            lastProgress=now; host.chatChanged();
        }
        // null: every fragment has used its attempts and we're waiting for ACKs (or the timeout).
        if(frame==null) finishTransfers();
        return frame;
    }
    void finishTransfers() {
        boolean changed=false; long now=SystemClock.elapsedRealtime();
        for(Iterator<Transfer> i=outgoing.iterator();i.hasNext();) {
            Transfer t=i.next();
            if(!t.bulkPending&&(t.data.complete() || t.data.failed(now) || (t.image&&!host.imageAdvertising()))) {
                String result=t.data.complete()?"Delivered to "+t.data.delivered()+" peer(s) · "+(now-t.data.started)+" ms"
                    :t.data.delivered()>0?"Delivered to "+t.data.delivered()+" · some peers incomplete · tap Retry":t.image&&!host.imageAdvertising()?"Not delivered · image broadcast fallback off · tap Retry":"No complete acknowledgment · tap Retry";
                if(t.connected>0)result+=" · data link";
                store.status(t.row,result); i.remove(); changed=true; progress=result;
                android.util.Log.i("BTChat",result);
            }
        }
        if(changed) { historyVersion++; host.chatChanged(); }
    }

    // ---------------------------------------------------------------- receiving
    private Peer peer(int id,int rssi) {
        Peer p=peers.get(id); if(p==null) { p=new Peer(); peers.put(id,p); }
        p.rssi=rssi; p.seen=SystemClock.elapsedRealtime();
        while(peers.size()>64) peers.remove(peers.keySet().iterator().next());
        return p;
    }
    void observePresence(int id,int rssi){peer(id,rssi);}
    void observeBeacon(int id,String name,int rssi) { peer(id,rssi).name=name; }
    void received(ChatProtocol.Packet p,int rssi) {
        int sender=host.sender();
        Peer known=peer(p.sender,rssi); known.chatSeen=SystemClock.elapsedRealtime();
        if(p.kind==ChatProtocol.HELLO||p.kind==ChatProtocol.IMAGE_HELLO||p.kind==ChatProtocol.LINK_HELLO) return;
        if(p.kind==ChatProtocol.ACK) {
            boolean mine=false; for(Transfer t:outgoing) if(t.data.id==p.id) { t.data.acknowledge(p); mine=true; }
            if(mine) { finishTransfers(); host.chatChanged(); }
            return;
        }
        // A completed data-link message must not reassemble, decode or notify from advertising.
        if(p.kind==ChatProtocol.DATA) {
            byte[] saved=store.envelope(p.sender,p.id);
            if(saved!=null) {
                int chunk=p.version==2?ChatProtocol.CHUNK2:ChatProtocol.CHUNK;
                int count=(saved.length+chunk-1)/chunk,offset=p.index*chunk;
                if(p.count!=count||offset>=saved.length||!Arrays.equals(p.body,Arrays.copyOfRange(saved,offset,Math.min(saved.length,offset+chunk))))return;
                inbox.forget(p.sender,p.id);
                int start=p.index/32*32,bits=Math.min(32,p.count-start);String key=p.sender+":"+p.id+":"+start;
                Long last=lastAck.get(key);long now=SystemClock.elapsedRealtime();
                if(last==null||now-last>=650){PendingAck ack=new PendingAck();ack.data=ChatProtocol.ackFor(p,sender,start,bits==32?-1:(1<<bits)-1);ack.repeats=1;ack.due=now;acknowledgments.put(key,ack);}
                while(acknowledgments.size()>64)acknowledgments.remove(acknowledgments.keySet().iterator().next());
                host.wake();return;
            }
        }
        long now=SystemClock.elapsedRealtime();
        ChatTransfers.Reception reception=inbox.receive(p,now); if(reception==null) return;
        if(reception.message!=null) {
            if(reception.message.type!=ChatProtocol.TEXT) {
                Bitmap check=ChatImage.decode(reception.message.content);
                if(check==null) { inbox.forget(p.sender,p.id); return; } check.recycle();
            }
            known.name=reception.message.name;
            if(store.contains(p.sender,p.id)) {
                if(!store.matches(p.sender,p.id,reception.message)){inbox.forget(p.sender,p.id);return;}
            } else try {
                store.add(p.sender,p.id,reception.message,false,"Received"); historyVersion++;
                String text=reception.message.type==ChatProtocol.TEXT?new String(reception.message.content,StandardCharsets.UTF_8):"📷 Image";
                progress="Received from "+DeviceIdentity.display(known.name,p.sender)+" · "+p.count+" packets";
                android.util.Log.i("BTChat","Received "+(reception.message.type==ChatProtocol.TEXT?"text":"image")+" from "+String.format(Locale.US,"%08X",p.sender)+" · "+p.count+" packets");
                host.incomingMessage(known.name,p.sender,text);
            } catch(Exception e) { inbox.forget(p.sender,p.id); progress="Could not save incoming message"; }
        }
        String key=p.sender+":"+p.id+":"+reception.window;
        Long last=lastAck.get(key);
        // Coalesce the newest bitmap while a reply waits; ACK at once when a transfer completes.
        boolean complete=reception.received==reception.count;
        if(acknowledgments.containsKey(key) || last==null || now-last>=650 || complete) {
            PendingAck a=acknowledgments.get(key);
            if(a==null) { a=new PendingAck(); a.due=now; acknowledgments.put(key,a); }
            a.data=ChatProtocol.ackFor(p,sender,reception.window,reception.bitmap); // newest bitmap
            a.repeats=Math.max(a.repeats,complete?FINAL_ACK_REPEATS:1);
            if(complete) a.due=Math.min(a.due,now);
            while(acknowledgments.size()>64) acknowledgments.remove(acknowledgments.keySet().iterator().next());
            host.wake();
        }
        host.chatChanged(); // live preview update
    }

    /** Worker already decoded the image; commit and notify on the service's main thread. */
    boolean saveConnectedImage(int sender,int id,byte[] envelope) {
        ChatProtocol.Message message=ChatProtocol.message(envelope);

        inbox.forget(sender,id);
        if(store.contains(sender,id))return store.matches(sender,id,message);
        try {
            if(store.add(sender,id,message,false,"Received · data link")<0)return false;
            historyVersion++;progress="Message received through connected Bluetooth";
            host.incomingMessage(message.name,sender,message.type==ChatProtocol.TEXT?message.text():"📷 Image");host.chatChanged();return true;
        }catch(Exception failure){return false;}
    }

    /** Messages still arriving, with the text received so far (oldest first). */
    List<ChatTransfers.Preview> previews() {
        inbox.expire(SystemClock.elapsedRealtime());
        List<ChatTransfers.Preview> out=new ArrayList<>();
        for(int[] k:inbox.activeTransfers()) {
            ChatTransfers.Preview v=inbox.preview(k[0],k[1]);
            if(v!=null && v.received<v.count) out.add(v);
        }
        return out;
    }
    String peerName(int id) { Peer p=peers.get(id); return p==null?"Peer":p.name; }
    String peersText() {
        long now=SystemClock.elapsedRealtime(); StringBuilder text=new StringBuilder();
        for(Map.Entry<Integer,Peer> e:peers.entrySet()) {
            Peer p=e.getValue(); if(now-p.seen>60000) continue;
            if(text.length()>0) text.append("\n");
            text.append(DeviceIdentity.display(p.name,e.getKey())).append(" · ").append(p.rssi).append(" dBm").append(p.chatSeen>0 && now-p.chatSeen<30000?" · chat":"");
        }
        return text.length()==0?"No nearby peers yet":text.toString();
    }
}
