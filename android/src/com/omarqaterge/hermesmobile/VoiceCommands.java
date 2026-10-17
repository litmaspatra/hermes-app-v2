package com.omarqaterge.hermesmobile;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixed set of spoken commands that may run from the island or the lock screen, in English plus simple
 * Hinglish. Pure Java (no Android imports) so it is unit-tested in CI. Anything not matched is UNKNOWN and does
 * nothing. Camera and reading private content are recognised only to be refused (BLOCKED).
 */
final class VoiceCommands {
    private VoiceCommands() {}

    enum Type { CALL, MESSAGE, MUSIC_PLAY, MUSIC_PAUSE, MUSIC_NEXT, MUSIC_PREV, MUSIC_QUERY, TIMER, ALARM, TORCH, VOLUME, TIME, NAVIGATE, BLOCKED, UNKNOWN }

    static final class Command {
        final Type type;
        final String name;   // contact name (CALL, MESSAGE), search text (MUSIC_QUERY), place (NAVIGATE), reason (BLOCKED)
        final String text;   // message body
        final int a;         // seconds (TIMER), hour (ALARM), 1/0 (TORCH on/off), +1/-1/percent (VOLUME)
        final int b;         // minute (ALARM), 0 = relative / 1 = absolute (VOLUME)
        final String number; // CALL, MESSAGE: the exact number to use when two contacts share a name ("" = decide from the name)

        Command(Type type, String name, String text, int a, int b) {
            this(type, name, text, a, b, "");
        }

        Command(Type type, String name, String text, int a, int b, String number) {
            this.type = type;
            this.name = name;
            this.text = text;
            this.a = a;
            this.b = b;
            this.number = number == null ? "" : number;
        }

        @Override
        public String toString() {
            return type + "(" + name + "|" + text + "|" + a + "|" + b + ")";
        }
    }

    private static Command c(Type t) {
        return new Command(t, "", "", 0, 0);
    }

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern MESSAGE = Pattern.compile("^send\\s+(?:a\\s+|an\\s+)?(?:message|sms|text|text message)\\s+to\\s+(.+?)\\s+(?:saying|that says|that|says)\\s+(.+)$", F);
    private static final Pattern MESSAGE_HI = Pattern.compile("^(.+?)\\s+ko\\s+(?:message|sms|msg)\\s+(?:bhejo|bhej\\s+do|send\\s+karo)\\s+(.+)$", F);
    private static final Pattern CALL = Pattern.compile("^(?:please\\s+)?(?:call|phone|dial|ring)\\s+(.+)$", F);
    private static final Pattern CALL_HI = Pattern.compile("^(.+?)\\s+ko\\s+(?:call|phone|fone)(?:\\s+(?:karo|kar\\s+do|lagao|laga\\s+do))?$", F);
    private static final Pattern NAV = Pattern.compile("^(?:navigate|directions|take\\s+me|drive|navigation)\\s+(?:to\\s+)?(.+)$", F);
    private static final Pattern TIMER = Pattern.compile("^(?:set\\s+(?:a\\s+)?)?timer\\s+(?:for\\s+)?(\\d+)\\s*(second|seconds|sec|minute|minutes|min|hour|hours)$", F);
    private static final Pattern TIMER2 = Pattern.compile("^set\\s+(?:a\\s+)?(\\d+)\\s*(second|seconds|sec|minute|minutes|min|hour|hours)\\s+timer$", F);
    private static final Pattern ALARM = Pattern.compile("^set\\s+(?:an\\s+)?alarm\\s+(?:for|at)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?$", F);
    private static final Pattern TORCH = Pattern.compile("^(?:turn\\s+|switch\\s+)?(on|off)\\s+(?:the\\s+)?(?:flashlight|torch)$|^(?:flashlight|torch)\\s+(on|off)$", F);
    private static final Pattern VOL_REL = Pattern.compile("^(?:volume\\s+(up|down)|(increase|decrease|raise|lower)\\s+(?:the\\s+)?volume)$", F);
    private static final Pattern VOL_SET = Pattern.compile("^(?:set\\s+)?volume\\s+(?:to\\s+)?(\\d{1,3})(?:\\s*(?:percent|%))?$", F);
    private static final Pattern PLAY_Q = Pattern.compile("^play\\s+(.+)$", F);
    private static final Pattern BLOCK = Pattern.compile(".*\\b(camera|selfie|photo|picture|video\\s+record|screenshot|whatsapp|notifications?|read\\s+(?:my\\s+)?(?:messages?|sms|mail|email)|my\\s+messages)\\b.*", F);

    static String clean(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        t = t.replaceAll("[\\s.,!?]+$", "");
        t = t.replaceAll("(?i)\\s+please$", "").trim();
        return t;
    }

    /** A contact name reduced for comparison: no accents, lower case, single spaces. */
    static String nameKey(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]+", " ").trim().replaceAll("\\s+", " ");
    }

    static Command parse(String utterance) {
        String u = clean(utterance);
        if (u.isEmpty()) return c(Type.UNKNOWN);
        Matcher m;
        // Messages only with the explicit phrase, and before anything else so the body can contain any word.
        if ((m = MESSAGE.matcher(u)).matches() || (m = MESSAGE_HI.matcher(u)).matches())
            return new Command(Type.MESSAGE, clean(m.group(1)), m.group(2).trim(), 0, 0);
        if (BLOCK.matcher(u).matches()) return new Command(Type.BLOCKED, "not available by voice", "", 0, 0);
        if ((m = CALL.matcher(u)).matches() || (m = CALL_HI.matcher(u)).matches()) return new Command(Type.CALL, clean(m.group(1)), "", 0, 0);
        if ((m = NAV.matcher(u)).matches()) return new Command(Type.NAVIGATE, clean(m.group(1)), "", 0, 0);
        if ((m = TIMER.matcher(u)).matches() || (m = TIMER2.matcher(u)).matches()) {
            int n = Integer.parseInt(m.group(1));
            String unit = m.group(2).toLowerCase(Locale.ROOT);
            int secs = unit.startsWith("h") ? n * 3600 : unit.startsWith("m") ? n * 60 : n;
            return new Command(Type.TIMER, "", "", secs, 0);
        }
        if ((m = ALARM.matcher(u)).matches()) {
            int h = Integer.parseInt(m.group(1)), min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            String ap = m.group(3) == null ? "" : m.group(3).toLowerCase(Locale.ROOT).replace(".", "");
            if (ap.equals("pm") && h < 12) h += 12;
            if (ap.equals("am") && h == 12) h = 0;
            if (h > 23 || min > 59) return c(Type.UNKNOWN);
            return new Command(Type.ALARM, "", "", h, min);
        }
        if ((m = TORCH.matcher(u)).matches()) {
            String v = m.group(1) != null ? m.group(1) : m.group(2);
            return new Command(Type.TORCH, "", "", v.equalsIgnoreCase("on") ? 1 : 0, 0);
        }
        if ((m = VOL_SET.matcher(u)).matches()) return new Command(Type.VOLUME, "", "", Math.min(100, Integer.parseInt(m.group(1))), 1);
        if ((m = VOL_REL.matcher(u)).matches()) {
            String v = (m.group(1) != null ? m.group(1) : m.group(2)).toLowerCase(Locale.ROOT);
            return new Command(Type.VOLUME, "", "", v.equals("up") || v.equals("increase") || v.equals("raise") ? 1 : -1, 0);
        }
        String l = u.toLowerCase(Locale.ROOT);
        if (l.equals("what time is it") || l.equals("what's the time") || l.equals("what is the time") || l.equals("time kya hai") || l.equals("samay kya hai") || l.equals("the time"))
            return c(Type.TIME);
        if (l.matches("^(pause|pause (the )?(music|song|gaana)|stop (the )?(music|song)|gaana band karo|music band karo)$")) return c(Type.MUSIC_PAUSE);
        if (l.matches("^(play|resume|play (the )?music|resume (the )?music|gaana chalao|music chalao|gaana chala do)$")) return c(Type.MUSIC_PLAY);
        if (l.matches("^(next|next (song|track|gaana)|skip|skip (song|track)|agla gaana)$")) return c(Type.MUSIC_NEXT);
        if (l.matches("^(previous|previous (song|track|gaana)|go back|pichla gaana)$")) return c(Type.MUSIC_PREV);
        if ((m = PLAY_Q.matcher(u)).matches()) {
            String q = clean(m.group(1)).replaceAll("(?i)^(?:the\\s+)?(?:song|music|track)\\s+", "");
            if (!q.isEmpty()) return new Command(Type.MUSIC_QUERY, q, "", 0, 0);
        }
        return c(Type.UNKNOWN);
    }

    // ── conversation helpers (the controller keeps listening only when asked to, or after a Hermes answer) ──

    private static final Pattern CONVO_START = Pattern.compile("^(?:hey\\s+hermes[,\\s]+)?(?:let'?s\\s+(?:talk|chat|have\\s+a\\s+(?:chat|conversation|talk))|talk\\s+to\\s+me|chat\\s+with\\s+me|conversation\\s+mode|start\\s+(?:a\\s+)?conversation|baat\\s+karte\\s+hain|baat\\s+karo|mujhse\\s+baat\\s+karo|chat\\s+karte\\s+hain)$", F);
    private static final Pattern CONVO_END = Pattern.compile("^(?:ok(?:ay)?[,\\s]+)?(?:stop|that'?s\\s+all|that\\s+is\\s+all|bye|goodbye|good\\s+bye|end\\s+(?:the\\s+)?conversation|stop\\s+(?:talking|listening)|thanks?(?:\\s+you)?|thank\\s+you|bas|bas\\s+itna|band\\s+karo|ruko|chup|alvida)$", F);
    private static final Pattern YES = Pattern.compile("^(?:yes|yeah|yep|yup|sure|ok|okay|correct|right|do\\s+it|go\\s+ahead|haan|haa|han|ha|ji|ji\\s+haan|theek\\s+hai|kar\\s+do|karo)$", F);
    private static final Pattern NO = Pattern.compile("^(?:no|nope|nah|cancel|don'?t|stop|nahi|nahin|na|mat\\s+karo|rehne\\s+do)$", F);

    static boolean isConversationStart(String utterance) {
        return CONVO_START.matcher(clean(utterance)).matches();
    }

    static boolean isConversationEnd(String utterance) {
        return CONVO_END.matcher(clean(utterance)).matches();
    }

    /** 1 = yes, -1 = no, 0 = something else. */
    static int yesNo(String utterance) {
        String u = clean(utterance);
        if (YES.matcher(u).matches()) return 1;
        if (NO.matcher(u).matches()) return -1;
        return 0;
    }

    /**
     * A Hermes reply made fit to be read aloud: no code blocks, links, markdown marks or bullets, and short (a spoken
     * answer is not a document; the full reply is in the chat).
     */
    static String speakable(String reply) {
        if (reply == null) return "";
        String t = reply.replaceAll("(?s)```.*?```", " (code is in the chat) ");
        t = t.replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ");              // images
        t = t.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1");              // links keep their text
        t = t.replaceAll("https?://\\S+", " link ");
        t = t.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");                    // headings
        t = t.replaceAll("(?m)^\\s*(?:[-*•]|\\d+[.)])\\s+", "");              // bullets and numbers
        t = t.replaceAll("[*_`~>|]+", "");
        t = t.replaceAll("\\s+", " ").trim();
        if (t.length() <= 600) return t;
        int cut = Math.max(t.lastIndexOf(". ", 600), Math.max(t.lastIndexOf("? ", 600), t.lastIndexOf("! ", 600)));
        return (cut > 200 ? t.substring(0, cut + 1) : t.substring(0, 600)) + " The rest is in the chat.";
    }
}
