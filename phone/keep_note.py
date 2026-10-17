#!/usr/bin/env python3
"""Create a Google Keep note on this phone (Keep's API is Workspace-only, so we drive the app).

Usage: keep_note.py "title" "body text" [account@gmail.com]   (title may be ""; account defaults to $KEEP_ACCOUNT or you@gmail.com)
Fires Keep's share sheet via Shizuku `rish`, waits for its "Save" button, taps it, and checks the dialog went away.
Needs: Shizuku running, Keep installed. Run from Debian or Termux. Exit 0 = saved, 1 = failed (message on stderr).
"""
import os, re, shlex, subprocess, sys, time

RISH = os.environ.get("RISH", "/data/data/com.termux/files/home/bin/rish")


def sh(cmd: str, timeout: int = 20) -> str:
    r = subprocess.run([RISH, "-c", cmd], capture_output=True, text=True, timeout=timeout)
    return r.stdout


def find(text: str = "", desc: str = "", tries: int = 5) -> tuple[int, int] | None:
    """Centre of the first UI node with this text (or content-desc). uiautomator dumps are flaky (about half of them
    miss some nodes), so a miss is retried a few times."""
    for _ in range(tries):
        pos = _find_once(text, desc)
        if pos:
            return pos
        time.sleep(0.3)
    return None


def _find_once(text: str, desc: str) -> tuple[int, int] | None:
    xml = sh("uiautomator dump /dev/stdout 2>/dev/null")
    for m in re.finditer(r"<node([^>]*)>", xml):
        a = m.group(1)
        t = re.search(r'text="([^"]*)"', a)
        d = re.search(r'content-desc="([^"]*)"', a)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', a)
        if b and ((text and t and t.group(1) == text) or (desc and d and d.group(1) == desc)):
            x1, y1, x2, y2 = map(int, b.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def find_save() -> tuple[int, int] | None:
    return find("Save")


def tap(pos: tuple[int, int]) -> None:
    sh(f"input tap {pos[0]} {pos[1]}")
    time.sleep(1.2)


def pick_account(email: str) -> bool:
    """The save dialog shows the account notes go to; open its chooser and pick `email` if it differs."""
    if find(text=email) and not find(desc="Choose account"):
        return True
    cur = find(text=email)
    chooser = find(desc="Choose account")
    if not chooser:
        return False
    # When the chooser is closed only the current account is listed: if it is the wanted one we're done.
    if cur:
        return True
    tap(chooser)
    row = find(text=email)
    if not row:
        return False
    tap(row)
    return True


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        return 2
    title, body = sys.argv[1], sys.argv[2]
    account = sys.argv[3] if len(sys.argv) > 3 else os.environ.get("KEEP_ACCOUNT", "you@gmail.com")
    cmd = "am start -a android.intent.action.SEND -t text/plain -p com.google.android.keep --es android.intent.extra.TEXT " + shlex.quote(body)
    if title:
        cmd += " --es android.intent.extra.SUBJECT " + shlex.quote(title)
    out = sh(cmd)
    if "Error" in out:
        print("Keep didn't open: " + out.strip(), file=sys.stderr)
        return 1
    pos = None
    for _ in range(12):
        time.sleep(0.7)
        pos = find_save()
        if pos:
            break
    if not pos:
        print("Keep's Save button never appeared (phone locked? Keep signed out?)", file=sys.stderr)
        return 1
    if not pick_account(account):
        print(f"Couldn't select {account} in Keep's account chooser (is it signed in on this phone?)", file=sys.stderr)
        sh("input keyevent 4")
        return 1
    pos = find_save()
    if not pos:
        print("Save button vanished after choosing the account", file=sys.stderr)
        return 1
    tap(pos)
    if find_save():
        print("Tapped Save but the dialog is still showing", file=sys.stderr)
        return 1
    sh("input keyevent 3")  # back to the home screen: leave nothing of Keep in front
    print("saved")
    return 0


if __name__ == "__main__":
    sys.exit(main())
