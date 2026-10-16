package com.omarqaterge.hermesmobile;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A natural voice from the local Piper TTS server (scripts/piper-tts-server.py in the FastBrain repo, runs next to it).
 * The reply is cut into small pieces (ProsodyPlanner: a pace and a pause each); the next piece is synthesised while the
 * current one plays, so speech starts after the first sentence. If the server isn't there the caller falls back to the
 * phone's own voice. Same key as the FastBrain bridge, on 127.0.0.1.
 */
final class NeuralTts {
    static final int PORT = 8766;
    static final String DEFAULT_VOICE = "en_US-lessac-medium";
    private static final java.util.regex.Pattern VOICE = java.util.regex.Pattern.compile("^[a-z]{2,3}_[A-Za-z]{2,4}-[A-Za-z0-9_]+-(?:x_low|low|medium|high)$");

    static boolean validVoice(String v) {
        return v != null && VOICE.matcher(v).matches();
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private AudioTrack track;

    NeuralTts(Context c) {
        this.app = c.getApplicationContext();
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences("hm", Context.MODE_PRIVATE).getBoolean("voice_neural", true);
    }

    static String voice(Context c) {
        String v = c.getSharedPreferences("hm", Context.MODE_PRIVATE).getString("tts_voice", DEFAULT_VOICE);
        return validVoice(v) ? v : DEFAULT_VOICE;
    }

    private static final byte[] END = new byte[0], FAIL = new byte[0];

    /**
     * Speaks `text`. `done` runs on the main thread when it has finished or was stopped; `unavailable` instead if the
     * server could not give even the first piece (then nothing was spoken: the caller uses the phone's voice).
     */
    void speak(String text, Runnable done, Runnable unavailable) {
        final List<ProsodyPlanner.Chunk> plan = ProsodyPlanner.plan(text);
        if (plan.isEmpty()) {
            main.post(done);
            return;
        }
        stopped = false;
        final String voice = voice(app);
        final BlockingQueue<Object[]> q = new ArrayBlockingQueue<>(2); // {pcm, rate, pauseMs}
        new Thread(() -> { // fetcher: stays one piece ahead of the player
            try {
                for (int i = 0; i < plan.size() && !stopped; i++) {
                    ProsodyPlanner.Chunk c = plan.get(i);
                    byte[] wav = fetch(c.text, c.speed, voice);
                    q.put(wav == null ? new Object[] {FAIL, 0, 0} : new Object[] {wav, 0, c.pauseMs});
                    if (wav == null) return;
                }
                q.put(new Object[] {END, 0, 0});
            } catch (InterruptedException ignored) {
            }
        }, "tts-fetch").start();
        new Thread(() -> { // player
            boolean spoke = false;
            try {
                while (!stopped) {
                    Object[] it = q.poll(40, TimeUnit.SECONDS);
                    if (it == null || it[0] == FAIL) {
                        if (!spoke) {
                            FastBrainClient.startBridges(app); // the voice server is down: start it for next time
                            finish(unavailable);
                            return;
                        }
                        break;
                    }
                    if (it[0] == END) break;
                    byte[] wav = (byte[]) it[0];
                    spoke |= play(wav, (Integer) it[2]);
                }
            } catch (Exception ignored) {
            }
            finish(done);
        }, "tts-play").start();
    }

    void stop() {
        stopped = true;
        AudioTrack t = track;
        if (t != null) {
            try {
                t.pause();
                t.flush();
            } catch (Exception ignored) {
            }
        }
    }

    private void finish(Runnable r) {
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.release();
            } catch (Exception ignored) {
            }
        }
        if (r != null) main.post(r);
    }

    /** One piece as WAV bytes, or null. */
    private byte[] fetch(String text, float speed, String voice) {
        HttpURLConnection h = null;
        try {
            byte[] body = ("text=" + URLEncoder.encode(text, "UTF-8") + "&speed=" + speed + "&voice=" + voice).getBytes(StandardCharsets.UTF_8);
            h = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/tts").openConnection();
            h.setConnectTimeout(600);
            h.setReadTimeout(20_000);
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            h.setRequestProperty("X-Hermes-Mobile-Key", FastBrainClient.key(app));
            try (OutputStream o = h.getOutputStream()) {
                o.write(body);
            }
            if (h.getResponseCode() != 200) return null;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && o.size() < 8_000_000) o.write(buf, 0, n);
            }
            return o.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (h != null) h.disconnect();
        }
    }

    /** Plays a 16-bit mono WAV, then `pauseMs` of silence. False if the data was not usable. */
    private boolean play(byte[] wav, int pauseMs) {
        if (wav.length < 48 || wav[0] != 'R' || wav[1] != 'I') return false;
        int rate = (wav[24] & 255) | (wav[25] & 255) << 8 | (wav[26] & 255) << 16 | (wav[27] & 255) << 24;
        int pos = 12, dataAt = -1, dataLen = 0;
        while (pos + 8 <= wav.length) {
            int len = (wav[pos + 4] & 255) | (wav[pos + 5] & 255) << 8 | (wav[pos + 6] & 255) << 16 | (wav[pos + 7] & 255) << 24;
            if (wav[pos] == 'd' && wav[pos + 1] == 'a' && wav[pos + 2] == 't' && wav[pos + 3] == 'a') {
                dataAt = pos + 8;
                dataLen = Math.max(0, Math.min(len, wav.length - dataAt));
                break;
            }
            pos += 8 + len + (len & 1);
        }
        if (dataAt < 0 || rate < 8000 || rate > 48000) return false;
        int silence = rate * 2 * pauseMs / 1000 & ~1;
        try {
            if (track == null) {
                int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(Math.max(min, rate)).setTransferMode(AudioTrack.MODE_STREAM).build();
                track.play();
            }
            track.write(wav, dataAt, dataLen);
            if (silence > 0) track.write(new byte[silence], 0, silence);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Voices installed on the server (Piper names like en_US-lessac-medium); empty if it isn't reachable. Blocking. */
    static String voices(Context c) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/voices").openConnection();
            h.setConnectTimeout(800);
            h.setReadTimeout(3000);
            h.setRequestProperty("X-Hermes-Mobile-Key", FastBrainClient.key(c));
            if (h.getResponseCode() != 200) return "";
            StringBuilder b = new StringBuilder();
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && b.length() < 8000) b.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            StringBuilder out = new StringBuilder();
            for (String line : b.toString().split("\\s+")) if (validVoice(line)) out.append(out.length() > 0 ? "," : "").append(line);
            return out.toString();
        } catch (Exception e) {
            return "";
        } finally {
            if (h != null) h.disconnect();
        }
    }

    /** Tells in words whether the server is up (Settings → Voice → Test voice). Blocking. */
    static String status(Context c) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/health").openConnection();
            h.setConnectTimeout(800);
            h.setReadTimeout(3000);
            h.setRequestProperty("X-Hermes-Mobile-Key", FastBrainClient.key(c));
            int code = h.getResponseCode();
            if (code == 200) return "The natural voice is ready.";
            if (code == 403) return "The voice server refused the app's key. Open Hermes once, then restart it.";
            if (code == 503) return "The voice server is running but has no voice installed. Run setup-piper.sh again.";
            return "The voice server answered HTTP " + code + ".";
        } catch (java.net.ConnectException e) {
            return "The voice server isn't running. In Termux / your Linux shell: bash ~/FastBrain/scripts/setup-piper.sh";
        } catch (Exception e) {
            return "Couldn't reach the voice server: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (h != null) h.disconnect();
        }
    }
}
