package dev.blx.btprobe;
import android.bluetooth.*;
import android.os.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Persistent, bidirectional LE links. Main owns discovery; each socket has one reader and writer. */
@android.annotation.SuppressLint("MissingPermission")
final class DataLinks {
    interface Host {
        int sender(); boolean saveImage(int sender,int id,byte[] envelope)throws Exception;
        void dataAdvertisingResume(); void dataPacket(int peer,byte[] packet); void voicePacket(int peer,byte[] pcm);
    }
    private final Host host;private final Handler main=new Handler(Looper.getMainLooper());
    private final ConcurrentHashMap<Integer,Link> links=new ConcurrentHashMap<>();
    private final Set<BluetoothSocket> sockets=ConcurrentHashMap.newKeySet();
    private final Set<Integer> dialing=new HashSet<>();private final Map<Integer,Long> retry=new HashMap<>();
    private final ImageFanout fanout=new ImageFanout();
    private BluetoothServerSocket server;private volatile boolean stopped;private int psm;
    private final Semaphore slots=new Semaphore(3);
    DataLinks(Host host){this.host=host;}
    int psm(){return psm;}
    int active(){return links.size();}
    Set<Integer> connectedPeers(){return new LinkedHashSet<>(links.keySet());}
    void start(BluetoothAdapter adapter){
        if(Build.VERSION.SDK_INT<29)return;
        try{server=adapter.listenUsingInsecureL2capChannel();psm=server.getPsm();
            new Thread(()->{while(!stopped)try{BluetoothSocket s=server.accept();if(stopped||!slots.tryAcquire()){close(s);continue;}sockets.add(s);attach(s,null);main.post(()->{if(!stopped)host.dataAdvertisingResume();});}catch(IOException e){break;}},"data-listener").start();main.post(heartbeat);
        }catch(Exception e){MeshService.note("Persistent data unavailable: "+e.getMessage());}
    }
    void observe(int peer,int remotePsm,BluetoothDevice device){
        // One deterministic initiator prevents crossed duplicate connections.
        if(Build.VERSION.SDK_INT<29||stopped||psm==0||remotePsm<1||device==null||Integer.compareUnsigned(host.sender(),peer)>=0||links.containsKey(peer)||dialing.contains(peer)||SystemClock.elapsedRealtime()<retry.getOrDefault(peer,0L)||!slots.tryAcquire())return;
        dialing.add(peer);retry.put(peer,SystemClock.elapsedRealtime()+5000);
        while(retry.size()>64)retry.remove(retry.keySet().iterator().next());
        new Thread(()->{BluetoothSocket s=null;Runnable timeout=null;boolean handedOff=false;
            try{s=device.createInsecureL2capChannel(remotePsm);sockets.add(s);final BluetoothSocket active=s;timeout=()->close(active);main.postDelayed(timeout,8000);s.connect();main.removeCallbacks(timeout);attach(s,peer);handedOff=true;}
            catch(Exception e){android.util.Log.i("BTData","Connect failed: "+e.getMessage());}
            finally{if(timeout!=null)main.removeCallbacks(timeout);if(!handedOff){if(s!=null){sockets.remove(s);close(s);}slots.release();}main.post(()->dialing.remove(peer));}
        },"data-connect").start();
    }
    private void attach(BluetoothSocket socket,Integer expected){
        Runnable deadline=()->close(socket);main.postDelayed(deadline,5000);
        new Thread(()->{Link link=null;
            try{
                DataInputStream in=new DataInputStream(socket.getInputStream());
                DataOutputStream out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(),ChatProtocol.MAX_ENVELOPE+32));
                if(expected!=null)DataProtocol.write(out,new DataProtocol.Frame(DataProtocol.HELLO,host.sender(),expected,0,new byte[0]));
                DataProtocol.Frame hello=DataProtocol.read(in,host.sender());
                if(hello.kind!=DataProtocol.HELLO||(expected!=null&&hello.sender!=expected))throw new IOException("Bad link greeting");
                if(expected==null){if(Integer.compareUnsigned(hello.sender,host.sender())>=0)throw new IOException("Wrong initiator");DataProtocol.write(out,new DataProtocol.Frame(DataProtocol.HELLO,host.sender(),hello.sender,0,new byte[0]));}
                link=new Link(hello.sender,socket,out);
                if(stopped||links.putIfAbsent(link.peer,link)!=null)throw new IOException("Duplicate data link");
                main.removeCallbacks(deadline);link.startWriter();main.post(MeshService::changed);android.util.Log.i("BTData","CONNECTED "+Integer.toHexString(link.peer));
                while(!stopped&&!link.closed){
                    DataProtocol.Frame f=DataProtocol.read(in,host.sender());if(f.sender!=link.peer)throw new IOException("Changed peer identity");link.lastRx=SystemClock.elapsedRealtime();
                    switch(f.kind){
                        case DataProtocol.CHAT:
                            boolean saved=host.saveImage(f.sender,f.id,f.body);link.offer(DataProtocol.ACK,f.id,new byte[]{(byte)(saved?1:0)},true);break;
                        case DataProtocol.ACK:CompletableFuture<Boolean> receipt=link.receipts.remove(f.id);if(receipt!=null)receipt.complete(f.body[0]==1);break;
                        case DataProtocol.PACKET:final int from=f.sender;main.post(()->{if(!stopped)host.dataPacket(from,f.body);});break;
                        case DataProtocol.VOICE:host.voicePacket(f.sender,f.body);break;
                        case DataProtocol.PING:link.offer(DataProtocol.PONG,f.id,new byte[0],true);break;
                        case DataProtocol.PONG:break;
                        default:throw new IOException("Unexpected greeting");
                    }
                }
            }catch(Exception e){if(!stopped)android.util.Log.i("BTData","Link ended: "+e.getMessage());}
            finally{main.removeCallbacks(deadline);if(link!=null){links.remove(link.peer,link);link.shutdown();}sockets.remove(socket);close(socket);slots.release();main.post(MeshService::changed);}
        },"data-read").start();
    }
    private final class Link {
        final int peer;final BluetoothSocket socket;final DataOutputStream out;
        final LinkedBlockingDeque<DataProtocol.Frame> queue=new LinkedBlockingDeque<>(96);
        final ConcurrentHashMap<Integer,CompletableFuture<Boolean>> receipts=new ConcurrentHashMap<>();
        volatile long lastRx=SystemClock.elapsedRealtime();volatile boolean closed;Thread writer;
        Link(int p,BluetoothSocket s,DataOutputStream o){peer=p;socket=s;out=o;}
        boolean offer(int kind,int id,byte[] bytes,boolean priority){
            if(closed||stopped)return false;
            DataProtocol.Frame f=new DataProtocol.Frame(kind,host.sender(),peer,id,bytes.clone());
            // Drop old audio under congestion; never let it accumulate seconds of latency.
            if(kind==DataProtocol.VOICE&&queue.size()>12)return false;
            boolean accepted=priority?queue.offerFirst(f):queue.offerLast(f);
            if(!accepted&&priority)shutdown();return accepted;
        }
        void startWriter(){writer=new Thread(()->{try{while(!closed&&!stopped)DataProtocol.write(out,queue.take());}catch(Exception e){shutdown();}},"data-write");writer.start();}
        void shutdown(){closed=true;close(socket);if(writer!=null)writer.interrupt();for(CompletableFuture<Boolean> r:receipts.values())r.complete(false);receipts.clear();queue.clear();}
    }
    boolean sendChat(int id,byte[] envelope,Collection<Integer> expected,BulkImages.Completion done){
        Set<Integer> targets=connectedPeers();if(!expected.isEmpty())targets.retainAll(expected);if(targets.isEmpty()||stopped)return false;
        fanout.send(targets,peer->{Link link=links.get(peer);if(link==null)return false;CompletableFuture<Boolean> ack=new CompletableFuture<>();
            if(link.receipts.putIfAbsent(id,ack)!=null)return false;
            try{if(!link.offer(DataProtocol.CHAT,id,envelope,false))return false;return ack.get(5,TimeUnit.SECONDS);}
            catch(Exception e){link.shutdown();return false;}finally{link.receipts.remove(id,ack);}
        },delivered->main.post(()->{if(!stopped)done.done(delivered);}));return true;
    }
    void packet(byte[] raw){for(Link l:links.values())l.offer(DataProtocol.PACKET,0,raw,true);}
    boolean voice(byte[] pcm){boolean sent=false;for(Link l:links.values())sent|=l.offer(DataProtocol.VOICE,0,pcm,false);return sent;}
    void cancel(){fanout.cancel();for(Link l:links.values()){for(CompletableFuture<Boolean> r:l.receipts.values())r.complete(false);l.receipts.clear();l.queue.removeIf(f->f.kind==DataProtocol.CHAT);}}
    private final Runnable heartbeat=new Runnable(){public void run(){if(stopped)return;long now=SystemClock.elapsedRealtime();for(Link l:links.values()){if(now-l.lastRx>20000)l.shutdown();else l.offer(DataProtocol.PING,0,new byte[0],true);}main.postDelayed(this,5000);}};
    void stop(){stopped=true;main.removeCallbacksAndMessages(null);close(server);for(Link l:links.values())l.shutdown();for(BluetoothSocket s:sockets)close(s);links.clear();fanout.close();}
    private static void close(Closeable c){try{if(c!=null)c.close();}catch(IOException ignored){}}
}
