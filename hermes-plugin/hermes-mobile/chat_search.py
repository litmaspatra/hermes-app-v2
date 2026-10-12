"""find_chats: find the user's chats by text and/or by the images, video, audio or files in them, in ONE call.

Without it the agent has to reverse-engineer state.db, the canvas store and the upload folders (a real
attempt took ~48 tool calls). Stdlib only; every database is opened read-only.

Text: every word must appear in one message (user or agent reply; tool output is skipped) or in the title.
What counts as media, per chat:
- from the user: `@image:<path>` lines (photos sent from the app) and `@file:<path>` attachments;
- from the agent: `MEDIA:<path>` lines and markdown images in its replies, canvas documents that are or
  embed images (svg, <img>, data:image), vision_analyze calls (it looked at an image), text_to_speech audio.
cleanup() removes uploads and canvas folders that no existing chat uses; the app calls it after deleting a
chat (POST /api/plugins/hermes-mobile/cleanup).
"""
import glob
import json
import os
import re
import sqlite3
from typing import Any, Dict, List

HOME = os.environ.get("HERMES_HOME_ROOT", "/root")
HERMES = os.path.join(HOME, ".hermes")

EXT = {
    "image": {"jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "avif"},
    "video": {"mp4", "mov", "webm", "mkv", "avi", "3gp"},
    "audio": {"mp3", "m4a", "wav", "ogg", "opus", "aac", "flac"},
}
KINDS = ("image", "video", "audio", "file", "media")

_AT = re.compile(r"@(image|file):(\S+)")
_MEDIA = re.compile(r"^\s*MEDIA:\s*(.+?)\s*$", re.M)
_MDIMG = re.compile(r"!\[[^\]]*\]\(\s*<?([^)\s>]+)")
_IMG_HTML = re.compile(r"<img\b|data:image/", re.I)

TOOL_SCHEMA = {
    "name": "find_chats",
    "description": (
        "Find the user's chats (all profiles) in ONE call: by words in the messages or titles, and/or by what they "
        "contain: images, videos, audio or attached files, both what the user sent (photos, screenshots, "
        "attachments) and what you showed (MEDIA lines, markdown images, canvas documents with images, images you "
        "analysed, speech audio). Use it for 'which chats have images', 'the chat where I sent a PDF', 'where did "
        "we talk about X'; don't search state.db, logs or folders by hand. Newest first, with chat ids, titles, "
        "links, matching snippets, file paths and whether each file still exists. Then read a chat with "
        "session_search(session_id=...)."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "text": {"type": "string", "description": "Words to find (all must appear in one message or the title). Optional."},
            "kind": {"type": "string", "enum": list(KINDS),
                     "description": "Only chats containing this: image, video, audio, file (documents etc.) or media (any of them). "
                                    "Omit for a text-only search; with neither text nor kind, image is assumed."},
            "who": {"type": "string", "enum": ["user", "agent", "any"], "description": "user = sent/written by the user, agent = by you (default any)."},
            "profile": {"type": "string", "description": "Profile name, or 'all' (default)."},
            "limit": {"type": "integer", "description": "Max chats to return (default 30)."},
        },
    },
}


def kind_of(path: str) -> str:
    p = path.split("?")[0].lower()
    if p.startswith("data:image/"):
        return "image"
    ext = p.rsplit(".", 1)[-1] if "." in p.rsplit("/", 1)[-1] else ""
    for k, exts in EXT.items():
        if ext in exts:
            return k
    return "file"


def _abs(path: str) -> str:
    path = path.strip().strip("`'\"")
    if path.startswith("~/"):
        return os.path.join(HOME, path[2:])
    if path.startswith(".hermes/"):  # file.attach refs are relative to home
        return os.path.join(HOME, path)
    return path


def _exists(path: str):
    if not path.startswith("/"):
        return None  # a URL or data: URI
    return os.path.exists(path)


def profiles() -> Dict[str, str]:
    out = {"default": os.path.join(HERMES, "state.db")}
    for db in sorted(glob.glob(os.path.join(HERMES, "profiles", "*", "state.db"))):
        name = os.path.basename(os.path.dirname(db))
        if not name.startswith("."):
            out[name] = db
    return {p: db for p, db in out.items() if os.path.exists(db)}


def _items_from_message(role: str, content: str, tool_calls: str) -> List[Dict[str, str]]:
    found: List[Dict[str, str]] = []
    content = content or ""
    if role == "user":
        # The attached-context block repeats each @file ref; keep only the part above it.
        head = content.split("\n--- Attached Context ---", 1)[0]
        for tag, raw in _AT.findall(head):
            path = _abs(raw)
            found.append({"who": "user", "how": "sent" if tag == "image" else "attached",
                          "kind": "image" if tag == "image" else kind_of(path), "path": path})
    elif role == "assistant":
        for raw in _MEDIA.findall(content):
            path = _abs(raw)
            found.append({"who": "agent", "how": "showed", "kind": kind_of(path), "path": path})
        for raw in _MDIMG.findall(content):
            found.append({"who": "agent", "how": "showed", "kind": "image", "path": _abs(raw)[:300]})
        if tool_calls and ("vision_analyze" in tool_calls or "text_to_speech" in tool_calls):
            try:
                calls = json.loads(tool_calls)
            except Exception:
                calls = []
            for c in calls if isinstance(calls, list) else []:
                fn = (c.get("function") or c) if isinstance(c, dict) else {}
                name = fn.get("name")
                if name not in ("vision_analyze", "text_to_speech"):
                    continue
                try:
                    args = json.loads(fn.get("arguments") or "{}")
                except Exception:
                    args = {}
                if name == "vision_analyze":
                    src = str(args.get("image_url") or args.get("image_path") or args.get("path") or "")
                    found.append({"who": "agent", "how": "analysed", "kind": "image", "path": _abs(src)[:300]})
                else:
                    found.append({"who": "agent", "how": "spoke", "kind": "audio", "path": ""})
    return found


def _canvas_items(sid: str) -> List[Dict[str, str]]:
    out = []
    for f in sorted(glob.glob(os.path.join(HERMES, "mobile", "canvas", glob.escape(sid), "*.json"))):
        try:
            with open(f, encoding="utf-8") as fp:
                d = json.load(fp)
        except Exception:
            continue
        content = d.get("content") or ""
        path = d.get("path") or ""
        if d.get("type") == "svg" or _IMG_HTML.search(content) or (path and kind_of(path) == "image"):
            out.append({"who": "agent", "how": "canvas", "kind": "image", "path": path,
                        "canvas": d.get("title", ""), "canvas_type": d.get("type", "")})
    return out


def _snippet(content: str, words: List[str]) -> str:
    low = content.lower()
    i = min((low.find(w) for w in words if low.find(w) >= 0), default=0)
    lo = max(0, i - 60)
    return ("…" if lo else "") + " ".join(content[lo:i + 140].split()) + ("…" if i + 140 < len(content) else "")


def search(text: str = "", kind: str = "", who: str = "any", profile: str = "all", limit: int = 30,
           exclude: str = "") -> Dict[str, Any]:
    words = [w for w in (text or "").lower().split() if w]
    kind = kind if kind in KINDS else ("" if words else "image")
    who = who if who in ("user", "agent", "any") else "any"
    limit = max(1, min(int(limit or 30), 200))
    roles = {"user": ("user",), "agent": ("assistant",), "any": ("user", "assistant")}[who]
    dbs = profiles()
    if profile and profile != "all":
        dbs = {p: db for p, db in dbs.items() if p == profile}
    chats: List[Dict[str, Any]] = []
    for prof, db in dbs.items():
        try:
            con = sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=5)
        except sqlite3.Error:
            continue
        per: Dict[str, List[Dict[str, str]]] = {}
        hits: Dict[str, List[Dict[str, Any]]] = {}
        try:
            meta = {r[0]: (r[1], r[2] or r[3]) for r in con.execute(
                "SELECT id, title, last_activity_at, started_at FROM sessions")}
            rows = con.execute(
                "SELECT session_id, role, content, tool_calls FROM messages WHERE "
                "(role='user' AND (instr(content,'@image:')>0 OR instr(content,'@file:')>0)) OR "
                "(role='assistant' AND (instr(content,'MEDIA:')>0 OR instr(content,'![')>0 OR "
                "instr(tool_calls,'vision_analyze')>0 OR instr(tool_calls,'text_to_speech')>0))")
            for sid, role, content, tc in rows:
                for it in _items_from_message(role, content, tc):
                    per.setdefault(sid, []).append(it)
            if words:
                cond = " AND ".join(["instr(lower(content), ?) > 0"] * len(words))
                marks = ",".join("?" * len(roles))
                for mid, sid, role, content in con.execute(
                        f"SELECT id, session_id, role, content FROM messages WHERE role IN ({marks}) AND {cond} ORDER BY id",
                        (*roles, *words)):
                    hits.setdefault(sid, []).append({"msg_id": mid, "who": "user" if role == "user" else "agent",
                                                     "snippet": _snippet(content, words)})
        except sqlite3.Error:
            con.close()
            continue
        con.close()
        for sid, (title, at) in meta.items():
            items = per.get(sid, []) + (_canvas_items(sid) if kind else [])
            if sid == exclude:
                continue
            if kind:
                items = [it for it in items if (kind == "media" or it["kind"] == kind) and (who == "any" or it["who"] == who)]
                if not items:
                    continue
            th = bool(words) and all(w in (title or "").lower() for w in words)
            mh = hits.get(sid, [])
            if words and not (th or mh):
                continue
            chat: Dict[str, Any] = {
                "id": sid, "profile": prof, "title": title or "(untitled)", "last_active": at,
                "link": f"[{title or sid}](hermes-chat:{sid})" if prof == "default" else f"@session:{prof}/{sid}",
            }
            if words:
                chat["title_match"] = th
                chat["text_hits"] = len(mh)
                chat["snippets"] = mh[:3]
            if kind:
                uniq, keys = [], set()
                for it in items:
                    k = (it["who"], it["how"], it["path"], it.get("canvas", ""))
                    if k not in keys:
                        keys.add(k)
                        ex = _exists(it["path"]) if it["path"] else None
                        uniq.append(dict(it, exists=ex) if ex is not None else it)
                chat.update({"from_user": sum(1 for it in uniq if it["who"] == "user"),
                             "from_agent": sum(1 for it in uniq if it["who"] == "agent"),
                             "items": uniq[:12], "more_items": max(0, len(uniq) - 12)})
            chats.append(chat)
    chats.sort(key=lambda c: c["last_active"] or 0, reverse=True)
    out: Dict[str, Any] = {"ok": True, "text": " ".join(words), "kind": kind or None, "who": who,
                           "profiles": list(dbs), "chats_found": len(chats), "chats": chats[:limit]}
    if len(chats) > limit:
        out["more_chats"] = len(chats) - limit
    if kind in ("image", "media") and who in ("user", "any") and not words:
        try:
            orphans = [f for f in leftovers()["files"] if kind_of(f) == "image"]
        except Exception:
            orphans = []
        if orphans:
            out["unreferenced_uploads"] = orphans[:50]
            out["note"] = ("unreferenced_uploads are photos whose chat was deleted; the app removes them the next "
                           "time a chat is deleted.")
    return out


KEEP_NEW_S = 15 * 60  # never touch fresh uploads: another client may have one attached but not sent yet


def _alive_ids() -> set:
    ids = set()
    for db in profiles().values():
        con = sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=5)
        try:
            ids.update(r[0] for r in con.execute("SELECT id FROM sessions"))
        finally:
            con.close()
    return ids


def leftovers(now: float = 0.0, keep=()) -> Dict[str, List[str]]:
    """Uploads (photos, attached files) and canvas folders that belong to no existing chat.

    A file counts as used while any user message or agent reply in any profile, or any canvas document,
    names it (tool output doesn't count: a directory listing would keep everything alive), or its name is in
    `keep` (attached in the app's composer, not sent yet). Raises on a
    database error, so a broken read never looks like "nothing is used"."""
    import time
    now = now or time.time()
    keep = {os.path.basename(str(k)) for k in keep or ()}
    dbs = profiles()
    if not dbs:
        raise RuntimeError("no chat database found")
    canvas_text = []
    for f in glob.glob(os.path.join(HERMES, "mobile", "canvas", "*", "*.json")):
        try:
            with open(f, encoding="utf-8", errors="replace") as fp:
                canvas_text.append(fp.read())
        except OSError:
            pass
    canvas_blob = "\n".join(canvas_text)
    cons = [sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=5) for db in dbs.values()]
    try:
        def used(name: str) -> bool:
            if name in canvas_blob:
                return True
            return any(c.execute("SELECT 1 FROM messages WHERE role IN ('user','assistant') AND instr(content, ?) > 0 "
                                 "LIMIT 1", (name,)).fetchone() for c in cons)

        files = []
        for folder in ("images", "attachments"):
            for f in sorted(glob.glob(os.path.join(HERMES, folder, "*"))):
                name = os.path.basename(f)
                if os.path.isfile(f) and name not in keep and now - os.path.getmtime(f) > KEEP_NEW_S and not used(name):
                    files.append(f)
        alive = set()
        for c in cons:
            alive.update(r[0] for r in c.execute("SELECT id FROM sessions"))
    finally:
        for c in cons:
            c.close()
    canvases = []
    for d in sorted(glob.glob(os.path.join(HERMES, "mobile", "canvas", "*"))):
        if os.path.isdir(d) and os.path.basename(d) not in alive and now - os.path.getmtime(d) > KEEP_NEW_S:
            canvases.append(d)
    return {"files": files, "canvases": canvases}


def _size(path: str) -> int:
    if os.path.isfile(path):
        return os.path.getsize(path)
    return sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(path) for f in fs)


def cleanup(dry_run: bool = False, keep=()) -> Dict[str, Any]:
    """Delete what leftovers() finds. Called by the app after it deletes a chat."""
    import shutil
    found = leftovers(keep=keep)
    removed, freed = [], 0
    for p in found["files"] + found["canvases"]:
        n = _size(p)
        if not dry_run:
            try:
                shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
            except OSError:
                continue
        removed.append(p)
        freed += n
    return {"ok": True, "dry_run": dry_run, "removed": removed, "freed_bytes": freed}


def tool_handler(args: Dict[str, Any], session_id: str = "", task_id: str = "", **_: Any) -> str:
    try:
        return json.dumps(search(args.get("text") or "", args.get("kind") or "", args.get("who") or "any",
                                 args.get("profile") or "all", args.get("limit") or 30, exclude=session_id or task_id),
                          ensure_ascii=False)
    except Exception as e:
        return json.dumps({"ok": False, "error": f"{type(e).__name__}: {e}"})


if __name__ == "__main__":  # python3 chat_search.py [--kind image] [--who user] [words…]
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("text", nargs="*")
    ap.add_argument("--kind", default="")
    ap.add_argument("--who", default="any")
    ap.add_argument("--profile", default="all")
    ap.add_argument("--leftovers", action="store_true", help="list what cleanup() would delete (deletes nothing)")
    o = ap.parse_args()
    if o.leftovers:
        print(json.dumps(cleanup(dry_run=True), indent=1))
        raise SystemExit
    print(json.dumps(search(" ".join(o.text), o.kind, o.who, o.profile), indent=1, ensure_ascii=False))
