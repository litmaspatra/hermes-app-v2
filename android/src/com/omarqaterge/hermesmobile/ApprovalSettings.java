package com.omarqaterge.hermesmobile;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The auto-approve switch and the editable read-only allowlist (stored as JSON in SharedPreferences "hm"). */
final class ApprovalSettings {
    private ApprovalSettings() {}

    static boolean enabled(Context c) {
        return c.getSharedPreferences("hm", Context.MODE_PRIVATE).getBoolean("auto_readonly", false);
    }

    static Map<String, ApprovalPolicy.Rule> load(Context c) {
        String json = c.getSharedPreferences("hm", Context.MODE_PRIVATE).getString("allowlist", null);
        if (json == null) return ApprovalPolicy.defaultRules();
        try {
            return parse(json);
        } catch (Exception e) {
            return ApprovalPolicy.defaultRules();
        }
    }

    static Map<String, ApprovalPolicy.Rule> parse(String json) throws Exception {
        Map<String, ApprovalPolicy.Rule> m = new LinkedHashMap<>();
        JSONArray a = new JSONArray(json);
        for (int i = 0; i < a.length() && i < 200; i++) {
            JSONObject o = a.getJSONObject(i);
            String p = o.optString("program", "").trim();
            if (p.isEmpty() || p.length() > 40 || !p.matches("[A-Za-z0-9._+-]+")) continue;
            m.put(p, new ApprovalPolicy.Rule(p, strs(o.optJSONArray("subs")), strs(o.optJSONArray("deny")), strs(o.optJSONArray("only")), o.optString("notes", "")));
        }
        return m;
    }

    private static List<String> strs(JSONArray a) {
        List<String> l = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length() && i < 100; i++) {
            String s = a.optString(i, "").trim();
            if (!s.isEmpty() && s.length() < 80) l.add(s);
        }
        return l;
    }

    static String toJson(Map<String, ApprovalPolicy.Rule> rules) {
        JSONArray a = new JSONArray();
        try {
            for (ApprovalPolicy.Rule r : rules.values()) {
                JSONObject o = new JSONObject();
                o.put("program", r.program);
                o.put("subs", new JSONArray(r.subs));
                o.put("deny", new JSONArray(r.deny));
                o.put("only", new JSONArray(r.only));
                o.put("notes", r.notes);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        return a.toString();
    }

    /** What the page shows: the current list, the defaults, and the programs that can write. */
    static String state(Context c) {
        try {
            return new JSONObject().put("enabled", enabled(c)).put("rules", new JSONArray(toJson(load(c))))
                    .put("defaults", new JSONArray(toJson(ApprovalPolicy.defaultRules()))).put("writers", new JSONArray(ApprovalPolicy.WRITERS)).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Saves a list from the editor; `reason` of the history entry says what changed. Returns "" or an error. */
    static String save(Context c, String json) {
        try {
            Map<String, ApprovalPolicy.Rule> m = parse(json);
            SharedPreferences.Editor ed = c.getSharedPreferences("hm", Context.MODE_PRIVATE).edit();
            ed.putString("allowlist", toJson(m)).apply();
            HistoryStore.append(c, "settings", "", "allowlist now has " + m.size() + " programs", "changed");
            return "";
        } catch (Exception e) {
            return "invalid list";
        }
    }

    /** The editor's "Test a command" box: {"allow":bool,"reason":str,"risk":str|null}. */
    static String test(Context c, String command, String rulesJson) {
        try {
            Map<String, ApprovalPolicy.Rule> m = rulesJson == null || rulesJson.isEmpty() ? load(c) : parse(rulesJson);
            ApprovalPolicy.Result r = ApprovalPolicy.evaluate(command, m);
            String risk = ApprovalPolicy.risk(command);
            return new JSONObject().put("allow", r.allow).put("reason", r.reason).put("risk", risk == null ? JSONObject.NULL : risk).toString();
        } catch (Exception e) {
            return "{\"allow\":false,\"reason\":\"invalid list\",\"risk\":null}";
        }
    }
}
