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
        check(l.get(0).speed < 1f && l.get(0).pauseMs == 330, "sad slower: " + ProsodyPlanner.describe(l));
        check(l.get(1).speed < 1f && l.get(1).pauseMs == 430, "question: " + ProsodyPlanner.describe(l));
        check(l.get(2).speed > 1f, "glad: " + ProsodyPlanner.describe(l));
        check(l.get(2).pauseMs == 0, "no pause after the last");
        check(ProsodyPlanner.plan("Okay.").get(0).pauseMs == 0, "single");
        check(ProsodyPlanner.plan("  ").isEmpty() && ProsodyPlanner.plan(null).isEmpty(), "empty");
        List<ProsodyPlanner.Chunk> long1 = ProsodyPlanner.plan("This is a long sentence that goes on and on, with several clauses in it, so that a person would take a breath, and then carry on to the end of the thought.");
        check(long1.size() >= 3 && long1.get(0).pauseMs == 170, "clauses: " + ProsodyPlanner.describe(long1));
        check(ProsodyPlanner.plan("Nice 🎉 one").get(0).text.equals("Nice  one") || !ProsodyPlanner.plan("Nice 🎉 one").get(0).text.contains("🎉"), "emoji stripped");
        System.out.println("ProsodyPlannerTest: " + n + " cases OK");
    }
}
