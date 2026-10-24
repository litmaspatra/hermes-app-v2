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

    // Fewer, longer pieces: every piece costs a round trip to the voice server and a pause, which is what made speech
    // sound like one sentence, a gap, the next sentence. The first piece stays short so speech starts quickly, then the
    // pieces grow so each is made while the one before it plays.
    static final int FIRST_MAX = 120, SECOND_MAX = 180, REST_MAX = 240;
    private static final int LONG_SENTENCE = 260, PART_MAX = 200;

    /** One sentence (or a part of a very long one), with the pace of its mood. */
    private static final class Piece {
        final String text;
        final float base;
        final boolean cont; // a part of a longer sentence: more of it follows

        Piece(String text, float base, boolean cont) {
            this.text = text;
            this.base = base;
            this.cont = cont;
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
            List<Piece> pieces = new ArrayList<>();
            for (String sentence : SENTENCES.split(pt)) {
                String s = sentence.trim();
                if (s.isEmpty()) continue;
                float base = SAD.matcher(s).matches() ? 0.92f : GLAD.matcher(s).matches() ? 1.06f : 1f;
                if (s.length() <= LONG_SENTENCE) {
                    pieces.add(new Piece(s, base, false));
                    continue;
                }
                StringBuilder cur = new StringBuilder(); // a very long sentence: split at commas, in pieces of up to PART_MAX
                for (String clause : CLAUSE.split(s)) {
                    if (cur.length() > 0 && cur.length() + 1 + clause.length() > PART_MAX) {
                        pieces.add(new Piece(cur.toString().trim(), base, true));
                        cur.setLength(0);
                    }
                    cur.append(cur.length() > 0 ? " " : "").append(clause);
                }
                if (cur.length() > 0) pieces.add(new Piece(cur.toString().trim(), base, false));
            }
            StringBuilder cur = null;
            Piece first = null, last = null;
            for (Piece p : pieces) {
                int cap = out.isEmpty() ? FIRST_MAX : out.size() == 1 ? SECOND_MAX : REST_MAX;
                boolean merge = cur != null && !last.cont && !p.cont && p.base == first.base
                        && last.text.charAt(last.text.length() - 1) != '?' && cur.length() + 1 + p.text.length() <= cap;
                if (merge) {
                    cur.append(' ').append(p.text);
                    last = p;
                    continue;
                }
                if (cur != null) out.add(chunk(cur.toString(), first.base, last, false));
                cur = new StringBuilder(p.text);
                first = p;
                last = p;
            }
            if (cur != null) out.add(chunk(cur.toString(), first.base, last, true));
        }
        if (!out.isEmpty()) {
            Chunk l = out.get(out.size() - 1);
            out.set(out.size() - 1, new Chunk(l.text, l.speed, 0));
        }
        return out;
    }

    /** A spoken piece: the pace of its mood, nudged by how its last sentence ends, and the pause after it. */
    private static Chunk chunk(String text, float base, Piece last, boolean endOfParagraph) {
        char end = text.charAt(text.length() - 1);
        float speed = base;
        int pause;
        if (last.cont) {
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
