package com.omarqaterge.hermesmobile;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * What your spoken names mean: "papa = Ramesh Sharma", "boss = Dr Mehta". Nobody has to type them: they come from memory
 * (FastBrain's cache, fed by Hindsight Lite, where Hermes already holds them) and are learned the first time a name is
 * explained ("Did you mean ...?" "yes" / "Which one: A or B?" "the first"), then written back to that memory. The text box in
 * Settings → Voice commands still works and wins over memory. The text parsing lives in ContactMatcher (pure Java, tested).
 */
final class VoiceAliases {
    private VoiceAliases() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("hm", Context.MODE_PRIVATE);
    }

    /** Memory's aliases, then yours on top (an alias whose contact no longer exists is ignored by the matcher). */
    static Map<String, String> load(Context c) {
        Map<String, String> m = ContactMatcher.parseAliases(sp(c).getString("voice_aliases_mem", ""));
        m.putAll(ContactMatcher.parseAliases(sp(c).getString("voice_aliases", "")));
        return m;
    }

    private static long syncedAt;

    /** Pulls the aliases memory already has (at most every 5 minutes). Background; keeps the old copy if the companion isn't reachable. */
    static void syncFromMemory(Context c) {
        final Context app = c.getApplicationContext();
        synchronized (VoiceAliases.class) {
            if (System.currentTimeMillis() - syncedAt < 300_000) return;
            syncedAt = System.currentTimeMillis();
        }
        new Thread(() -> {
            Map<String, String> m = Companion.aliases(app);
            if (!m.isEmpty()) sp(app).edit().putString("voice_aliases_mem", ContactMatcher.formatAliases(m)).apply();
        }, "alias-sync").start();
    }

    /** What memory contributed, for the Settings page. */
    static String memoryText(Context c) {
        return sp(c).getString("voice_aliases_mem", "");
    }

    static String text(Context c) {
        return sp(c).getString("voice_aliases", "");
    }

    /** Saves the edited list as typed. Returns "" or what is wrong with it. */
    static String saveText(Context c, String text) {
        if (text == null) return "nothing to save";
        if (text.length() > 20_000) return "too long";
        sp(c).edit().putString("voice_aliases", ContactMatcher.formatAliases(ContactMatcher.parseAliases(text))).apply();
        return "";
    }

    /** Remembers that this spoken name means that contact (after the person confirmed it). */
    static void learn(Context c, String spoken, String contactName) {
        String k = ContactMatcher.key(spoken);
        if (k.isEmpty() || contactName == null || contactName.isEmpty() || k.equals(ContactMatcher.key(contactName))) return;
        Map<String, String> m = load(c);
        if (contactName.equals(m.get(k))) return;
        Map<String, String> mine = ContactMatcher.parseAliases(text(c));
        if (mine.size() >= ContactMatcher.MAX_ALIASES) mine.remove(mine.keySet().iterator().next());
        mine.put(k, contactName);
        sp(c).edit().putString("voice_aliases", ContactMatcher.formatAliases(mine)).apply();
        Companion.learn(c, spoken, contactName); // into FastBrain's cache and Hindsight Lite: Hermes knows it too, and it survives a reinstall
    }
}
