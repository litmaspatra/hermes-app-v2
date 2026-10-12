package com.omarqaterge.hermesmobile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides whether a command Hermes wants to run may be approved WITHOUT asking, because it only reads.
 * Pure Java (no Android imports) so it is unit-tested in CI. The rule is "unknown means ask": anything that is
 * not clearly a read-only use of an allowlisted program returns ASK, and the user decides as usual.
 *
 * Hard rules that no allowlist entry can switch off:
 *  - no shell operators at all (pipes, redirects, chains, substitution, variables, subshells, newlines);
 *  - no environment assignment or script path in front of the program;
 *  - arguments that point at secrets (keys, tokens, .env ...) always ask, because "read-only" can still leak them.
 */
final class ApprovalPolicy {
    private ApprovalPolicy() {}

    /** One allowlist entry. */
    static final class Rule {
        final String program;
        /** If not empty: the first argument must be one of these (git status, git log ...). */
        final List<String> subs;
        /** Arguments (exact, or `--flag=...`) that are never allowed for this program. */
        final List<String> deny;
        /** If not empty: EVERY argument after the subcommand must be exactly one of these (strict mode). */
        final List<String> only;
        final String notes;

        Rule(String program, List<String> subs, List<String> deny, List<String> only, String notes) {
            this.program = program;
            this.subs = subs;
            this.deny = deny;
            this.only = only;
            this.notes = notes == null ? "" : notes;
        }
    }

    static final class Result {
        final boolean allow;
        final String reason;

        Result(boolean allow, String reason) {
            this.allow = allow;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return (allow ? "ALLOW: " : "ASK: ") + reason;
        }
    }

    /** Programs that can write or delete: adding one to the allowlist needs an explicit confirmation in Settings. */
    static final List<String> WRITERS = Arrays.asList("rm", "mv", "cp", "dd", "chmod", "chown", "tee", "truncate", "sed", "shred",
            "ln", "mkdir", "touch", "install", "rsync", "curl", "wget", "sudo", "su", "kill", "pkill", "python", "python3", "node",
            "sh", "bash", "zsh", "xargs", "awk", "perl", "ruby", "pip", "npm", "git-push");

    private static final String[] SENSITIVE = {".ssh", ".env", ".aws", ".gnupg", "id_rsa", "id_ed25519", ".pem", ".key", ".keystore",
            ".hermes-mobile", "/etc/shadow", "/etc/passwd", "credentials", "secret", "token", "password", "passwd", ".netrc", ".npmrc",
            ".pypirc", ".kube", ".docker/config", "api_key", "apikey", "auth.json"};

    private static List<String> l(String... a) {
        return new ArrayList<>(Arrays.asList(a));
    }

    static Map<String, Rule> defaultRules() {
        Map<String, Rule> m = new LinkedHashMap<>();
        add(m, new Rule("ls", l(), l(), l(), "list files"));
        add(m, new Rule("pwd", l(), l(), l(), "current folder"));
        add(m, new Rule("cat", l(), l(), l(), "show a file"));
        add(m, new Rule("head", l(), l(), l(), "start of a file"));
        add(m, new Rule("tail", l(), l("-f", "-F", "--follow"), l(), "end of a file (not -f)"));
        add(m, new Rule("wc", l(), l(), l(), "count lines or words"));
        add(m, new Rule("grep", l(), l(), l(), "search text"));
        add(m, new Rule("find", l(), l("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprint0", "-fprintf", "-fls"), l(), "find files (no -exec or -delete)"));
        add(m, new Rule("stat", l(), l(), l(), "file details"));
        add(m, new Rule("file", l(), l(), l(), "file type"));
        add(m, new Rule("du", l(), l(), l(), "disk use"));
        add(m, new Rule("df", l(), l(), l(), "free space"));
        add(m, new Rule("date", l(), l("-s", "--set"), l(), "date and time"));
        add(m, new Rule("whoami", l(), l(), l(), "current user"));
        add(m, new Rule("uname", l(), l(), l(), "system info"));
        add(m, new Rule("echo", l(), l(), l(), "print text"));
        add(m, new Rule("git", l("status", "log", "diff", "show", "rev-parse", "branch"),
                l("--output", "-o", "--ext-diff", "--textconv", "-d", "-D", "-m", "-M", "-c", "-C", "--delete", "--move", "--copy",
                        "--set-upstream-to", "-u", "--unset-upstream", "--edit-description"), l(), "git status, log, diff, show, rev-parse, branch (list only)"));
        return m;
    }

    private static void add(Map<String, Rule> m, Rule r) {
        m.put(r.program, r);
    }

    private static final List<String> BRANCH_ONLY = l("-a", "-r", "-v", "-vv", "--list", "--all", "--remotes", "--verbose", "--show-current",
            "--merged", "--no-merged", "--contains");

    static Result evaluate(String command, Map<String, Rule> rules) {
        try {
            return evaluateInner(command, rules);
        } catch (RuntimeException e) {
            return new Result(false, "could not analyse the command");
        }
    }

    private static Result ask(String why) {
        return new Result(false, why);
    }

    private static Result evaluateInner(String command, Map<String, Rule> rules) {
        if (command == null || command.trim().isEmpty()) return ask("no command to check");
        if (command.length() > 2000) return ask("command is too long to check");
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '|' || c == ';' || c == '&' || c == '>' || c == '<' || c == '`' || c == '$' || c == '\\' || c == '\n' || c == '\r'
                    || c == '(' || c == ')' || c == '{' || c == '}' || c == '\u0000')
                return ask("contains a shell operator (" + (c == '\n' ? "newline" : String.valueOf(c)) + ")");
        }
        List<String> t = tokenize(command);
        if (t == null) return ask("unbalanced quotes");
        if (t.isEmpty()) return ask("no command to check");
        String first = t.get(0);
        if (first.indexOf('=') > 0 && !first.startsWith("-")) return ask("sets an environment variable first");
        String prog = first;
        if (first.indexOf('/') >= 0) {
            if (!first.matches("^/(?:[A-Za-z0-9._+-]+/)*bin/[A-Za-z0-9._+-]+$")) return ask("runs a program by path");
            prog = first.substring(first.lastIndexOf('/') + 1);
        }
        Rule rule = rules.get(prog);
        if (rule == null) return ask("'" + prog + "' is not on the read-only list");
        String low = command.toLowerCase(Locale.ROOT);
        for (String s : SENSITIVE) if (low.contains(s)) return ask("touches something that may hold secrets (" + s + ")");

        List<String> args = t.subList(1, t.size());
        int from = 0;
        if (!rule.subs.isEmpty()) {
            if (args.isEmpty() || !rule.subs.contains(args.get(0))) return ask("'" + prog + (args.isEmpty() ? "" : " " + args.get(0)) + "' is not on the read-only list");
            from = 1;
        }
        List<String> only = rule.only;
        if (rule.program.equals("git") && !args.isEmpty() && args.get(0).equals("branch")) only = BRANCH_ONLY;
        for (int i = from; i < args.size(); i++) {
            String a = args.get(i);
            for (String d : rule.deny) {
                if (a.equals(d) || (d.startsWith("--") && a.startsWith(d + "="))) return ask("uses " + d + ", which can change things");
                // combined short flags such as -rf or -Dx for denied single-letter flags
                if (d.length() == 2 && d.charAt(0) == '-' && d.charAt(1) != '-' && a.startsWith("-") && !a.startsWith("--")
                        && a.length() > 2 && a.indexOf(d.charAt(1)) > 0 && !rule.program.equals("grep") && !rule.program.equals("find")
                        && !rule.program.equals("ls") && !rule.program.equals("head") && !rule.program.equals("cat") && !rule.program.equals("wc"))
                    return ask("uses " + d + ", which can change things");
            }
            if (!only.isEmpty() && !only.contains(a)) return ask("'" + a + "' is not allowed here");
        }
        return new Result(true, "read-only " + prog);
    }

    /** Shell-like split on whitespace honouring single and double quotes; null when a quote is left open. */
    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean in = false;
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) {
                if (c == q) q = 0;
                else cur.append(c);
            } else if (c == '\'' || c == '"') {
                q = c;
                in = true;
            } else if (Character.isWhitespace(c)) {
                if (in || cur.length() > 0) out.add(cur.toString());
                cur.setLength(0);
                in = false;
            } else {
                cur.append(c);
                in = true;
            }
        }
        if (q != 0) return null;
        if (in || cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** A short reason when a command is dangerous (shown as a red chip), or null. */
    static String risk(String command) {
        if (command == null) return null;
        String c = " " + command.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") + " ";
        if (c.matches(".*\\brm\\b.* -[a-z]*r[a-z]*f.*") || c.matches(".*\\brm\\b.* -[a-z]*f[a-z]*r.*") || c.contains(" rm -r ") || c.contains("--no-preserve-root"))
            return "Deletes files recursively";
        if (c.matches(".*\\b(curl|wget)\\b.*\\|\\s*(sudo )?(ba|z)?sh\\b.*")) return "Runs a downloaded script";
        if (c.matches(".* dd .*") || c.contains(" dd if=")) return "Writes raw data to a disk or file";
        if (c.contains(" mkfs") || c.contains(" format ")) return "Formats a disk";
        if (c.contains(" chmod 777") || c.contains(" chmod -r 777")) return "Makes files writable by everyone";
        if (c.contains(" chown -r")) return "Changes file owners recursively";
        if (c.contains(" sudo ")) return "Runs with administrator rights";
        if (c.contains("> /dev/") && !c.contains("> /dev/null")) return "Writes to a device";
        if (c.contains(":(){")) return "Fork bomb";
        if (c.contains(" shutdown") || c.contains(" reboot") || c.contains(" poweroff")) return "Shuts down or restarts the machine";
        if (c.contains("git push") && (c.contains("--force") || c.contains(" -f "))) return "Force-pushes and can overwrite history";
        if (c.contains("git reset --hard") || c.contains("git clean -f")) return "Discards local changes";
        return null;
    }
}
