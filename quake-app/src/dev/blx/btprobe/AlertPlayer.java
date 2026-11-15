package dev.blx.btprobe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns short mesh codes ("EQW", "EQA:4.8:120", ...) into pre-generated voice
 * clips in res/raw (made by earthquake-voices/generate_clips.py) and plays them
 * offline. Received text is only matched against this fixed table: unknown
 * text plays nothing and is never executed.
 */
final class AlertPlayer {
    static final int CALM = 1, SERIOUS = 2, URGENT = 3;

    static final class Alert {
        final String code, title, text, extra; final int tone, priority;
        Alert(String code, int tone, int priority, String title, String text, String extra) {
            this.code = code; this.tone = tone; this.priority = priority;
            this.title = title; this.text = text; this.extra = extra;
        }
    }

    static final class Parsed {
        final Alert alert; final List<String> clips; final String detail;
        Parsed(Alert a, List<String> c, String d) { alert = a; clips = c; detail = d; }
    }

    /** Same scripts as earthquake-voices/clips/manifest.json. Order = "Send alert" menu order. */
    static final Map<String, Alert> ALERTS = new LinkedHashMap<>();
    private static void add(String code, int tone, int priority, String title, String text, String extra) {
        ALERTS.put(code, new Alert(code, tone, priority, title, text, extra));
    }
    static {
        add("EQW", URGENT, 3, "Shaking expected",
            "Earthquake! Drop, cover, hold on. Protect your head and neck.",
            "Strong shaking is coming. Get under a sturdy table. Wheelchair or walker users: lock, cover, hold on.");
        add("EQN", URGENT, 3, "Shaking now",
            "Earthquake! Drop, cover, hold on. Stay put until the shaking stops.",
            "Stay where you are until the shaking stops. Do not run outside. Wheelchair or walker users: lock, cover, hold on.");
        add("TSU", URGENT, 3, "Tsunami",
            "Tsunami danger. If you are near the coast, go to high ground or inland now. Do not wait for official instructions. Stay away from the shore until officials say it is safe.", "");
        add("EQA", SERIOUS, 2, "Possible light shaking",
            "Earthquake detected nearby. Light shaking is possible. If you feel shaking, drop, cover, and hold on.", "");
        add("AFT", SERIOUS, 2, "Aftershocks",
            "Aftershocks are possible. If shaking starts again, drop, cover, and hold on. Stay away from damaged buildings and windows.", "");
        add("SOS", URGENT, 2, "Help needed",
            "Someone nearby needs help. Only go if it is safe for you.", "");
        add("CLR", CALM, 1, "After the shaking",
            "If the shaking has stopped, check yourself and others for injuries. Expect aftershocks. If you smell gas, leave right away and do not use flames or light switches. Use stairs, not elevators.", "");
        add("CHK", CALM, 1, "Check-in",
            "Community safety check. If you are safe, tap \"I'm OK\" to tell people nearby.", "");
        // A cancel must be able to interrupt the alert it cancels.
        add("CXL", CALM, 3, "False alarm",
            "Cancel. The earlier earthquake alert was a false alarm. No action is needed.", "");
        add("RLY", CALM, 1, "Unverified source",
            "This alert came from nearby phones and could not be verified. Follow official alerts if you receive them.", "");
        add("TEST", CALM, 1, "Test",
            "Test. This is only a test of the earthquake mesh alert system. No action is needed. Test.", "");
    }

    /** Integer 0-999 -> clip names, e.g. 120 -> num_1, word_hundred, num_20. */
    static List<String> numberClips(int n) {
        if (n < 0 || n > 999) throw new IllegalArgumentException("out of range");
        List<String> out = new ArrayList<>();
        if (n >= 100) {
            out.add("num_" + (n / 100)); out.add("word_hundred"); n %= 100;
            if (n == 0) return out;
        }
        if (n < 20) out.add("num_" + n);
        else { out.add("num_" + (n - n % 10)); if (n % 10 != 0) out.add("num_" + (n % 10)); }
        return out;
    }

    /** Mirrors playlist() in generate_clips.py. Returns null for anything that isn't an alert code. */
    static Parsed parse(String message) {
        if (message == null) return null;
        String[] parts = message.trim().split(":", -1);
        Alert alert = ALERTS.get(parts[0].toUpperCase(Locale.US));
        if (alert == null) return null;
        List<String> clips = new ArrayList<>();
        clips.add("alert_" + alert.code.toLowerCase(Locale.US));
        String detail = "";
        if (alert.code.equals("EQA") && parts.length >= 2) {
            try {
                List<String> extra = new ArrayList<>();
                String[] mag = parts[1].split("\\.", -1);
                extra.add("word_magnitude"); extra.addAll(numberClips(Integer.parseInt(mag[0])));
                String magText = mag[0];
                if (mag.length > 1 && !mag[1].isEmpty()) {
                    int tenth = Character.digit(mag[1].charAt(0), 10);
                    if (tenth < 0) throw new NumberFormatException();
                    extra.add("word_point"); extra.addAll(numberClips(tenth)); magText += "." + tenth;
                }
                detail = "Magnitude " + magText;
                if (parts.length >= 3) {
                    int km = Integer.parseInt(parts[2]);
                    extra.add("word_about"); extra.addAll(numberClips(km)); extra.add("word_km_away");
                    detail += " · about " + km + " km away";
                }
                clips.addAll(extra);
            } catch (IllegalArgumentException e) { detail = ""; } // malformed details: base alert only
        }
        return new Parsed(alert, clips, detail);
    }

    private static final long REPEAT_WINDOW_MS = 60_000;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> recent = new HashMap<>();
    private final AudioAttributes attributes = new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private MediaPlayer player;
    private ToneGenerator toneGenerator;
    private int currentPriority, playId;
    private boolean busy; // true from the tone until the last clip ends

    AlertPlayer(Context context) { this.context = context.getApplicationContext(); }

    /**
     * Mesh relays re-send the same alert with new message IDs, so the same text is
     * only played once per minute. Returns false if the alert was skipped because
     * it repeats one played recently, or because something more urgent is playing.
     */
    boolean play(Parsed p) {
        long now = SystemClock.elapsedRealtime();
        String key = String.join(",", p.clips);
        Long last = recent.get(key);
        if (last != null && now - last < REPEAT_WINDOW_MS) return false;
        if (busy && p.alert.priority < currentPriority) return false;
        recent.put(key, now);
        if (recent.size() > 64) recent.clear();
        stopPlayback();
        currentPriority = p.alert.priority; busy = true;
        final int id = ++playId;
        long delay = 0;
        if (p.alert.tone == URGENT) {
            alarmTone(); vibrate();
            delay = 1100; // let the attention tone finish before speech
        } else if (p.alert.tone == SERIOUS) vibrate();
        final List<String> queue = new ArrayList<>(p.clips);
        handler.postDelayed(() -> { if (id == playId) next(queue, id); }, delay);
        return true;
    }

    private void next(List<String> queue, int id) {
        releasePlayer();
        if (id != playId) return;
        if (queue.isEmpty()) { busy = false; currentPriority = 0; return; }
        int res = context.getResources().getIdentifier(queue.remove(0), "raw", context.getPackageName());
        if (res == 0) { next(queue, id); return; }
        player = MediaPlayer.create(context, res, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (player == null) { next(queue, id); return; }
        player.setOnCompletionListener(mp -> next(queue, id));
        player.setOnErrorListener((mp, what, extra) -> { next(queue, id); return true; });
        player.start();
    }

    private void alarmTone() {
        try {
            if (toneGenerator == null) toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1000);
        } catch (RuntimeException ignored) { } // tone is best effort; speech still plays
    }

    private void vibrate() {
        Vibrator v = context.getSystemService(Vibrator.class);
        if (v == null || !v.hasVibrator()) return;
        v.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 200, 500, 200, 800}, -1), attributes);
    }

    /** True when the alarm volume is muted, so the UI can warn that alerts will be silent. */
    boolean alarmMuted() {
        AudioManager am = context.getSystemService(AudioManager.class);
        return am != null && am.getStreamVolume(AudioManager.STREAM_ALARM) == 0;
    }

    private void releasePlayer() {
        if (player != null) { try { player.release(); } catch (RuntimeException ignored) { } player = null; }
    }

    void stopPlayback() { playId++; handler.removeCallbacksAndMessages(null); releasePlayer(); busy = false; currentPriority = 0; }

    void release() {
        stopPlayback();
        if (toneGenerator != null) { toneGenerator.release(); toneGenerator = null; }
    }
}
