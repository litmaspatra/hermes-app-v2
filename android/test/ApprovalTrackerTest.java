package com.omarqaterge.hermesmobile;

import java.util.Arrays;

/** Plain-Java test (no JUnit): run by tools/java-unit-tests.sh. Exits non-zero on the first wrong answer. */
public class ApprovalTrackerTest {
    static int n = 0;

    static void is(boolean expected, boolean got, String what) {
        n++;
        if (expected != got) {
            System.err.println("FAIL: " + what + " (expected " + expected + ")");
            System.exit(1);
        }
    }

    static final String CMD = "rm -rf ~/tmp-approval-test";

    public static void main(String[] a) {
        // 1. answered in the app first, then the island's copy shows up or is tapped
        ApprovalTracker t = new ApprovalTracker();
        is(true, ApprovalTracker.answerMatches("r1", CMD, "recursive delete", "r1", CMD, "recursive delete"), "app answer matches island copy by id");
        is(true, ApprovalTracker.answerMatches("", CMD, "recursive delete", "r1", CMD, "recursive delete"), "page copy without id matches by command");
        is(true, ApprovalTracker.answerMatches("", CMD, "", "r1", CMD, "recursive delete"), "empty description still matches by command");
        is(false, ApprovalTracker.answerMatches("", "ls", "", "r1", CMD, ""), "a different command is a different approval");
        is(false, ApprovalTracker.answerMatches("", "", "", "r1", CMD, ""), "no id and no command never matches");
        is(false, ApprovalTracker.answerMatches("r2", CMD, "", "r1", CMD, ""), "different ids never match, even with the same command");
        t.close("r1");
        is(true, t.isClosed("r1"), "answered in the app: closed");
        is(true, t.isClosed("r1"), "the island's late copy is refused");
        is(false, t.isClosed("r2"), "another approval is unaffected");

        // 2. answered on the island first, then the app's sheet answers or the plugin reports it
        t = new ApprovalTracker();
        t.close("r1");
        is(true, t.close("r1"), "a second report for the same id is harmless");
        is(true, t.isClosed("r1"), "still closed after the repeat");
        is(false, ApprovalTracker.answerMatches("r1", CMD, "", "r2", CMD, ""), "the sheet's answer for r1 does not close open r2");
        is(false, t.close(""), "an empty id is never remembered");
        is(false, t.isClosed(""), "an empty id is never treated as closed");
        is(false, t.isClosed(null), "null is never closed");

        // the two copies of one approval (no id first, then the id) are one
        is(true, ApprovalTracker.same("", "s1", CMD, "r1", "s1", CMD), "id-less copy and id copy: same approval");
        is(true, ApprovalTracker.same("r1", "s1", CMD, "r1", "s1", CMD), "same id, same chat");
        is(false, ApprovalTracker.same("r1", "s1", CMD, "r2", "s1", CMD), "different ids are different approvals");
        is(false, ApprovalTracker.same("r1", "s1", CMD, "r1", "s2", CMD), "same id in another chat is different");
        is(false, ApprovalTracker.same("", "s1", CMD, "", "s1", "ls"), "no ids, different commands: different");
        is(true, ApprovalTracker.same(null, null, null, null, null, null), "nulls are empty strings");

        // 3. a late event after close, including after a restart (the ledger file reloads the ids)
        t = new ApprovalTracker();
        t.close("r1");
        t.close("r2");
        ApprovalTracker restarted = new ApprovalTracker();
        restarted.load(Arrays.asList("r1", "r2", ""));
        is(true, restarted.isClosed("r1"), "closed before a restart stays closed");
        is(true, restarted.isClosed("r2"), "second id stays closed");
        is(false, restarted.isClosed("r3"), "unknown id is not closed");
        ApprovalTracker big = new ApprovalTracker();
        for (int i = 0; i < ApprovalTracker.MAX + 5; i++) big.close("id" + i);
        is(false, big.isClosed("id0"), "the oldest is forgotten past the cap");
        is(true, big.isClosed("id" + (ApprovalTracker.MAX + 4)), "the newest is remembered");

        // 4. expiry
        is(true, ApprovalTracker.pastDeadline(1000, 2000), "an event past its deadline is dropped");
        is(false, ApprovalTracker.pastDeadline(3000, 2000), "an event before its deadline is kept");
        is(false, ApprovalTracker.pastDeadline(2000, 2000), "exactly at the deadline is kept");
        is(false, ApprovalTracker.stale(2000 + 15000, 2000, 15000), "not stale until past the grace");
        is(true, ApprovalTracker.stale(2000 + 15001, 2000, 15000), "stale once past the grace");
        t = new ApprovalTracker();
        t.close("r9"); // expired approvals are closed too
        is(true, t.isClosed("r9"), "a late event for an expired approval is refused");

        System.out.println("ApprovalTrackerTest ok (" + n + " checks)");
    }
}
