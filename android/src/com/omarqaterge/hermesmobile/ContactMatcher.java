package com.omarqaterge.hermesmobile;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the contact a person MEANT from what the speech recogniser HEARD. Pure Java (no Android imports) so it is
 * unit-tested in CI. The order is: your own alias, the whole name, a first name, then a sound-alike match (names
 * heard as "budagodira" for "butagorira"). The sound-alike match compares consonants only and treats confusable
 * pairs (t/d, p/b, k/g, r/d, m/n ...) as near-equal, the way Indian names are usually misheard.
 *
 * A wrong call is worse than a slow one, so a sound-alike match is CONFIDENT (call at once) only when it is clearly
 * better than the next contact; a weaker one is a SUGGESTION the caller should confirm with the person.
 */
final class ContactMatcher {
    private ContactMatcher() {}

    enum How { ALIAS, EXACT, FIRST_NAME, SOUNDS_LIKE, SUGGEST, AMBIGUOUS, NONE }

    static final class Result {
        final How how;
        final String name;        // the contact's display name (null for NONE / AMBIGUOUS)
        final double score;       // 0..1
        final List<String> others; // AMBIGUOUS: the contenders

        Result(How how, String name, double score, List<String> others) {
            this.how = how;
            this.name = name;
            this.score = score;
            this.others = others;
        }

        /** Safe to act on without asking. */
        boolean confident() {
            return how == How.ALIAS || how == How.EXACT || how == How.FIRST_NAME || how == How.SOUNDS_LIKE;
        }

        @Override
        public String toString() {
            return how + "(" + name + "," + String.format(Locale.ROOT, "%.2f", score) + ")";
        }
    }

    static final int MAX_ALIASES = 300;

    /** "alias = Contact name" per line; # starts a comment; later lines win. Keys are normalised like contact names. */
    static Map<String, String> parseAliases(String text) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        if (text == null) return m;
        for (String line : text.split("\\r?\\n")) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = ContactMatcher.key(line.substring(0, eq));
            String v = line.substring(eq + 1).trim();
            if (k.isEmpty() || v.isEmpty() || m.size() >= MAX_ALIASES && !m.containsKey(k)) continue;
            m.put(k, v);
        }
        return m;
    }

    static String formatAliases(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) b.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        return b.toString();
    }

    static final double CONFIDENT = 0.78, SUGGEST = 0.70, MARGIN = 0.12;

    /** A name reduced for comparison: no accents, lower case, single spaces. */
    static String key(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]+", " ").trim().replaceAll("\\s+", " ");
    }

    /**
     * The consonant skeleton of a name, Hindi/English transliteration spelling folded together:
     * "Bhagwati" and "bagavati" both give "bgvt"; vowels and h/y disappear (except they separate doubled letters).
     */
    static String skeleton(String s) {
        String t = key(s).replace(" ", "");
        if (t.isEmpty()) return "";
        t = t.replaceAll("(.)\\1+", "$1"); // Amman -> Aman
        t = t.replace("ph", "f").replace("bh", "b").replace("dh", "d").replace("gh", "g").replace("kh", "k")
                .replace("th", "t").replace("sh", "s").replace("ch", "c").replace("ck", "k").replace("x", "ks")
                .replace("q", "k").replace("w", "v").replace("z", "j");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == 'c') c = i + 1 < t.length() && "eiy".indexOf(t.charAt(i + 1)) >= 0 ? 's' : 'k';
            if (!Character.isLetter(c)) {
                b.append(c);
                continue;
            }
            if ("aeiouyh".indexOf(c) >= 0) continue; // vowels carry little: they are what the recogniser garbles most
            b.append(c);
        }
        return b.toString();
    }

    /** 0 = same sound, 1 = unrelated. */
    private static double sub(char a, char b) {
        if (a == b) return 0;
        String p = "" + (a < b ? a : b) + (a < b ? b : a);
        switch (p) {
            case "bp": case "bv": case "fp": case "fv": case "dt": case "gk": case "jz": case "js": case "ks": case "jk": case "kt":
                return 0.35;
            case "mn": case "dr": case "lr": case "dl": case "ns": case "jy": case "gj": case "ct":
                return 0.55;
            default:
                return 1;
        }
    }

    /** Weighted edit distance over two skeletons. */
    static double distance(String a, String b) {
        int n = a.length(), m = b.length();
        double[][] d = new double[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++)
            for (int j = 1; j <= m; j++)
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + sub(a.charAt(i - 1), b.charAt(j - 1)));
        return d[n][m];
    }

    /** 0..1: how alike two names sound. Skeletons under 3 letters only count when they are identical (2 letters) or the names are equal. */
    static double similarity(String spoken, String name) {
        String a = skeleton(spoken), b = skeleton(name);
        if (a.length() < 3 || b.length() < 3) return key(spoken).equals(key(name)) ? 1 : a.length() == 2 && a.equals(b) ? 0.9 : 0;
        return 1 - distance(a, b) / Math.max(a.length(), b.length());
    }

    /** The best score of the spoken text against a full name or any one word of it ("Rahul" for "Rahul Sharma"). */
    private static double best(String spoken, String name) {
        double s = similarity(spoken, name);
        for (String w : key(name).split(" ")) if (w.length() >= 3) s = Math.max(s, similarity(spoken, w) * 0.97);
        return s;
    }

    /**
     * @param spoken  what was heard
     * @param names   every contact's display name (duplicates welcome: one entry per contact)
     * @param aliases your own spoken-name -> contact-name table (keys are compared with key()); may be null
     */
    static Result match(String spoken, List<String> names, Map<String, String> aliases) {
        String want = key(spoken);
        if (want.isEmpty() || names == null || names.isEmpty()) return new Result(How.NONE, null, 0, null);

        // 1. your own alias ("papa" -> "Ramesh Sharma"), if that contact still exists
        if (aliases != null) {
            String target = aliases.get(want);
            if (target != null) for (String n : names) if (key(n).equals(key(target))) return new Result(How.ALIAS, n, 1, null);
        }
        // 2. the whole name
        List<String> exact = new ArrayList<>();
        for (String n : names) if (key(n).equals(want) && !exact.contains(n)) exact.add(n);
        if (exact.size() == 1) return new Result(How.EXACT, exact.get(0), 1, null);
        if (exact.size() > 1) return new Result(How.AMBIGUOUS, null, 1, exact);

        // 3. a first name (or any one word) that belongs to exactly one contact
        if (!want.contains(" ")) {
            List<String> w = new ArrayList<>();
            for (String n : names) {
                for (String part : key(n).split(" ")) if (part.equals(want)) {
                    if (!w.contains(n)) w.add(n);
                    break;
                }
            }
            if (w.size() == 1) return new Result(How.FIRST_NAME, w.get(0), 0.95, null);
            if (w.size() > 1) return new Result(How.AMBIGUOUS, null, 0.95, w);
        }
        // 4. sounds like
        String top = null, second = null;
        double s1 = 0, s2 = 0;
        for (String n : names) {
            double s = best(spoken, n);
            if (s > s1) {
                second = top;
                s2 = s1;
                top = n;
                s1 = s;
            } else if (s > s2 && !n.equals(top)) {
                second = n;
                s2 = s;
            }
        }
        if (top == null || s1 < SUGGEST) return new Result(How.NONE, null, s1, null);
        if (s1 >= CONFIDENT && s1 - s2 >= MARGIN) return new Result(How.SOUNDS_LIKE, top, s1, null);
        if (s1 >= CONFIDENT && s2 >= CONFIDENT) {
            List<String> l = new ArrayList<>();
            l.add(top);
            if (second != null) l.add(second);
            return new Result(How.AMBIGUOUS, null, s1, l);
        }
        return new Result(How.SUGGEST, top, s1, null);
    }

    /** Best result across several recogniser alternatives (it returns a few guesses): the most confident wins. */
    static Result matchAny(List<String> heard, List<String> names, Map<String, String> aliases) {
        Result best = new Result(How.NONE, null, 0, null);
        for (String h : heard) {
            Result r = match(h, names, aliases);
            if (rank(r) > rank(best)) best = r;
        }
        return best;
    }

    private static double rank(Result r) {
        switch (r.how) {
            case ALIAS: return 5;
            case EXACT: return 4.5;
            case FIRST_NAME: return 4;
            case SOUNDS_LIKE: return 3 + r.score;
            case AMBIGUOUS: return 2;
            case SUGGEST: return 1 + r.score;
            default: return 0;
        }
    }
}
