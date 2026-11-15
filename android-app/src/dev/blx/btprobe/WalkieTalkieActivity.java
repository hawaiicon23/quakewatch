package dev.blx.btprobe;
import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import android.view.WindowManager;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Voice multiplexed over the foreground service's existing unpaired data links. */
public class WalkieTalkieActivity extends ProbeActivity {
    private AudioManager audio;
    private volatile AudioRecord recorder;private volatile AudioTrack track;
    private volatile boolean visible;
    private volatile int generation;volatile boolean talking;
    private final AtomicBoolean toneSending=new AtomicBoolean(),captureRunning=new AtomicBoolean();
    private int micBurst;String voiceStatus="Waiting for nearby data links";
    volatile int sent,received,played,micPeak,rxPeak;
    private int oldMode;private boolean oldSpeaker,routed;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor playback=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(12),new ThreadPoolExecutor.DiscardPolicy());
    private final MeshService.VoiceListener voice=(peer,pcm)->{if(visible&&!isFinishing())try{playback.execute(()->play(peer,pcm));}catch(RejectedExecutionException ignored){}};
    @Override public void onCreate(Bundle b){super.onCreate(b);audio=getSystemService(AudioManager.class);VoiceCompose.install(this);}
    @Override public void onStart(){super.onStart();prepare("links");attach();}
    private final Runnable attachLoop=new Runnable(){public void run(){attach();ui.postDelayed(this,1000);}};
    private void attach(){MeshService mesh=MeshService.get();if(mesh!=null)mesh.voiceListener=voice;voiceStatus=connected()?"Data links ready · hold Talk to speak":"Discovering compatible nearby data links…";}
    void prepare(String action){Permissions.startMesh(this,null);}
    private void status(String value){android.util.Log.i("BTWalkie",value);ui.post(()->voiceStatus=value);}
    boolean connected(){MeshService mesh=MeshService.get();return mesh!=null&&mesh.data!=null&&mesh.data.active()>0;}
    private void sendAudio(byte[] pcm)throws IOException {MeshService mesh=MeshService.get();if(mesh==null||!mesh.sendVoice(pcm))throw new IOException("No available data link");}
    private void play(int peer,byte[] pcm){
        received++;rxPeak=VoiceProtocol.peak(pcm);if(!visible||talking||pcm.length==0)return;
        try{AudioTrack output=track;if(output==null){int size=AudioTrack.getMinBufferSize(VoiceProtocol.SAMPLE_RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
            output=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(VoiceProtocol.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(size,VoiceProtocol.FRAME_BYTES*8)).build();track=output;output.play();}
            output.write(pcm,0,pcm.length,AudioTrack.WRITE_BLOCKING);played++;
        }catch(Exception e){status("Playback failed: "+e.getMessage());}
    }
    void startTalk() {
        final int epoch=generation;
        if(!connected() || talking || toneSending.get()) return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},40);status("Allow microphone permission, then hold Talk."); return; }
        if(!captureRunning.compareAndSet(false,true)) return;
        final int burst=++micBurst;
        talking=true; if(track!=null) track.setVolume(0f);
        new Thread(()-> {
            AudioRecord input=null;
            try {
                int min=AudioRecord.getMinBufferSize(VoiceProtocol.SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                input=new AudioRecord(MediaRecorder.AudioSource.MIC,VoiceProtocol.SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(min,VoiceProtocol.FRAME_BYTES*8));
                if(input.getState()!=AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone initialization failed");
                recorder=input; if(!talking || epoch!=generation) return; input.startRecording();
                byte[] data=new byte[VoiceProtocol.FRAME_BYTES]; int filled=0;
                while(talking && epoch==generation && connected()) {
                    int count=input.read(data,filled,data.length-filled,AudioRecord.READ_BLOCKING);
                    if(count<=0) { if(talking) throw new IOException("Microphone read failed: "+count); break; }
                    filled+=count;
                    if(filled==data.length) { micPeak=VoiceProtocol.peak(data); sendAudio(data); sent++; filled=0; }
                }
                if(epoch==generation && connected()) sendAudio(new byte[0]);
                android.util.Log.i("BTWalkie","Microphone burst sent: TX="+sent+" peak="+micPeak);
            } catch(Exception e) { if(epoch==generation) status("Microphone/transmit error: "+e.getMessage()); }
            finally {
                if(input!=null) { try { input.stop(); } catch(Exception ignored) {} input.release(); if(recorder==input) recorder=null; }
                captureRunning.set(false);
                if(epoch==generation) ui.post(()-> { if(micBurst==burst) stopTalk(); });
            }
        },"walkie-microphone").start();
    }
    void stopTalk() {
        talking=false;
        AudioRecord input=recorder; if(input!=null) try { input.stop(); } catch(Exception ignored) {}
        AudioTrack output=track; if(output!=null) try { output.setVolume(1f); } catch(Exception ignored) {}
    }
    void sendTone() {
        final int epoch=generation;
        if(!connected()) { status("Wait for a nearby data link first."); return; }
        if(talking || !toneSending.compareAndSet(false,true)) return;
        new Thread(()-> {
            try {
                for(int i=0;i<25 && epoch==generation && connected();i++) { sendAudio(VoiceProtocol.tone(i)); sent++; Thread.sleep(20); }
                if(epoch==generation && connected()) sendAudio(new byte[0]);
                android.util.Log.i("BTWalkie","Tone test sent: TX="+sent);
            } catch(Exception e) { if(epoch==generation) status("Tone test failed: "+e.getMessage()); }
            finally { toneSending.set(false); }
        },"walkie-tone").start();
    }
    private void routeAudio() {
        if(routed) return; oldMode=audio.getMode(); oldSpeaker=audio.isSpeakerphoneOn(); routed=true;
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if(Build.VERSION.SDK_INT>=31) {
            for(AudioDeviceInfo device:audio.getAvailableCommunicationDevices()) if(device.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { audio.setCommunicationDevice(device); break; }
        } else audio.setSpeakerphoneOn(true);
    }

    void disconnect(){stopTalk();status("Voice paused. Shared chat and sensing links remain connected.");MeshService mesh=MeshService.get();if(mesh!=null&&mesh.voiceListener==voice)mesh.voiceListener=null;}
    @Override protected void onResume(){super.onResume();visible=true;routeAudio();if(track!=null)try{track.play();}catch(Exception ignored){}ui.post(attachLoop);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    @Override protected void onPause(){visible=false;stopTalk();playback.getQueue().clear();if(track!=null)try{track.pause();track.flush();}catch(Exception ignored){}unrouteAudio();ui.removeCallbacks(attachLoop);MeshService mesh=MeshService.get();if(mesh!=null&&mesh.voiceListener==voice)mesh.voiceListener=null;super.onPause();}
    @Override protected void onDestroy(){generation++;disconnect();ui.removeCallbacksAndMessages(null);playback.shutdownNow();
        AudioTrack output=track;track=null;if(output!=null){try{output.stop();}catch(Exception ignored){}output.release();}
        unrouteAudio();super.onDestroy();}
    private void unrouteAudio(){if(routed){try{if(Build.VERSION.SDK_INT>=31)audio.clearCommunicationDevice();else audio.setSpeakerphoneOn(oldSpeaker);audio.setMode(oldMode);}catch(Exception ignored){}routed=false;}}
}
