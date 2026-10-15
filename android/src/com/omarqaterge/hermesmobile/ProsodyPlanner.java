package com.omarqaterge.hermesmobile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns a reply into small spoken pieces with a pace and a pause each, so a neural voice sounds like a person talking
 * instead of reading a block: a breath after a comma, longer after a sentence, a slower softer pace for bad news,
 * a lighter quicker one for good news, a rising slower question. Pure Java, tested without a phone.
 */
final class ProsodyPlanner {
    private ProsodyPlanner() {}

    static final class Chunk {
        final String text;
        final float speed;   // 1.0 = normal
        final int pauseMs;   // silence after this piece

        Chunk(String text, float speed, int pauseMs) {
            this.text = text;
            this.speed = speed;
            this.pauseMs = pauseMs;
        }
    }

    private static final Pattern SENTENCES = Pattern.compile("(?<=[.!?…])\\s+|\\n+");
    private static final Pattern CLAUSE = Pattern.compile("(?<=[,;:])\\s+");
    private static final Pattern SAD = Pattern.compile("^(?:sorry|unfortunately|i can'?t|i couldn'?t|i didn'?t|that didn'?t|failed|no luck|oops|i'm afraid)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern GLAD = Pattern.compile("^(?:great|awesome|done|nice|yay|perfect|welcome|good news|all set|got it)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}]");

    static List<Chunk> plan(String text) {
        List<Chunk> out = new ArrayList<>();
        if (text == null) return out;
        String t = EMOJI.matcher(text).replaceAll("").trim();
        if (t.isEmpty()) return out;
        for (String sentence : SENTENCES.split(t)) {
            String s = sentence.trim();
            if (s.isEmpty()) continue;
            float base = SAD.matcher(s).matches() ? 0.92f : GLAD.matcher(s).matches() ? 1.06f : 1f;
            String[] parts = s.length() > 150 ? CLAUSE.split(s) : new String[] {s};
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.isEmpty()) continue;
                boolean last = i == parts.length - 1;
                char end = p.charAt(p.length() - 1);
                float speed = base;
                int pause;
                if (!last) {
                    pause = 170; // a breath between clauses of one long sentence
                } else if (end == '?') {
                    speed *= 0.96f;
                    pause = 430;
                } else if (end == '!') {
                    speed *= 1.05f;
                    pause = 360;
                } else if (end == '…' || p.endsWith("...")) {
                    speed *= 0.93f;
                    pause = 540;
                } else if (end == ',' || end == ';') {
                    pause = 170;
                } else if (end == ':') {
                    pause = 240;
                } else {
                    pause = p.split("\\s+").length <= 3 ? 260 : 330;
                }
                out.add(new Chunk(p, Math.max(0.8f, Math.min(1.2f, speed)), pause));
            }
        }
        if (!out.isEmpty()) {
            Chunk l = out.get(out.size() - 1);
            out.set(out.size() - 1, new Chunk(l.text, l.speed, 0));
        }
        return out;
    }

    static String describe(List<Chunk> l) {
        StringBuilder b = new StringBuilder();
        for (Chunk c : l) b.append(String.format(Locale.ROOT, "[%s|%.2f|%d]", c.text, c.speed, c.pauseMs));
        return b.toString();
    }
}
