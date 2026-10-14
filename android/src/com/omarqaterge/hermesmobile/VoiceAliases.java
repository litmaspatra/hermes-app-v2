package com.omarqaterge.hermesmobile;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * Your own names for people: "papa = Ramesh Sharma", "boss = Dr Mehta". One per line in Settings → Voice commands,
 * kept in the same text form in SharedPreferences. Also the home of names the phone learned after you confirmed one
 * ("Did you mean Budagodira Ramesh?" "yes"). The text parsing lives in ContactMatcher (pure Java, tested without a phone).
 */
final class VoiceAliases {
    private VoiceAliases() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("hm", Context.MODE_PRIVATE);
    }

    static Map<String, String> load(Context c) {
        return ContactMatcher.parseAliases(sp(c).getString("voice_aliases", ""));
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
        if (m.size() >= ContactMatcher.MAX_ALIASES) m.remove(m.keySet().iterator().next());
        m.put(k, contactName);
        sp(c).edit().putString("voice_aliases", ContactMatcher.formatAliases(m)).apply();
    }
}
