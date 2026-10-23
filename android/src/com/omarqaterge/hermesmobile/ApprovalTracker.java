package com.omarqaterge.hermesmobile;

import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * The rules for which approvals are open, closed or expired, in plain Java so CI can test them. HermesService keeps
 * the open list (JSON objects) and calls these for every decision.
 */
final class ApprovalTracker {
    static final int MAX = 100;

    private final LinkedHashSet<String> closed = new LinkedHashSet<>();

    /** Closed ids from the ledger file, oldest first (after a restart). */
    synchronized void load(Collection<String> ids) {
        for (String id : ids) close(id);
    }

    /** Remembers a request id as closed (answered or expired). Empty ids are never remembered. */
    synchronized boolean close(String rid) {
        if (rid == null || rid.isEmpty()) return false;
        closed.add(rid);
        while (closed.size() > MAX) closed.remove(closed.iterator().next());
        return true;
    }

    /** A closed approval must never be asked again, however late its event arrives. */
    synchronized boolean isClosed(String rid) {
        return rid != null && !rid.isEmpty() && closed.contains(rid);
    }

    /** Two copies of one approval: the id decides when both have one; otherwise same chat and same command. */
    static boolean same(String ra, String sa, String ca, String rb, String sb, String cb) {
        ra = nz(ra);
        rb = nz(rb);
        boolean sameSession = nz(sa).equals(nz(sb));
        if (!ra.isEmpty() && !rb.isEmpty()) return ra.equals(rb) && sameSession;
        return sameSession && nz(ca).equals(nz(cb));
    }

    /** Is the approval answered in the app's sheet (id, command, description) this open one (id, command, what)? */
    static boolean answerMatches(String reqId, String command, String description, String openId, String openCmd, String openWhat) {
        reqId = nz(reqId);
        openId = nz(openId);
        if (!reqId.isEmpty() && !openId.isEmpty()) return reqId.equals(openId);
        String c = nz(command).trim();
        if (c.isEmpty() || !c.equals(nz(openCmd).trim())) return false;
        String d = nz(description).trim(), w = nz(openWhat).trim();
        return d.isEmpty() || w.isEmpty() || d.equals(w);
    }

    /** An event whose deadline already passed must not ask. */
    static boolean pastDeadline(long deadline, long now) {
        return deadline < now;
    }

    /** Hermes gave up at the deadline: an approval this long past it is gone, whatever the plugin said. */
    static boolean stale(long now, long deadline, long staleMs) {
        return now > deadline + staleMs;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
