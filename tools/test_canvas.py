#!/usr/bin/env python3
"""Unit tests for the canvas storage/tool (hermes-plugin/hermes-mobile/canvas.py). Uses a temp HOME.
    python3 tools/test_canvas.py
"""
import importlib.util
import json
import os
import sys
import tempfile

HOME = tempfile.mkdtemp(prefix="hm-canvas-test-")
os.environ["HOME"] = HOME
HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("canvas", os.path.join(HERE, "..", "hermes-plugin", "hermes-mobile", "canvas.py"))
canvas = importlib.util.module_from_spec(spec)
sys.modules["canvas"] = canvas
spec.loader.exec_module(canvas)

fails = 0


def check(name, cond, detail=""):
    global fails
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f"  ({detail})" if detail and not cond else ""))
    fails += 0 if cond else 1


def raises(fn, *a, **k):
    try:
        fn(*a, **k)
    except canvas.CanvasError as e:
        return str(e)
    return None


S = "20260930_abc123"
print("documents")
d = canvas.create(S, "My Plan", "# Plan\n\n- one\n- two\n", by="agent")
check("create returns id/rev/type", d["rev"] == 1 and d["type"] == "markdown" and d["id"].startswith("my-plan-"))
check("html guessed from content", canvas.create(S, "Page", "<!doctype html><h1>x</h1>")["type"] == "html")
check("type from file name", canvas.guess_type("app.py") == ("code", "python") and canvas.guess_type("n.md")[0] == "markdown")
check("list has both", len(canvas.list_docs(S)) == 2)
check("bad type refused", raises(canvas.create, S, "x", "y", type="pdf") is not None)

print("writing and versions")
w = canvas.write(S, d["id"], "# Plan\n\n- one\n- two\n- three\n", by="user")
check("write bumps rev", w["rev"] == 2)
check("conflict refused", "conflict" in (raises(canvas.write, S, d["id"], "x", base_rev=1) or ""))
check("write with right base_rev", canvas.write(S, d["id"], "# Plan v3", by="user", base_rev=2)["rev"] == 3)
check("identical write is a no-op", canvas.write(S, d["id"], "# Plan v3")["rev"] == 3)
v = canvas.get(S, d["id"])
check("versions list has authors", [x["by"] for x in v["versions"]] == ["agent", "user", "user"])
check("old version content", canvas.get_version(S, d["id"], 1)["content"].startswith("# Plan\n\n- one"))
r = canvas.restore(S, d["id"], 1)
check("restore creates a new revision", r["rev"] == 4 and canvas.get(S, d["id"])["content"].startswith("# Plan\n\n- one"))
for i in range(60):
    canvas.write(S, d["id"], f"content {i}")
check("versions are capped", len(canvas.get(S, d["id"])["versions"]) == canvas.MAX_VERSIONS)
check("size limit", "too large" in (raises(canvas.write, S, d["id"], "x" * (canvas.MAX_CHARS + 1)) or ""))

print("patching")
p = canvas.create(S, "Patchme", "alpha beta alpha gamma")["id"]
check("ambiguous find refused", "2 times" in (raises(canvas.patch, S, p, [{"find": "alpha", "replace": "A"}]) or ""))
check("all:true replaces every match", canvas.patch(S, p, [{"find": "alpha", "replace": "A", "all": True}])["chars"] == len("A beta A gamma"))
check("missing text refused", "not found" in (raises(canvas.patch, S, p, [{"find": "zzz", "replace": "y"}]) or ""))
check("failed patch changed nothing", canvas.get(S, p)["content"] == "A beta A gamma")
canvas.patch(S, p, [{"find": "beta", "replace": "B"}, {"find": "gamma", "replace": "G"}])
check("several edits in order", canvas.get(S, p)["content"] == "A B A G")

print("files")
f = os.path.join(os.path.realpath(HOME), "notes.md")  # realpath: on macOS /var is a symlink to /private/var
open(f, "w").write("# From file\nhello")
o = canvas.open_file(S, f)
check("open_file links the path", o["path"] == f and o["type"] == "markdown" and "hello" in o["content"])
open(f, "w").write("# From file\nchanged outside")
o2 = canvas.open_file(S, f)
check("opening the same file again refreshes it", o2["id"] == o["id"] and "changed outside" in o2["content"] and o2["rev"] == 2)
canvas.write(S, o["id"], "edited in canvas", by="user")
check("save_file writes back", canvas.save_file(S, o["id"])["chars"] == 16 and open(f).read() == "edited in canvas")
check("binary refused", "binary" in (raises(canvas.open_file, S, "/bin/ls") or "") or "too big" in (raises(canvas.open_file, S, "/bin/ls") or ""))
check("relative path refused", raises(canvas.open_file, S, "notes.md") is not None)
check("missing file refused", "no such file" in (raises(canvas.open_file, S, "/nope/nothing.txt") or ""))
check("save without a linked file refused", "isn't linked" in (raises(canvas.save_file, S, d["id"]) or ""))

print("agent tool")
def call(**a):
    return json.loads(canvas.tool_handler(a, session_id="tool-chat"))
c = call(action="create", title="Notes", content="hi", type="markdown")
check("tool create", c["ok"] and c["shown"])
check("id optional with one document", call(action="write", content="hi there")["ok"])
r = call(action="read")
check("tool read returns content + last editor", r["content"] == "hi there" and r["last_edited_by"] == "agent")
canvas.write("tool-chat", c["id"], "user typed this", by="user")
check("agent sees the user's edit", call(action="read")["last_edited_by"] == "user")
call(action="create", title="Second", content="2")
check("id required with several documents", not call(action="read")["ok"])
check("unknown action reported", "unknown action" in call(action="dance")["error"])
check("errors don't raise", call(action="patch", id="nope", edits=[{"find": "a", "replace": "b"}])["ok"] is False)
check("list", len(call(action="list")["documents"]) == 2)
check("close", call(action="close", id=c["id"])["ok"] and len(call(action="list")["documents"]) == 1)
check("no chat attached", json.loads(canvas.tool_handler({"action": "list"}))["ok"] is False)

print("offline lint")
h = call(action="create", title="chart", type="html", content='<script src="https://cdn.jsdelivr.net/npm/chart.js"></script><script>fetch("/x")</script>')
check("CDN script + fetch produce a warning", h["ok"] and h.get("warnings") and "cdn" in h["warnings"][0].lower() or "https" in h["warnings"][0], str(h))
w = call(action="write", id=h["id"], content="<html><body><svg width='10' height='10'></svg><a href='#x'>ok</a><script>document.title='ok'</script></body></html>")
check("clean HTML gives no warning", w["ok"] and "warnings" not in w, str(w))
w = call(action="patch", id=h["id"], edits=[{"find": "<a href='#x'>ok</a>", "replace": "<img src='//evil.example/a.png'>"}])
check("a patch that adds an external URL warns", bool(w.get("warnings")), str(w))
m = call(action="create", title="note", type="markdown", content="see https://example.com and fetch(x)")
check("markdown is never linted", m["ok"] and "warnings" not in m)
call(action="close", id=h["id"]); call(action="close", id=m["id"])

print("safety")
check("path tricks in the chat id are neutralised", canvas._dir("../../etc").resolve().parent == canvas.ROOT.resolve())
check("empty ids refused", raises(canvas._safe, "") is not None)

print("concurrency")
import subprocess
canvas.create("race", "doc", "x")
rid = canvas.list_docs("race")[0]["id"]
code = (
    "import importlib.util,sys;"
    f"s=importlib.util.spec_from_file_location('canvas',{os.path.join(HERE, '..', 'hermes-plugin', 'hermes-mobile', 'canvas.py')!r});"
    "m=importlib.util.module_from_spec(s);s.loader.exec_module(m);"
    f"[m.write('race',{rid!r},f'writer {{sys.argv[1]}} step {{i}}',by='agent') for i in range(15)]"
)
procs = [subprocess.Popen([sys.executable, "-c", code, str(n)], env=os.environ) for n in range(4)]
[p.wait() for p in procs]
final = canvas.get("race", rid)
check("4 processes x 15 writes: no lost or corrupt revision", final["rev"] == 61 and final["content"].startswith("writer "), f"rev {final['rev']}")

print(f"\n{'ALL PASSED' if not fails else str(fails) + ' FAILED'}")
sys.exit(1 if fails else 0)
