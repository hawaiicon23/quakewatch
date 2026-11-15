package dev.blx.btprobe;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Unpaired LE CoC image links. The primary broadcast/scan is never stopped for a transfer. */
@android.annotation.SuppressLint("MissingPermission") // MeshService gates Nearby Devices permission; all calls handle revocation.
final class BulkImages {
    interface Host {
        int sender();
        int dataPsm();
        boolean saveImage(int sender,int id,byte[] envelope) throws Exception;
        void imageLinkStatus(String status,float progress,boolean busy);
    }
    interface Completion {void done(Set<Integer> delivered);}
    private static final long PEER_MS=30000, DEADLINE_MS=12000;
    private static final class Peer {
        final BluetoothDevice device;final int psm;final long seen;
        Peer(BluetoothDevice d,int p,long now){device=d;psm=p;seen=now;}
    }
    private final Context context;
    private final Host host;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<Integer,Peer> peers=new LinkedHashMap<>(); // main thread only
    private final Set<BluetoothSocket> sockets=ConcurrentHashMap.newKeySet();
    private final Set<BluetoothSocket> outgoing=ConcurrentHashMap.newKeySet();
    private final ImageFanout senders=new ImageFanout();
    private final Set<BluetoothSocket> incoming=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor receivers=new ThreadPoolExecutor(3,3,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(3));
    private volatile boolean stopped;
    private volatile int cancellation;
    private BluetoothServerSocket server;
    private BluetoothLeAdvertiser advertiser;
    private AdvertisingSetCallback beacon;
    private AdvertisingSet advertising;
    private int psm;
    static final class LinkState {
        final int peer;int sending,receiving,connecting;long updated;String result="";
        LinkState(int peer){this.peer=peer;}
    }
    private final Map<Integer,LinkState> linkStates=new LinkedHashMap<>();
    List<LinkState> links(){return new ArrayList<>(linkStates.values());}
    int activeLinks(){int count=0;for(LinkState state:linkStates.values())count+=state.sending+state.receiving;return count;}
    private void linkEvent(int peer,int sending,int receiving,int connecting,String result){
        main.post(()->{
            if(stopped)return;
            LinkState state=linkStates.get(peer);
            if(state==null){state=new LinkState(peer);linkStates.put(peer,state);}
            state.sending=Math.max(0,state.sending+sending);state.receiving=Math.max(0,state.receiving+receiving);state.connecting=Math.max(0,state.connecting+connecting);
            state.updated=SystemClock.elapsedRealtime();if(!result.isEmpty())state.result=result;
            if(linkStates.size()>64){Iterator<LinkState> i=linkStates.values().iterator();while(i.hasNext()&&linkStates.size()>64){LinkState old=i.next();if(old.sending+old.receiving+old.connecting==0)i.remove();}}
            MeshService.changed();
        });
    }
    BulkImages(Context c,Host host){context=c;this.host=host;}
    void start(BluetoothAdapter adapter) {
        if(Build.VERSION.SDK_INT<29||adapter==null)return;
        try {
            server=adapter.listenUsingInsecureL2capChannel();psm=server.getPsm();
            advertiser=adapter.getBluetoothLeAdvertiser();
            if(advertiser==null)throw new IOException("No advertiser");
            beacon=new AdvertisingSetCallback(){
                @Override public void onAdvertisingSetStarted(AdvertisingSet set,int power,int status){
                    if(stopped)return;
                    if(status!=0){MeshService.note("Connected images unavailable (advertising status "+status+"); using broadcast fallback");stop();return;}
                    advertising=set;MeshService.note("Connected image listener ready · PSM "+psm+" · no pairing · controller "+power+" dBm");
                }
                @Override public void onAdvertisingEnabled(AdvertisingSet set,boolean enabled,int status){
                    if(!stopped&&!enabled&&status==0)main.postDelayed(()->reenable(),200);
                }
            };
            AdvertisingSetParameters params=RadioPower.parameters(RadioPower.requested(context),true);
            AdvertiseData data=new AdvertiseData.Builder().addManufacturerData(Protocol.COMPANY,(host.dataPsm()>0?ChatProtocol.linkHello(host.sender(),psm,host.dataPsm()):ChatProtocol.imageHello(host.sender(),psm))).build();
            advertiser.startAdvertisingSet(params,data,null,null,null,beacon);
            final BluetoothServerSocket listener=server;
            new Thread(()->{
                while(!stopped)try {
                    BluetoothSocket socket=listener.accept();
                    if(stopped){close(socket);break;}
                    if(incoming.size()>=3){close(socket);continue;}
                    sockets.add(socket);incoming.add(socket);
                    try {receivers.execute(()->receive(socket));}catch(RejectedExecutionException full){sockets.remove(socket);incoming.remove(socket);close(socket);}
                    main.post(()->reenable());
                }catch(IOException e){if(!stopped)MeshService.note("Image listener stopped: "+e.getMessage());break;}
            },"image-listener").start();
        }catch(Exception e){MeshService.note("Connected images unavailable: "+e.getMessage());stop();}
    }
    void reenable(){try{if(!stopped&&advertising!=null)advertising.enableAdvertising(true,0,0);}catch(Exception ignored){}}
    void observe(int sender,int psm,BluetoothDevice device) {
        if(stopped||sender==host.sender()||device==null||psm<1||psm>65535)return;
        peers.put(sender,new Peer(device,psm,SystemClock.elapsedRealtime()));
        while(peers.size()>64)peers.remove(peers.keySet().iterator().next());
    }
    boolean available(Collection<Integer> expected) {
        if(Build.VERSION.SDK_INT<29||stopped||psm==0)return false;
        for(int peer:peers.keySet())if((expected.isEmpty()||expected.contains(peer))&&hasPeer(peer))return true;
        return false;
    }
    boolean hasPeer(int id){Peer peer=peers.get(id);return !stopped&&peer!=null&&SystemClock.elapsedRealtime()-peer.seen<PEER_MS;}
    boolean send(int id,byte[] envelope,Collection<Integer> expected,Completion completion) {
        if(Build.VERSION.SDK_INT<29||stopped||psm==0)return false;
        Map<Integer,Peer> targets=new LinkedHashMap<>();long now=SystemClock.elapsedRealtime();
        for(Map.Entry<Integer,Peer> e:peers.entrySet())if(now-e.getValue().seen<PEER_MS&&(expected.isEmpty()||expected.contains(e.getKey())))targets.put(e.getKey(),e.getValue());
        if(targets.isEmpty())return false;
        final int epoch=cancellation;
        java.util.concurrent.atomic.AtomicInteger activeLinks=new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger finished=new java.util.concurrent.atomic.AtomicInteger();
        senders.send(targets.keySet(),peer->{
                if(stopped||cancellation!=epoch)return false;
                Peer link=targets.get(peer);activeLinks.incrementAndGet();
                final String label=String.format(Locale.US,"%08X",peer);
                status("Image connections · "+activeLinks.get()+" active · "+finished.get()+" / "+targets.size()+" finished",finished.get()/(float)targets.size(),true,epoch);
                BluetoothSocket socket=null;Runnable timeout=null;boolean connectedLink=false;String outcome="Connection failed";
                linkEvent(peer,0,0,1,"");
                long start=SystemClock.elapsedRealtime();
                try {
                    socket=link.device.createInsecureL2capChannel(link.psm);sockets.add(socket);outgoing.add(socket);
                    final BluetoothSocket active=socket;timeout=()->close(active);main.postDelayed(timeout,DEADLINE_MS);
                    int bondBefore=link.device.getBondState();socket.connect();connectedLink=true;linkEvent(peer,1,0,-1,"");long connected=SystemClock.elapsedRealtime();
                    android.util.Log.i("BTImage","Bond state before="+bondBefore+" after="+link.device.getBondState());
                    BulkImageProtocol.write(new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(),ChatProtocol.MAX_ENVELOPE+20)),host.sender(),peer,id,envelope);
                    if(!BulkImageProtocol.accepted(new DataInputStream(socket.getInputStream()),peer,id))throw new IOException("Receiver did not save image");
                    long end=SystemClock.elapsedRealtime();
                    android.util.Log.i("BTImage","DELIVERED peer="+label+" id="+id+" bytes="+envelope.length+" connect_ms="+(connected-start)+" transfer_ack_ms="+(end-connected)+" total_ms="+(end-start)+" tx_packet="+socket.getMaxTransmitPacketSize());
                    outcome="Image delivered · "+envelope.length+" bytes · "+(end-start)+" ms";
                    return true;
                }catch(Exception e){android.util.Log.i("BTImage","FALLBACK peer="+label+" id="+id+" reason="+e.getMessage());return false;}
                finally {
                    if(timeout!=null)main.removeCallbacks(timeout);
                    if(socket!=null){outgoing.remove(socket);sockets.remove(socket);close(socket);}
                    linkEvent(peer,connectedLink?-1:0,0,connectedLink?0:-1,outcome);
                    activeLinks.decrementAndGet();finished.incrementAndGet();
                    status("Image connections · "+activeLinks.get()+" active · "+finished.get()+" / "+targets.size()+" finished",finished.get()/(float)targets.size(),true,epoch);
                }
        },delivered->main.post(()->{
            if(!stopped&&cancellation==epoch){
                host.imageLinkStatus("Connected image delivery: "+delivered.size()+" / "+targets.size()+" devices",1,false);
                completion.done(delivered);
            }
        }));
        return true;
    }
    private void status(String status,float progress,boolean busy,int epoch){main.post(()->{if(!stopped&&epoch==cancellation)host.imageLinkStatus(status,progress,busy);});}
    private void receive(BluetoothSocket socket) {
        Runnable timeout=()->close(socket);main.postDelayed(timeout,DEADLINE_MS);
        Integer peer=null;String outcome="Incoming image failed";
        try {
            BulkImageProtocol.Frame frame=BulkImageProtocol.read(new DataInputStream(socket.getInputStream()),host.sender());
            peer=frame.sender;linkEvent(frame.sender,0,1,0,"");
            // Decode and CRC validation happen on this worker; DB commit is dispatched to main by Host.
            boolean saved=host.saveImage(frame.sender,frame.id,frame.envelope);
            BulkImageProtocol.acknowledge(new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(),64)),host.sender(),frame.id,saved);
            outcome=saved?"Image saved · "+frame.envelope.length+" bytes":"Image rejected";
            android.util.Log.i("BTImage","RECEIVED sender="+String.format(Locale.US,"%08X",frame.sender)+" id="+frame.id+" bytes="+frame.envelope.length+" saved="+saved);
        }catch(Exception e){if(!stopped)android.util.Log.i("BTImage","Incoming image failed: "+e.getMessage());}
        finally {if(peer!=null)linkEvent(peer,0,-1,0,outcome);main.removeCallbacks(timeout);sockets.remove(socket);incoming.remove(socket);close(socket);main.post(()->reenable());}
    }
    void cancel(){cancellation++;senders.cancel();for(BluetoothSocket socket:outgoing)close(socket);}
    void stop(){
        if(stopped)return;stopped=true;cancel();main.removeCallbacksAndMessages(null);
        close(server);for(BluetoothSocket socket:sockets)close(socket);sockets.clear();
        try{if(advertiser!=null&&beacon!=null)advertiser.stopAdvertisingSet(beacon);}catch(Exception ignored){}
        senders.close();receivers.shutdownNow();peers.clear();
    }
    private static void close(Closeable c){try{if(c!=null)c.close();}catch(IOException ignored){}}
}
