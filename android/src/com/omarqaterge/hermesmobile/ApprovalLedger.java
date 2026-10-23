package com.omarqaterge.hermesmobile;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Which approvals are already closed (answered or expired): one JSON object per line in the app's private files dir,
 * newest at the end. It survives service restarts, so a late or replayed event for a closed approval can't re-open it.
 * Capped; never leaves the phone. (approvals.jsonl is the History screen's log; this file is state.)
 */
final class ApprovalLedger {
    private ApprovalLedger() {}

    static final int MAX = 300;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "approval_ledger.jsonl");
    }

    /** state: answered | expired. An empty id is ignored. */
    static synchronized void close(Context c, String requestId, String state) {
        if (requestId == null || requestId.isEmpty()) return;
        try {
            JSONObject o = new JSONObject().put("t", System.currentTimeMillis()).put("request_id", requestId).put("state", state);
            File f = file(c);
            try (FileOutputStream os = new FileOutputStream(f, true)) {
                os.write((o.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (f.length() > 40_000) trim(c);
        } catch (Exception ignored) {
            // the ledger is a safety net: never let it break an approval
        }
    }

    /** The closed request ids, oldest first. */
    static synchronized LinkedHashSet<String> closedIds(Context c) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String line : lines(c)) {
            try {
                String id = new JSONObject(line).optString("request_id", "");
                if (!id.isEmpty()) out.add(id);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static List<String> lines(Context c) {
        List<String> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = r.readLine()) != null) if (!l.trim().isEmpty()) out.add(l);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void trim(Context c) {
        List<String> all = lines(c);
        if (all.size() <= MAX) return;
        List<String> keep = all.subList(all.size() - MAX, all.size());
        try (FileOutputStream os = new FileOutputStream(file(c), false)) {
            for (String l : keep) os.write((l + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
