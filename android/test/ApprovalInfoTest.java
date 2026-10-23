package com.omarqaterge.hermesmobile;

/** Plain-Java test (no JUnit): run by tools/java-unit-tests.sh. Exits non-zero on the first wrong answer. */
public class ApprovalInfoTest {
    static int n = 0;

    static void eq(String expected, String got) {
        n++;
        if (!expected.equals(got)) {
            System.err.println("FAIL: expected [" + expected + "] got [" + got + "]");
            System.exit(1);
        }
    }

    public static void main(String[] a) {
        eq("Taxes · in /root · asked 12s ago\nYou asked: delete the temp files", ApprovalInfo.meta("Taxes", "/root", "delete the temp files", 12));
        eq("", ApprovalInfo.meta("", "", "", -1));
        eq("", ApprovalInfo.meta(null, null, null, -1));
        eq("in /root", ApprovalInfo.meta("", "/root", "", -1));
        eq("Taxes", ApprovalInfo.meta("Taxes", "", "", -1));
        eq("asked 0s ago", ApprovalInfo.meta("", "", "", 0));
        eq("You asked: x", ApprovalInfo.meta("", "", "x", -1));
        eq("59s", ApprovalInfo.age(59));
        eq("1m", ApprovalInfo.age(60));
        eq("59m", ApprovalInfo.age(3599));
        eq("1h", ApprovalInfo.age(3600));
        eq("Taxes", ApprovalInfo.meta("  Taxes  ", "  ", "  ", -1));
        System.out.println("ApprovalInfoTest ok (" + n + " checks)");
    }
}
