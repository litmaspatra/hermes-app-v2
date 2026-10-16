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

    private static long lastStart;

    /**
     * Starts the FastBrain bridge and the voice server inside Termux's Debian (scripts/start-app-bridges.sh), at most once
     * a minute. Needs Termux's RUN_COMMAND permission, which the app already uses to share its key. True if it was started.
     */
    static synchronized boolean startBridges(Context c) {
        if (System.currentTimeMillis() - lastStart < 60_000) return false;
        if (c.checkSelfPermission("com.termux.permission.RUN_COMMAND") != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        lastStart = System.currentTimeMillis();
        try {
            android.content.Intent i = new android.content.Intent();
            i.setClassName("com.termux", "com.termux.app.RunCommandService");
            i.setAction("com.termux.RUN_COMMAND");
            i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh");
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[] {"-c",
                    "proot-distro login debian -- bash -lc 'for d in ~/FastBrain ~/*/FastBrain; do [ -f $d/scripts/start-app-bridges.sh ] && exec bash $d/scripts/start-app-bridges.sh; done'"});
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            c.startForegroundService(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Background, periodic: start the bridge / voice server again if either is down (and the person uses them). */
    static void watch(Context c) {
        final Context app = c.getApplicationContext();
        if (!enabled(app) && !NeuralTts.enabled(app)) return;
        new Thread(() -> {
            if (status(app).startsWith("The FastBrain bridge isn't running") || NeuralTts.status(app).startsWith("The voice server isn't running")) startBridges(app);
        }, "fb-watch").start();
    }

    /** Background: if the bridge is down, start it now, so it is up by the time the command has been spoken. */
    static void warm(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            if (enabled(app) && status(app).startsWith("The FastBrain bridge isn't running")) startBridges(app);
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
            if (code == 403) return "FastBrain's bridge refused the app's key. Open Hermes once (the app rewrites ~/.hermes-mobile/key), then restart the bridge.";
            if (code == 503) return "The bridge is running but the FastBrain router isn't. Run start-fastbrain-router.sh in Termux.";
            return "FastBrain answered HTTP " + code + ".";
        } catch (java.net.ConnectException e) {
            return "The FastBrain bridge isn't running. In Termux: bash ~/FastBrain/scripts/start-fastbrain-router.sh (use the updated FastBrain).";
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
