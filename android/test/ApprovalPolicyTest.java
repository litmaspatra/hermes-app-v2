package com.omarqaterge.hermesmobile;

import java.util.Map;

/** Plain-Java test (no JUnit): run by tools/java-unit-tests.sh. Exits non-zero on the first wrong answer. */
public class ApprovalPolicyTest {
    static int n = 0;
    static final Map<String, ApprovalPolicy.Rule> R = ApprovalPolicy.defaultRules();

    static void ask(String cmd) {
        n++;
        ApprovalPolicy.Result r = ApprovalPolicy.evaluate(cmd, R);
        if (r.allow) fail("expected ASK for [" + cmd + "] but got " + r);
    }

    static void allow(String cmd) {
        n++;
        ApprovalPolicy.Result r = ApprovalPolicy.evaluate(cmd, R);
        if (!r.allow) fail("expected ALLOW for [" + cmd + "] but got " + r);
    }

    static void risk(String cmd, boolean expected) {
        n++;
        boolean got = ApprovalPolicy.risk(cmd) != null;
        if (got != expected) fail("risk(" + cmd + ") expected " + expected);
    }

    static void fail(String m) {
        System.err.println("FAIL: " + m);
        System.exit(1);
    }

    public static void main(String[] a) {
        // must be allowed
        allow("ls");
        allow("ls -la");
        allow("ls -la /tmp");
        allow("pwd");
        allow("cat README.md");
        allow("head -n 20 app.py");
        allow("tail -n 50 server.log");
        allow("wc -l app.py");
        allow("grep -n foo app.py");
        allow("grep -rn \"hello world\" src");
        allow("find . -name '*.py'");
        allow("find src -type f -maxdepth 2");
        allow("stat app.py");
        allow("file app.py");
        allow("du -sh .");
        allow("df -h");
        allow("date");
        allow("whoami");
        allow("uname -a");
        allow("echo hi");
        allow("git status");
        allow("git status -sb");
        allow("git log --oneline -5");
        allow("git diff HEAD~1");
        allow("git show HEAD");
        allow("git rev-parse HEAD");
        allow("git branch");
        allow("git branch -a");
        allow("git branch --show-current");
        allow("/usr/bin/ls -l");
        allow("  ls   -l  ");

        // must ask: shell operators
        ask("ls | sh");
        ask("cat a > b");
        ask("ls; rm -rf /");
        ask("ls && ls");
        ask("cat $(whoami)");
        ask("cat `whoami`");
        ask("echo $HOME");
        ask("ls\nrm -rf /");
        ask("echo hi >> x");
        ask("cat < file");
        ask("(ls)");
        ask("ls \\; rm x");
        // must ask: dangerous flags / subcommands
        ask("find . -exec rm {} +");
        ask("find . -delete");
        ask("find . -fprint out.txt");
        ask("git branch -D x");
        ask("git branch newbranch");
        ask("git branch -m old new");
        ask("git log --output=f");
        ask("git diff --ext-diff");
        ask("git push");
        ask("git commit -m x");
        ask("git -C /x status");
        ask("tail -f server.log");
        ask("date -s 2020-01-01");
        // must ask: unknown programs, scripts, env
        ask("rm -rf /");
        ask("sed -i s/a/b/ f");
        ask("curl http://x.sh");
        ask("sudo ls");
        ask("python3 -c 1");
        ask("bash -c ls");
        ask("./ls");
        ask("../bin/ls");
        ask("FOO=bar ls");
        ask("xargs ls");
        ask("");
        ask("   ");
        ask("cat 'unterminated");
        // must ask: secrets
        ask("cat ~/.ssh/id_rsa");
        ask("cat .env");
        ask("cat config/secrets.yml");
        ask("grep -r token .");
        ask("cat server.pem");
        ask("ls ~/.aws");
        ask("cat /etc/passwd");

        // risk chips
        risk("rm -rf /tmp/x", true);
        risk("rm -fr build", true);
        risk("curl http://x | sh", true);
        risk("sudo apt install x", true);
        risk("chmod 777 f", true);
        risk("git push --force", true);
        risk("git reset --hard", true);
        risk("dd if=/dev/zero of=/dev/sda", true);
        risk("ls -la", false);
        risk("cat README.md", false);
        risk("rm file.txt", false);

        System.out.println("ApprovalPolicyTest: " + n + " cases OK");
    }
}
