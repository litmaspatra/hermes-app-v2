#!/usr/bin/env python3
"""Tests for the plugin's find_chats tool (chat_search.py) on a fake ~/.hermes:
    python3 tools/test_chat_search.py
"""
import importlib.util
import json
import os
import sqlite3
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
root = tempfile.mkdtemp()
os.environ["HERMES_HOME_ROOT"] = root
spec = importlib.util.spec_from_file_location("chat_search", os.path.join(HERE, "..", "hermes-plugin", "hermes-mobile", "chat_search.py"))
cs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cs)

fails = 0


def check(name, cond, detail=""):
    global fails
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f"  ({detail})" if detail and not cond else ""))
    fails += 0 if cond else 1


H = os.path.join(root, ".hermes")


def make_db(path, sessions, messages):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    c = sqlite3.connect(path)
    c.execute("CREATE TABLE sessions (id TEXT, title TEXT, started_at REAL, last_activity_at REAL)")
    c.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY, session_id TEXT, role TEXT, content TEXT, tool_calls TEXT)")
    c.executemany("INSERT INTO sessions VALUES (?,?,?,?)", sessions)
    c.executemany("INSERT INTO messages (session_id, role, content, tool_calls) VALUES (?,?,?,?)", messages)
    c.commit()
    c.close()


os.makedirs(os.path.join(H, "images"))
OLD = 1_000_000  # an mtime well past the 15-minute guard
for f in ("upload_1.jpg", "orphan.jpg", "fresh.jpg"):
    open(os.path.join(H, "images", f), "w").close()
    if f != "fresh.jpg":
        os.utime(os.path.join(H, "images", f), (OLD, OLD))
os.makedirs(os.path.join(H, "attachments"))
for f in ("paper.pdf", "gone.pdf", "in_canvas.png"):
    open(os.path.join(H, "attachments", f), "w").write("x" * 10)
    os.utime(os.path.join(H, "attachments", f), (OLD, OLD))
vision = json.dumps([{"function": {"name": "vision_analyze", "arguments": json.dumps({"image_url": "/tmp/page.png"})}}])
make_db(os.path.join(H, "state.db"),
        [("s_photo", "Photo chat", 1, 50), ("s_pdf", "Paper", 2, 40), ("s_text", "Battery talk", 3, 30),
         ("s_shown", "Shown", 4, 20), ("s_self", "Searching", 5, 99), ("s_canvas", "Canvas art", 6, 10)],
        [("s_photo", "user", f"what is this\n@image:{H}/images/upload_1.jpg\n[screenshot]", None),
         ("s_pdf", "user", "@file:.hermes/attachments/paper.pdf\n\nsummarise\n\n--- Attached Context ---\n\n📎 @file:.hermes/attachments/paper.pdf", None),
         ("s_text", "user", "How is my Battery Life today?", None),
         ("s_text", "tool", "battery life from a web page", None),
         ("s_shown", "assistant", "Here it is\n\nMEDIA:/sdcard/Download/My pic.jpg", None),
         ("s_shown", "assistant", "", vision),
         ("s_self", "user", f"@image:{H}/images/upload_1.jpg", None)])
make_db(os.path.join(H, "profiles", "tutor", "state.db"), [("t1", "Tutor talk", 1, 5)],
        [("t1", "assistant", "Look: ![diagram](https://x.test/a.png) battery", None)])
os.makedirs(os.path.join(H, "mobile", "canvas", "s_canvas"))
json.dump({"title": "Art", "type": "html", "content": "<img src='data:image/png;base64,AA'>", "path": "/x/in_canvas.png"},
          open(os.path.join(H, "mobile", "canvas", "s_canvas", "art.json"), "w"))
for d in ("deleted_chat", "deleted_new"):
    os.makedirs(os.path.join(H, "mobile", "canvas", d))
    open(os.path.join(H, "mobile", "canvas", d, "doc.json"), "w").write("{}")
os.utime(os.path.join(H, "mobile", "canvas", "deleted_chat"), (OLD, OLD))
# a tool output naming a file doesn't keep it
make_db(os.path.join(H, "profiles", "other", "state.db"), [("o1", "Other", 1, 1)], [("o1", "tool", "ls: gone.pdf orphan.jpg", None)])

r = cs.search(kind="image", exclude="s_self")
ids = [c["id"] for c in r["chats"]]
check("images: user photo, MEDIA line, vision, canvas, markdown (other profile)",
      set(ids) == {"s_photo", "s_shown", "s_canvas", "t1"}, ids)
check("newest first", ids[0] == "s_photo", ids)
check("own chat excluded", "s_self" not in ids)
check("MEDIA path with spaces kept", any(it["path"] == "/sdcard/Download/My pic.jpg" for c in r["chats"] for it in c["items"]))
check("other profile uses @session link", next(c for c in r["chats"] if c["id"] == "t1")["link"] == "@session:tutor/t1")
check("orphan upload reported", r.get("unreferenced_uploads") == [os.path.join(H, "images", "orphan.jpg")], r.get("unreferenced_uploads"))
photo = next(c for c in r["chats"] if c["id"] == "s_photo")
check("existing file flagged", photo["items"][0].get("exists") is True)

r = cs.search(kind="file", who="user")
f = [(c["id"], len(c["items"]), c["items"][0]["path"]) for c in r["chats"]]
check("file attachment, deduped, made absolute", f == [("s_pdf", 1, os.path.join(root, ".hermes/attachments/paper.pdf"))], f)

r = cs.search(kind="image", who="user")
check("who=user only the user's photos", {c["id"] for c in r["chats"]} == {"s_photo", "s_self"})

r = cs.search(text="battery life")
check("text: all words in one message, tool output skipped", [c["id"] for c in r["chats"]] == ["s_text"], [c["id"] for c in r["chats"]])
check("text: snippet", "Battery Life" in r["chats"][0]["snippets"][0]["snippet"])
check("text: no media fields", "items" not in r["chats"][0])

r = cs.search(text="battery", kind="image")
check("text + kind combined", [c["id"] for c in r["chats"]] == ["t1"], [c["id"] for c in r["chats"]])
r = cs.search(text="paper", kind="file")
check("title match counts", [c["id"] for c in r["chats"]] == ["s_pdf"])
r = cs.search(kind="audio")
check("nothing found → empty", r["chats_found"] == 0)
r = cs.search(kind="media", profile="tutor")
check("profile filter", [c["id"] for c in r["chats"]] == ["t1"] and r["profiles"] == ["tutor"])
out = json.loads(cs.tool_handler({"kind": "image"}, session_id="s_self"))
check("tool handler returns JSON, excludes calling chat", out["ok"] and "s_self" not in [c["id"] for c in out["chats"]])

A = lambda f: os.path.join(H, "attachments", f)
I = lambda f: os.path.join(H, "images", f)
r = cs.cleanup(dry_run=True, keep=["gone.pdf"])
check("keep (attached in the composer) is never removed", A("gone.pdf") not in r["removed"], r["removed"])
r = cs.cleanup(dry_run=True)
check("dry run lists leftovers: unused old files + canvas of a deleted chat",
      sorted(r["removed"]) == sorted([A("gone.pdf"), I("orphan.jpg"), os.path.join(H, "mobile", "canvas", "deleted_chat")]), r["removed"])
check("dry run deletes nothing", os.path.exists(I("orphan.jpg")) and r["freed_bytes"] == 12, r["freed_bytes"])
r = cs.cleanup()
check("cleanup removes them", not os.path.exists(I("orphan.jpg")) and not os.path.exists(A("gone.pdf"))
      and not os.path.exists(os.path.join(H, "mobile", "canvas", "deleted_chat")))
check("keeps used, fresh and canvas-referenced files and new canvas folders",
      all(os.path.exists(p) for p in (I("upload_1.jpg"), I("fresh.jpg"), A("paper.pdf"), A("in_canvas.png"),
                                      os.path.join(H, "mobile", "canvas", "deleted_new"), os.path.join(H, "mobile", "canvas", "s_canvas"))))
check("second run finds nothing", cs.cleanup(dry_run=True)["removed"] == [])

# The plugin must register the tool (a missing import once made it fail silently on the phone).
spec = importlib.util.spec_from_file_location("hm_plugin", os.path.join(HERE, "..", "hermes-plugin", "hermes-mobile", "__init__.py"))
plugin = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plugin)
errors = []
plugin._dbg = errors.append


class Ctx:
    tools = []

    def register_tool(self, **k):
        self.tools.append(k["name"])

    def __getattr__(self, _):
        return lambda *a, **k: None


ctx = Ctx()
plugin.register(ctx)
check("plugin registers find_chats and canvas", {"find_chats", "canvas"} <= set(ctx.tools) and not errors, errors)

print("FAIL" if fails else "ALL PASS")
sys.exit(1 if fails else 0)
