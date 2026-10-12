package com.omarqaterge.hermesmobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * What was approved, denied, timed out or run by voice: one JSON object per line in the app's private files
 * dir, newest at the end, capped at 500 entries. It never leaves the phone.
 */
final class HistoryStore {
    private HistoryStore() {}

    static final int MAX = 500;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "approvals.jsonl");
    }

    /** source: user | auto | timeout | voice | settings | app. */
    static synchronized void append(Context c, String source, String session, String text, String decision) {
        try {
            JSONObject o = new JSONObject().put("t", System.currentTimeMillis()).put("source", source).put("session", session == null ? "" : session)
                    .put("text", text == null ? "" : text.length() > 300 ? text.substring(0, 300) : text).put("decision", decision == null ? "" : decision);
            File f = file(c);
            try (FileOutputStream os = new FileOutputStream(f, true)) {
                os.write((o.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (f.length() > 200_000) trim(c); // about 500 lines of 300 chars at most
        } catch (Exception ignored) {
            // history is a convenience: never let it break an approval
        }
    }

    private static List<String> lines(Context c) {
        List<String> l = new ArrayList<>();
        File f = file(c);
        if (!f.isFile()) return l;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s;
            while ((s = r.readLine()) != null) if (!s.isEmpty()) l.add(s);
        } catch (Exception ignored) {
        }
        return l;
    }

    private static void trim(Context c) {
        List<String> l = lines(c);
        if (l.size() <= MAX) return;
        l = l.subList(l.size() - MAX, l.size());
        try (FileOutputStream os = new FileOutputStream(file(c), false)) {
            for (String s : l) os.write((s + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** Newest first, as a JSON array string. */
    static synchronized String read(Context c) {
        List<String> l = lines(c);
        if (l.size() > MAX) l = l.subList(l.size() - MAX, l.size());
        JSONArray a = new JSONArray();
        for (int i = l.size() - 1; i >= 0; i--) {
            try {
                a.put(new JSONObject(l.get(i)));
            } catch (Exception ignored) {
            }
        }
        return a.toString();
    }

    static synchronized void clear(Context c) {
        file(c).delete();
    }
}
