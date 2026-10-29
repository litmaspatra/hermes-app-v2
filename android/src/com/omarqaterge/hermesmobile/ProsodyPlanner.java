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

    private static final Pattern SENTENCES = Pattern.compile("(?<=[.!?…])\\s+");
    private static final Pattern NEWLINES = Pattern.compile("\\n+");
    private static final Pattern CLAUSE = Pattern.compile("(?<=[,;:])\\s+");
    private static final Pattern SAD = Pattern.compile("^(?:sorry|unfortunately|i can'?t|i couldn'?t|i didn'?t|that didn'?t|failed|no luck|oops|i'm afraid)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern GLAD = Pattern.compile("^(?:great|awesome|done|nice|yay|perfect|welcome|good news|all set|got it)\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F000}-\\x{1FFFF}\\x{2600}-\\x{27BF}\\x{FE0F}]");

    // Fewer, longer pieces: every piece costs a round trip to the voice server and a pause, which made speech sound
    // like one sentence, a gap, the next sentence. But the phone makes speech only ~1.17x faster than it plays it, so
    // each piece may be at most ~12% longer than the one before it (the next piece is made while this one plays):
    // a short first piece so speech starts quickly, then slowly longer ones. A sentence is split at its commas only
    // when it is clearly longer than the piece it would have to fit in.
    static final int CAP_FIRST = 60, CAP_MAX = 200;
    static final float GROWTH = 1.12f, SOFT = 1.3f;

    /** Longest piece (in characters) the k-th piece of a reply should be. */
    static int cap(int k) {
        return Math.min(CAP_MAX, Math.round(CAP_FIRST * (float) Math.pow(GROWTH, k)));
    }

    private static final class Sentence {
        final String text;
        final float base;

        Sentence(String text, float base) {
            this.text = text;
            this.base = base;
        }
    }

    static List<Chunk> plan(String text) {
        List<Chunk> out = new ArrayList<>();
        if (text == null) return out;
        String t = EMOJI.matcher(text).replaceAll("").trim();
        if (t.isEmpty()) return out;
        for (String para : NEWLINES.split(t)) { // a new line is always a spoken break (lists, short lines)
            String pt = para.trim();
            if (pt.isEmpty()) continue;
            StringBuilder cur = null;
            float curBase = 1f;
            for (String sentence : SENTENCES.split(pt)) {
                String s = sentence.trim();
                if (s.isEmpty()) continue;
                float base = SAD.matcher(s).matches() ? 0.92f : GLAD.matcher(s).matches() ? 1.06f : 1f;
                if (cur != null && base == curBase && cur.charAt(cur.length() - 1) != '?'
                        && cur.length() + 1 + s.length() <= cap(out.size())) {
                    cur.append(' ').append(s);
                    continue;
                }
                if (cur != null) out.add(chunk(cur.toString(), curBase, false, false));
                if (s.length() > cap(out.size()) * SOFT) { // clearly too long for its slot: split at commas
                    List<String> parts = splitAtCommas(s, out.size());
                    for (int i = 0; i < parts.size() - 1; i++) out.add(chunk(parts.get(i), base, true, false));
                    cur = new StringBuilder(parts.get(parts.size() - 1));
                } else {
                    cur = new StringBuilder(s);
                }
                curBase = base;
            }
            if (cur != null) out.add(chunk(cur.toString(), curBase, false, true));
        }
        if (!out.isEmpty()) {
            Chunk l = out.get(out.size() - 1);
            out.set(out.size() - 1, new Chunk(l.text, l.speed, 0));
        }
        return out;
    }

    /** Pack a sentence's comma clauses into parts that fit the growing piece sizes, starting at piece number k. */
    private static List<String> splitAtCommas(String s, int k) {
        List<String> parts = new ArrayList<>();
        StringBuilder acc = new StringBuilder();
        for (String clause : CLAUSE.split(s)) {
            if (acc.length() > 0 && acc.length() + 1 + clause.length() > cap(k + parts.size())) {
                parts.add(acc.toString().trim());
                acc.setLength(0);
            }
            acc.append(acc.length() > 0 ? " " : "").append(clause);
        }
        if (acc.length() > 0) parts.add(acc.toString().trim());
        return parts;
    }

    /** A spoken piece: the pace of its mood, nudged by how its last sentence ends, and the pause after it. */
    private static Chunk chunk(String text, float base, boolean cont, boolean endOfParagraph) {
        char end = text.charAt(text.length() - 1);
        float speed = base;
        int pause;
        if (cont) {
            pause = 90; // a breath inside a long sentence
        } else if (end == '?') {
            speed *= 0.96f;
            pause = 260;
        } else if (end == '!') {
            speed *= 1.05f;
            pause = 220;
        } else if (end == '…' || text.endsWith("...")) {
            speed *= 0.93f;
            pause = 380;
        } else if (end == ',' || end == ';') {
            pause = 90;
        } else if (end == ':') {
            pause = 150;
        } else {
            pause = text.split("\\s+").length <= 3 ? 150 : 180;
        }
        if (endOfParagraph) pause += 120;
        return new Chunk(text, Math.max(0.8f, Math.min(1.2f, speed)), pause);
    }

    static String describe(List<Chunk> l) {
        StringBuilder b = new StringBuilder();
        for (Chunk c : l) b.append(String.format(Locale.ROOT, "[%s|%.2f|%d]", c.text, c.speed, c.pauseMs));
        return b.toString();
    }
}
