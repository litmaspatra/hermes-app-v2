package com.omarqaterge.hermesmobile;

import java.util.List;

/** Plain-Java test (no JUnit): run by tools/java-unit-tests.sh. */
public class ProsodyPlannerTest {
    static int n = 0;

    static void check(boolean ok, String what) {
        n++;
        if (!ok) {
            System.err.println("FAIL: " + what);
            System.exit(1);
        }
    }

    public static void main(String[] a) {
        List<ProsodyPlanner.Chunk> l = ProsodyPlanner.plan("Sorry, I can't find that. Is it saved under another name? Great, calling now!");
        check(l.size() == 3, "chunks " + ProsodyPlanner.describe(l));
        check(l.get(0).speed < 1f && l.get(0).pauseMs == 180, "sad slower: " + ProsodyPlanner.describe(l));
        check(l.get(1).speed < 1f && l.get(1).pauseMs == 260, "question: " + ProsodyPlanner.describe(l));
        check(l.get(2).speed > 1f, "glad: " + ProsodyPlanner.describe(l));
        check(l.get(2).pauseMs == 0, "no pause after the last");
        check(ProsodyPlanner.plan("Okay.").get(0).pauseMs == 0, "single");
        check(ProsodyPlanner.plan("  ").isEmpty() && ProsodyPlanner.plan(null).isEmpty(), "empty");
        List<ProsodyPlanner.Chunk> long1 = ProsodyPlanner.plan("This is a long sentence that goes on and on, with several clauses in it, so that a person would take a breath, and then carry on to the end of the thought.");
        check(long1.size() == 1, "a 155-character sentence is one piece now: " + ProsodyPlanner.describe(long1));
        check(ProsodyPlanner.plan("Nice 🎉 one").get(0).text.equals("Nice  one") || !ProsodyPlanner.plan("Nice 🎉 one").get(0).text.contains("🎉"), "emoji stripped");
        // short sentences of one mood are spoken together, not one at a time with a gap between them
        List<ProsodyPlanner.Chunk> merged = ProsodyPlanner.plan("I looked at the calendar. You are free on Tuesday. Wednesday is busy.");
        check(merged.size() == 1 && merged.get(0).pauseMs == 0, "merged: " + ProsodyPlanner.describe(merged));
        // a new line is always a break; the reply never ends up in more pieces than it has sentences
        List<ProsodyPlanner.Chunk> lines = ProsodyPlanner.plan("The first point is this one.\nThe second point is that one.");
        check(lines.size() == 2 && lines.get(0).pauseMs == 300, "lines: " + ProsodyPlanner.describe(lines));
        // a long reply: the first piece is short, no piece is longer than the cap, nothing is lost
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 12; i++) big.append("This is sentence number ").append(i).append(" of a fairly long reply that keeps going. ");
        List<ProsodyPlanner.Chunk> bl = ProsodyPlanner.plan(big.toString());
        boolean sizes = bl.get(0).text.length() <= ProsodyPlanner.FIRST_MAX;
        int total = 0;
        for (ProsodyPlanner.Chunk c : bl) {
            sizes &= c.text.length() <= ProsodyPlanner.REST_MAX;
            total += c.text.replace(" ", "").length();
        }
        check(sizes && bl.size() < 12 && bl.size() >= 4, "big: " + ProsodyPlanner.describe(bl));
        check(total == big.toString().trim().replace(" ", "").length(), "big: no text lost");
        // a mood change is its own piece
        List<ProsodyPlanner.Chunk> mood = ProsodyPlanner.plan("Sorry, that did not work. Great, it is done now.");
        check(mood.size() == 2 && mood.get(0).speed < 1f && mood.get(1).speed > 1f, "mood: " + ProsodyPlanner.describe(mood));
        // one very long sentence is split at commas, not left whole
        StringBuilder one = new StringBuilder("Well");
        for (int i = 0; i < 40; i++) one.append(", then step ").append(i).append(" happens");
        one.append(".");
        List<ProsodyPlanner.Chunk> ol = ProsodyPlanner.plan(one.toString());
        check(ol.size() >= 2 && ol.get(0).pauseMs == 90, "long sentence: " + ProsodyPlanner.describe(ol));
        System.out.println("ProsodyPlannerTest: " + n + " cases OK");
    }
}
