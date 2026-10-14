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
