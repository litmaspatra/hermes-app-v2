package com.omarqaterge.hermesmobile;

/** The context line under an approval: which chat, where it runs, how long ago it was asked, and what you asked. Pure Java. */
final class ApprovalInfo {
    private ApprovalInfo() {}

    /** ageSecs < 0 leaves the age out. Empty pieces are left out; nothing known gives "". */
    static String meta(String chat, String cwd, String asked, long ageSecs) {
        StringBuilder b = new StringBuilder();
        if (chat != null && !chat.trim().isEmpty()) b.append(chat.trim());
        if (cwd != null && !cwd.trim().isEmpty()) {
            if (b.length() > 0) b.append(" · ");
            b.append("in ").append(cwd.trim());
        }
        if (ageSecs >= 0) {
            if (b.length() > 0) b.append(" · ");
            b.append("asked ").append(age(ageSecs)).append(" ago");
        }
        if (asked != null && !asked.trim().isEmpty()) {
            if (b.length() > 0) b.append("\n");
            b.append("You asked: ").append(asked.trim());
        }
        return b.toString();
    }

    static String age(long secs) {
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m";
        return (secs / 3600) + "h";
    }
}
