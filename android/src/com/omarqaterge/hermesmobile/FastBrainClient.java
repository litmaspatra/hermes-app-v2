package com.omarqaterge.hermesmobile;

import android.content.Context;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Talks to the FastBrain router running in Termux (the same phone). FastBrain's own socket is a Unix socket inside
 * Termux's private folder, which other apps cannot open, so scripts/fastbrain-router-tcp.py in the FastBrain repo
 * exposes it on 127.0.0.1:8765, and only to callers that send this app's key (the same key the Hermes plugin uses;
 * the app writes it to ~/.hermes-mobile/key in Termux). Blocking: call from a background thread.
 */
final class FastBrainClient {
    private FastBrainClient() {}

    static final int PORT = 8765;

    static final class Reply {
        boolean reachable;     // false: router/bridge not running, wrong key, timeout
        JSONObject payload;    // the router's JSON, when reachable
        String error = "";
    }

    static boolean enabled(Context c) {
        return c.getSharedPreferences("hm", Context.MODE_PRIVATE).getBoolean("voice_fastbrain", true);
    }

    static String key(Context c) {
        return c.getSharedPreferences("hermes", Context.MODE_PRIVATE).getString("event_key", "");
    }

    /**
     * Brings the companion (services.py in the Hermes plugin) up, which keeps the bridge and the voice server alive from inside
     * Hermes' own process. See Companion. True if a start was requested.
     */
    static boolean startBridges(Context c) {
        return Companion.startSupervisor(c);
    }

    /** Background, periodic: if the companion or what it keeps (bridge, voice server) is down, bring it back (a localhost call, almost free). */
    static void watch(Context c) {
        final Context app = c.getApplicationContext();
        if (!enabled(app) && !NeuralTts.enabled(app)) return;
        new Thread(() -> {
            if (!Companion.up(app)) Companion.startSupervisor(app);
            else if (status(app).startsWith("The FastBrain bridge isn't running") || NeuralTts.status(app).startsWith("The voice server isn't running")) Companion.ensure(app, 0);
        }, "fb-watch").start();
    }

    /** Background: if the bridge is down, bring it up now, so it is up by the time the command has been spoken. */
    static void warm(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            if (enabled(app) && status(app).startsWith("The FastBrain bridge isn't running")) Companion.ensure(app, 15_000);
        }, "fb-warm").start();
    }

    /** What is wrong, in words (Settings → Voice → Test FastBrain). Blocking. */
    static String status(Context c) {
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/health").openConnection();
            h.setConnectTimeout(800);
            h.setReadTimeout(3000);
            h.setRequestProperty("X-Hermes-Mobile-Key", key(c));
            int code = h.getResponseCode();
            if (code == 200) return "FastBrain is connected.";
            if (code == 403) return "FastBrain's bridge refused the app's key. Open Hermes once (the app rewrites ~/.hermes-mobile/key), then Settings → Voice → Fix everything.";
            if (code == 503) return "The bridge is running but the FastBrain router isn't. Settings → Voice → Fix everything starts it.";
            return "FastBrain answered HTTP " + code + ".";
        } catch (java.net.ConnectException e) {
            return "The FastBrain bridge isn't running. It restarts by itself; if it stays down: Settings → Voice → Fix everything.";
        } catch (Exception e) {
            return "Couldn't reach FastBrain: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (h != null) h.disconnect();
        }
    }

    /**
     * Ask the router. `execute` false only routes (which skill, which arguments) and runs nothing; true also runs
     * the action. `readMs`: how long to wait for the answer (an action can take seconds; the connect is 500 ms).
     */
    static Reply post(Context c, String text, boolean execute, int readMs) {
        Reply r = new Reply();
        HttpURLConnection h = null;
        try {
            byte[] body = ("text=" + URLEncoder.encode(text, "UTF-8") + "&execute=" + (execute ? "1" : "0")).getBytes(StandardCharsets.UTF_8);
            h = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/route").openConnection();
            h.setConnectTimeout(500);
            h.setReadTimeout(readMs);
            h.setRequestMethod("POST");
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            h.setRequestProperty("X-Hermes-Mobile-Key", key(c));
            try (OutputStream o = h.getOutputStream()) {
                o.write(body);
            }
            int code = h.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? h.getInputStream() : h.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && sb.length() < 200_000) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            if (code < 200 || code >= 300) {
                r.error = "HTTP " + code;
                return r;
            }
            r.payload = new JSONObject(sb.toString());
            r.reachable = true;
        } catch (Exception e) {
            r.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            if (h != null) h.disconnect();
        }
        return r;
    }
}
