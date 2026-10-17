package com.omarqaterge.hermesmobile;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The phone-side companion: services.py in the Hermes plugin, which runs inside Hermes' own long-lived process and keeps
 * FastBrain's bridge, Piper and MiniLM alive (closing the app can't take them down any more), installs what is missing
 * (the "doctor"), fetches pets without the phone having to reach a pet CDN, and keeps contact aliases in FastBrain /
 * Hindsight memory. Control port 127.0.0.1:8767, same key as the other bridges. Everything here blocks: call it from a
 * background thread.
 */
final class Companion {
    private Companion() {}

    static final int PORT = 8767;

    static final class Resp {
        int code;
        byte[] body = new byte[0];
        String slug = "", name = "";

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    static Resp call(Context c, String method, String path, String form, int readMs, int maxBytes) {
        Resp r = new Resp();
        HttpURLConnection h = null;
        try {
            h = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + path).openConnection();
            h.setConnectTimeout(600);
            h.setReadTimeout(readMs);
            h.setRequestMethod(method);
            h.setRequestProperty("X-Hermes-Mobile-Key", FastBrainClient.key(c));
            if (form != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream o = h.getOutputStream()) {
                    o.write(form.getBytes(StandardCharsets.UTF_8));
                }
            }
            r.code = h.getResponseCode();
            InputStream in = r.code >= 200 && r.code < 300 ? h.getInputStream() : h.getErrorStream();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && o.size() < maxBytes) o.write(buf, 0, n);
            }
            r.body = o.toByteArray();
            r.slug = h.getHeaderField("X-Pet-Slug") == null ? "" : h.getHeaderField("X-Pet-Slug");
            String nm = h.getHeaderField("X-Pet-Name");
            r.name = nm == null ? "" : java.net.URLDecoder.decode(nm, "UTF-8");
        } catch (Exception e) {
            r.code = 0;
        } finally {
            if (h != null) h.disconnect();
        }
        return r;
    }

    static boolean up(Context c) {
        return call(c, "GET", "/health", null, 2500, 2000).code == 200;
    }

    private static long lastStart;

    /**
     * Brings the companion up if it isn't: starts Hermes' services in Termux (they load the plugin, which supervises everything), and,
     * if the plugin isn't there yet or Hermes is not running, runs the supervisor as the long-lived foreground process of its own
     * Debian session: a `proot-distro login` that EXITS kills what it started, which is why the bridges used to die with the app.
     * At most once a minute. True if a start was requested.
     */
    static synchronized boolean startSupervisor(Context c) {
        if (System.currentTimeMillis() - lastStart < 60_000) return false;
        if (c.checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) return false;
        lastStart = System.currentTimeMillis();
        try {
            Intent i = new Intent();
            i.setClassName("com.termux", "com.termux.app.RunCommandService");
            i.setAction("com.termux.RUN_COMMAND");
            i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh");
            String inDebian = "f=$(ls ~/.hermes/plugins/hermes-mobile/services.py ~/.hermes/plugins/*/services.py 2>/dev/null | head -1); "
                    + "if [ -n \"$f\" ]; then exec python3 \"$f\" serve; fi; "
                    + "for d in ~/FastBrain ~/*/FastBrain; do [ -f $d/scripts/start-app-bridges.sh ] && exec bash $d/scripts/start-app-bridges.sh; done";
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[] {"-c",
                    "[ -x ~/bin/hermes-services ] && (~/bin/hermes-services >/dev/null 2>&1 &); proot-distro login debian -- bash -lc '" + inDebian.replace("'", "'\\''") + "'"});
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            c.startForegroundService(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Makes sure the companion answers (starting it if needed, waiting up to `waitMs`), then has it start anything that is down. */
    static boolean ensure(Context c, int waitMs) {
        if (!up(c)) {
            startSupervisor(c);
            long end = System.currentTimeMillis() + waitMs;
            while (System.currentTimeMillis() < end && !up(c)) {
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    return false;
                }
            }
            if (!up(c)) return false;
        }
        call(c, "POST", "/ensure", "", 25_000, 4000);
        return true;
    }

    /** The doctor's report as JSON ("" when the companion can't be reached). */
    static String status(Context c) {
        Resp r = call(c, "GET", "/status", null, 20_000, 60_000);
        return r.code == 200 ? r.text() : "";
    }

    static boolean fix(Context c) {
        if (!up(c) && !ensure(c, 20_000)) return false;
        return call(c, "POST", "/fix", "", 5000, 2000).code == 200;
    }

    /** The last doctor report ("" = never asked, {"down":true} = the companion can't be reached). The page polls this: it never blocks. */
    static volatile String lastStatus = "";

    static void refreshAsync(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String s = status(app);
            if (s.isEmpty()) {
                startSupervisor(app); // not answering: ask for it, the next refresh will see it
                s = "{\"down\":true}";
            }
            lastStatus = s;
        }, "doctor-status").start();
    }

    /** Has the companion install and start everything that is missing (FastBrain, MiniLM, Piper, 4 voices), then refreshes the report. */
    static void fixAsync(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            if (!fix(app)) lastStatus = "{\"down\":true}";
            else lastStatus = status(app);
        }, "doctor-fix").start();
    }

    /** alias -> contact, as FastBrain / Hindsight know them. Empty if unreachable. */
    static Map<String, String> aliases(Context c) {
        Map<String, String> out = new LinkedHashMap<>();
        Resp r = call(c, "GET", "/aliases", null, 6000, 400_000);
        if (r.code != 200) return out;
        try {
            JSONObject o = new JSONObject(r.text()).optJSONObject("aliases");
            if (o == null) return out;
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext() && out.size() < ContactMatcher.MAX_ALIASES) {
                String k = it.next();
                String v = o.optString(k, "");
                if (!k.isEmpty() && !v.isEmpty()) out.put(k, v);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Tells memory (FastBrain's hot cache and Hindsight Lite) that this spoken name means that contact. Fire and forget. */
    static void learn(Context c, String alias, String canonical) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                call(app, "POST", "/alias", "alias=" + URLEncoder.encode(alias, "UTF-8") + "&canonical=" + URLEncoder.encode(canonical, "UTF-8"), 60_000, 2000);
            } catch (Exception ignored) {
            }
        }, "alias-learn").start();
    }
}
