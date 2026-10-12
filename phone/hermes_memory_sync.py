#!/usr/bin/env python3
"""Two-way, conflict-free sync of Hermes built-in memory (USER.md / MEMORY.md)
between devices through a private git repo.

Design
------
* Every memory entry is one immutable file in the repo, named
  ``<created_ms>-<sha256[:16]>.md`` and holding the exact entry text. Editing an
  entry = delete old file + add new file, so two devices can never touch the same
  path in incompatible ways -> git rebases are always clean.
* Repo layout: ``<home>/<scope>/<target>/<file>.md`` where
  home   = ``default`` or a profile name (``~/.hermes/profiles/<name>``),
  scope  = ``shared`` | ``mac`` | ``phone``,
  target = ``user`` (USER.md) | ``memory`` (MEMORY.md).
  A device sees ``shared`` + its own scope; the other device's scope is never loaded.
* Three-way merge per home/target against the set both sides agreed on at the last
  sync (``state.json``): local adds/deletes go to the repo, remote adds/deletes come
  back. Nothing is ever lost by a concurrent edit on the other device.
* Scope of a NEW entry: ``Mac:`` / ``Phone:`` prefix -> that device; otherwise, if it
  replaced a device-only entry in the same sync window (text similarity), it keeps
  that scope; otherwise ``shared``.
* Local files are written exactly like Hermes writes them (entries stripped, joined
  by ``\\n§\\n``, atomic rename, mode 0600) while holding Hermes's own
  ``<file>.lock`` flock, so Hermes never sees drift and never loses a concurrent write.
* Safety valve: a sync that would delete more than half of a non-trivial memory
  (local file wiped, repo emptied) is refused for that file and reported.

Usage
-----
  hermes_memory_sync.py init --device mac|phone --remote <git-url>
  hermes_memory_sync.py [sync]
  hermes_memory_sync.py status
Stdlib only; Python >= 3.9.
"""

import argparse
import contextlib
import difflib
import fcntl
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path

DELIM = "\n§\n"
TARGETS = {"user": "USER.md", "memory": "MEMORY.md"}
DEVICES = ("mac", "phone")
DEFAULT_LIMITS = {"user": 1375, "memory": 2200}
PREFIX_RE = re.compile(r"^\s*(mac|phone)\s*:", re.IGNORECASE)
SIMILARITY_INHERIT = 0.6
MASS_DELETE_MIN = 4  # valve only applies when at least this many entries would vanish
GIT_TIMEOUT = 60


# ── paths & config ───────────────────────────────────────────

def hermes_home() -> Path:
    return Path(os.environ.get("HERMES_HOME") or Path.home() / ".hermes").expanduser()


def sync_dir() -> Path:
    return Path(os.environ.get("HERMES_MEMORY_SYNC_DIR") or hermes_home() / "memory-sync")


def repo_dir() -> Path:
    return sync_dir() / "repo"


def read_device() -> str:
    p = sync_dir() / "device"
    dev = p.read_text(encoding="utf-8").strip().lower() if p.exists() else ""
    if dev not in DEVICES:
        raise SystemExit(f"memory-sync: device not configured ({p}); run: init --device mac|phone --remote URL")
    return dev


def local_homes() -> dict:
    homes = {"default": hermes_home()}
    prof = hermes_home() / "profiles"
    if prof.is_dir():
        for d in sorted(prof.iterdir()):
            # A ".no-memory-sync" marker keeps a profile local (demo/test profiles).
            if d.is_dir() and (d / "memories").is_dir() and not (d / ".no-memory-sync").exists():
                homes[d.name] = d
    return homes


def char_limits(home: Path) -> dict:
    limits = dict(DEFAULT_LIMITS)
    cfg = home / "config.yaml"
    try:
        text = cfg.read_text(encoding="utf-8")
    except OSError:
        return limits
    for target, key in (("memory", "memory_char_limit"), ("user", "user_char_limit")):
        m = re.search(rf"^\s+{key}:\s*(\d+)\s*$", text, re.MULTILINE)
        if m:
            limits[target] = int(m.group(1))
    return limits


# ── Hermes-compatible file handling ──────────────────────────

def parse_entries(raw: str) -> list:
    return [e for e in (x.strip() for x in raw.split(DELIM)) if e]


def render(entries: list) -> str:
    return DELIM.join(entries)


def entry_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


@contextlib.contextmanager
def hermes_lock(mem_file: Path):
    """Same lock Hermes's MemoryStore takes: flock(LOCK_EX) on '<file>.lock'."""
    lock_path = mem_file.with_suffix(mem_file.suffix + ".lock")
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(lock_path, flags, 0o600)
    try:
        os.fchmod(fd, 0o600)
        fcntl.flock(fd, fcntl.LOCK_EX)
        yield
    finally:
        with contextlib.suppress(OSError):
            fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)


def read_raw(path: Path):
    """(text, ok). Missing file -> ('', True); undecodable/unreadable -> ('', False)."""
    if not path.exists():
        return "", True
    try:
        return path.read_text(encoding="utf-8-sig"), True
    except (OSError, UnicodeDecodeError):
        return "", False


def atomic_write(path: Path, content: str, mode: int = 0o600, prefix: str = ".mem_"):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=prefix)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(content)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, mode)
        os.replace(tmp, path)
    except BaseException:
        with contextlib.suppress(OSError):
            os.unlink(tmp)
        raise


# ── repo model ───────────────────────────────────────────────

def scope_dirs(repo: Path, home: str, target: str, scopes) -> dict:
    return {s: repo / home / s / target for s in scopes}


def load_repo_entries(repo: Path, home: str, target: str, scopes) -> dict:
    """hash -> {"text", "scope", "paths": [..], "order"}; identical texts dedupe."""
    out = {}
    for scope, d in scope_dirs(repo, home, target, scopes).items():
        if not d.is_dir():
            continue
        for p in sorted(d.glob("*.md")):
            try:
                text = p.read_text(encoding="utf-8").strip()
            except (OSError, UnicodeDecodeError):
                continue
            if not text or DELIM in text:
                continue
            h = entry_hash(text)
            rec = out.setdefault(h, {"text": text, "scope": scope, "paths": [], "order": p.name})
            rec["paths"].append(p)
            if scope != "shared":  # a device-scoped copy wins over a shared duplicate
                rec["scope"] = scope
    return out


def new_entry_path(repo: Path, home: str, scope: str, target: str, text: str, ts_ms: int) -> Path:
    return repo / home / scope / target / f"{ts_ms:013d}-{entry_hash(text)}.md"


def classify_new(text: str, removed_scoped: list) -> str:
    m = PREFIX_RE.match(text)
    if m:
        return m.group(1).lower()
    best, best_scope = 0.0, "shared"
    for old_text, old_scope in removed_scoped:
        r = difflib.SequenceMatcher(None, old_text, text).ratio()
        if r > best:
            best, best_scope = r, old_scope
    return best_scope if best >= SIMILARITY_INHERIT else "shared"


# ── core merge (pure: no git, no locks) ──────────────────────

class MergeRefused(Exception):
    pass


def merge(local: list, remote: dict, base, device: str, allow_mass_delete: bool = False):
    """Three-way merge of one home/target.

    local  : ordered entry texts currently in the device's file
    remote : load_repo_entries(...) for scopes shared + device
    base   : {hash: scope} agreed at last sync, or None on first sync
    Returns (result_texts, repo_adds [(text, scope)], repo_deletes [hash], new_base)
    """
    local = list(dict.fromkeys(local))
    lh = {entry_hash(t): t for t in local}
    if base is None:  # first sync on this device: union, never delete anything
        base_keys = set(lh) & set(remote)
    else:
        base_keys = set(base)

    added_local = {h for h in lh if h not in base_keys}
    deleted_local = sorted(h for h in base_keys if h not in lh)
    added_remote = {h for h in remote if h not in base_keys}
    deleted_remote = {h for h in base_keys if h not in remote}

    if not allow_mass_delete:
        for label, gone in (("local", deleted_local), ("remote", deleted_remote)):
            if len(gone) >= MASS_DELETE_MIN and len(gone) > len(base_keys) / 2:
                raise MergeRefused(f"{label} side would delete {len(gone)}/{len(base_keys)} entries")

    removed_scoped = []
    for h in deleted_local:
        scope = (base or {}).get(h) or (remote.get(h) or {}).get("scope")
        if scope and scope != "shared" and h in remote:
            removed_scoped.append((remote[h]["text"], scope))

    repo_adds, keep_local = [], []
    for t in local:
        h = entry_hash(t)
        if h in deleted_remote:
            continue
        if h in added_local:
            scope = remote[h]["scope"] if h in remote else classify_new(t, removed_scoped)
            if h not in remote:
                repo_adds.append((t, scope))
            if scope not in ("shared", device):
                continue  # other device's entry: ship it, don't keep it here
        keep_local.append(t)

    repo_deletes = [h for h in deleted_local if h in remote]
    incoming = sorted((remote[h] for h in added_remote if h not in lh), key=lambda r: r["order"])
    result = keep_local + [r["text"] for r in incoming]

    new_base = {}
    scope_of = {entry_hash(t): s for t, s in repo_adds}
    for t in result:
        h = entry_hash(t)
        new_base[h] = scope_of.get(h) or (remote.get(h) or {}).get("scope") or (base or {}).get(h) or "shared"
    return result, repo_adds, repo_deletes, new_base


# ── git ──────────────────────────────────────────────────────

def git(*args, check=True, timeout=GIT_TIMEOUT):
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0", GIT_ASKPASS="true")
    r = subprocess.run(["git", "-C", str(repo_dir()), *args], capture_output=True, text=True,
                       timeout=timeout, env=env)
    if check and r.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {(r.stderr or r.stdout).strip()[:400]}")
    return r


def has_remote() -> bool:
    return bool(git("remote", check=False).stdout.strip())


def repo_heal(log):
    """Leave the repo in a clean, rebased-free state before reading it."""
    gd = repo_dir() / ".git"
    if (gd / "rebase-merge").exists() or (gd / "rebase-apply").exists():
        log("repo: aborting interrupted rebase")
        git("rebase", "--abort", check=False)
    if (gd / "MERGE_HEAD").exists():
        git("merge", "--abort", check=False)
    if git("status", "--porcelain").stdout.strip():
        log("repo: committing leftover changes from an interrupted run")
        git("add", "-A")
        git("commit", "-q", "-m", "sync: recover interrupted run", check=False)


def pull(log) -> bool:
    if not has_remote():
        return False
    try:
        r = git("pull", "--rebase", "-q", check=False)
    except subprocess.TimeoutExpired:
        log("pull: timed out (offline?) — using last known repo state")
        return False
    if r.returncode != 0:
        log(f"pull: failed — using last known repo state ({(r.stderr or r.stdout).strip()[:200]})")
        git("rebase", "--abort", check=False)
        return False
    return True


def push(log) -> bool:
    if not has_remote():
        return False
    for attempt in range(3):
        try:
            r = git("push", "-q", "origin", "HEAD", check=False)
        except subprocess.TimeoutExpired:
            log("push: timed out — will retry next run")
            return False
        if r.returncode == 0:
            return True
        # Someone pushed first. Entry files are unique + immutable, so this rebase is always clean.
        pr = git("pull", "--rebase", "-q", check=False)
        if pr.returncode != 0:
            git("rebase", "--abort", check=False)
            log(f"push: rebase failed — will retry next run ({(pr.stderr or pr.stdout).strip()[:200]})")
            return False
    log("push: rejected 3 times — will retry next run")
    return False


# ── state / status / log ─────────────────────────────────────

def load_state() -> dict:
    p = sync_dir() / "state.json"
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"version": 1, "homes": {}}


def save_state(state: dict):
    atomic_write(sync_dir() / "state.json", json.dumps(state, indent=1, sort_keys=True), prefix=".state_")


def make_logger():
    log_path = sync_dir() / "sync.log"
    lines = []

    def log(msg):
        line = f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}"
        lines.append(line)
        print(line)
        try:
            if log_path.exists() and log_path.stat().st_size > 1_000_000:
                log_path.replace(log_path.with_suffix(".log.1"))
            with open(log_path, "a", encoding="utf-8") as f:
                f.write(line + "\n")
        except OSError:
            pass
    return log, lines


# ── sync ─────────────────────────────────────────────────────

def sync_once(allow_mass_delete=False) -> int:
    device = read_device()
    repo = repo_dir()
    if not (repo / ".git").is_dir():
        raise SystemExit(f"memory-sync: no repo at {repo}; run init first")
    lock_fd = os.open(sync_dir() / "sync.lock", os.O_RDWR | os.O_CREAT, 0o600)
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        print("memory-sync: another sync is running; skipping")
        os.close(lock_fd)
        return 0
    log, _ = make_logger()
    warnings, summary = [], []
    try:
        repo_heal(log)
        online = pull(log)
        state = load_state()
        state.setdefault("homes", {})
        scopes = ("shared", device)
        ts_ms = int(time.time() * 1000)
        adds_total = dels_total = changed_local = 0

        for home_name, home in local_homes().items():
            limits = char_limits(home)
            for target, fname in TARGETS.items():
                mem_file = home / "memories" / fname
                base = state["homes"].get(home_name, {}).get(target)
                with hermes_lock(mem_file):
                    raw, ok = read_raw(mem_file)
                    if not ok:
                        warnings.append(f"{home_name}/{fname}: unreadable, skipped")
                        continue
                    local = parse_entries(raw)
                    remote = load_repo_entries(repo, home_name, target, scopes)
                    try:
                        result, adds, deletes, new_base = merge(local, remote, base, device, allow_mass_delete)
                    except MergeRefused as e:
                        warnings.append(f"{home_name}/{fname}: REFUSED ({e}); run with --allow-mass-delete if intended")
                        continue
                    rendered = render(result)
                    if rendered != raw or not mem_file.exists() and result:
                        atomic_write(mem_file, rendered)
                        changed_local += 1
                    elif mem_file.exists() and mem_file.stat().st_mode & 0o077:
                        os.chmod(mem_file, 0o600)  # memory is private; match Hermes's owner-only files
                # repo writes happen after Hermes's lock is released
                for i, (text, scope) in enumerate(adds):
                    atomic_write(new_entry_path(repo, home_name, scope, target, text, ts_ms + i), text + "\n",
                                 mode=0o644, prefix=".entry_")
                for h in deletes:
                    for p in remote[h]["paths"]:
                        with contextlib.suppress(FileNotFoundError):
                            p.unlink()
                ts_ms += len(adds)
                adds_total += len(adds)
                dels_total += len(deletes)
                state["homes"].setdefault(home_name, {})[target] = new_base
                used = len(rendered)
                if used > limits[target]:
                    warnings.append(f"{home_name}/{fname}: {used}/{limits[target]} chars — over limit; "
                                    "Hermes will ask the agent to consolidate on the next add")
                if adds or deletes or rendered != raw:
                    summary.append(f"{home_name}/{target}: +{len(adds)} -{len(deletes)} "
                                   f"local={len(result)} ({used}/{limits[target]})")

        if git("status", "--porcelain").stdout.strip():
            git("add", "-A")
            git("commit", "-q", "-m", f"sync({device}): +{adds_total} -{dels_total}")
        pushed = push(log)
        save_state(state)
        status = {"device": device, "last_run": time.strftime("%Y-%m-%d %H:%M:%S"), "online": online,
                  "pushed": pushed, "repo_adds": adds_total, "repo_deletes": dels_total,
                  "local_files_changed": changed_local, "warnings": warnings}
        atomic_write(sync_dir() / "status.json", json.dumps(status, indent=1), prefix=".status_")
        for s in summary:
            log(f"sync: {s}")
        for w in warnings:
            log(f"WARN: {w}")
        log(f"sync done: repo +{adds_total} -{dels_total}, local files changed {changed_local}, "
            f"online={online}, pushed={pushed}")
        return 0
    finally:
        with contextlib.suppress(OSError):
            fcntl.flock(lock_fd, fcntl.LOCK_UN)
        os.close(lock_fd)


def cmd_status() -> int:
    device = read_device()
    st = sync_dir() / "status.json"
    if st.exists():
        print(st.read_text(encoding="utf-8"))
    for home_name, home in local_homes().items():
        limits = char_limits(home)
        for target, fname in TARGETS.items():
            raw, _ = read_raw(home / "memories" / fname)
            es = parse_entries(raw)
            print(f"{device} {home_name:24} {fname:9} {len(es):3} entries {len(render(es)):5}/{limits[target]}")
    return 0


def cmd_init(device: str, remote: str) -> int:
    if device not in DEVICES:
        raise SystemExit("device must be mac or phone")
    sd = sync_dir()
    sd.mkdir(parents=True, exist_ok=True)
    os.chmod(sd, 0o700)
    atomic_write(sd / "device", device + "\n", prefix=".device_")
    repo = repo_dir()
    if not (repo / ".git").is_dir():
        if remote:
            r = subprocess.run(["git", "clone", "-q", remote, str(repo)], capture_output=True, text=True,
                               timeout=120, env=dict(os.environ, GIT_TERMINAL_PROMPT="0"))
            if r.returncode != 0:
                raise SystemExit(f"clone failed: {r.stderr.strip()}")
        else:
            repo.mkdir(parents=True, exist_ok=True)
            git("init", "-q", "-b", "main")
    git("config", "user.name", f"Hermes memory sync ({device})")
    git("config", "user.email", "hermes-mobile@users.noreply.github.com")
    git("config", "pull.rebase", "true")
    print(f"memory-sync initialised: device={device} repo={repo}")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Conflict-free Hermes memory sync")
    sub = ap.add_subparsers(dest="cmd")
    i = sub.add_parser("init")
    i.add_argument("--device", required=True)
    i.add_argument("--remote", default="")
    s = sub.add_parser("sync")
    s.add_argument("--allow-mass-delete", action="store_true")
    sub.add_parser("status")
    a = ap.parse_args(argv)
    if a.cmd == "init":
        return cmd_init(a.device.lower(), a.remote)
    if a.cmd == "status":
        return cmd_status()
    return sync_once(allow_mass_delete=getattr(a, "allow_mass_delete", False))


if __name__ == "__main__":
    sys.exit(main())
