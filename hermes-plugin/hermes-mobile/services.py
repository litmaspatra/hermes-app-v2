#!/usr/bin/env python3
"""Keeps the phone's voice stack alive and complete, from inside Hermes (stdlib only).

Why this exists: FastBrain's bridge (8765) and the Piper voice server (8766) used to be started by the app through a
short-lived `proot-distro login`. When that login ends, proot kills everything it started, so both vanished as soon as
the app was closed. Hermes itself survives (the app starts it the same way every time), so the plugin now runs this
supervisor in Hermes' own long-lived process:

  * every 20 s: router, bridge, Piper, MiniLM are up, or are started again (children detached with setsid);
  * one control port, 8767 (same key as the other two): /status (the doctor's report), /fix (installs what is missing),
    /ensure, pets (/pet/*, fetched here so the phone never has to reach the pet CDN) and contact aliases (/aliases, /alias);
  * a file lock so only one Hermes process supervises, another takes over if it dies.

Run by hand for testing:  python3 services.py serve | status | fix
"""
from __future__ import annotations

import hmac
import json
import os
import re
import shutil
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOME = Path.home()
FB_PORT, TTS_PORT, CONTROL_PORT = 8765, 8766, 8767
FB_HOME = Path(os.environ.get("FASTBRAIN_HOME", str(HOME / ".fastbrain")))
STATE_DIR = Path(os.environ.get("HM_STATE_DIR", str(HOME / ".hermes-mobile")))
VOICE_DIR = Path(os.environ.get("PIPER_VOICES_DIR", str(HOME / ".piper-voices")))
# Mandatory voices: two female, two male-ish mix, all medium quality (about 60 MB each).
VOICES_WANTED = ("en_US-lessac-medium", "en_US-ryan-medium", "en_US-amy-medium", "en_GB-alan-medium")
FASTBRAIN_REPO = "https://github.com/litmaspatra/FastBrain"
MINILM_BASE = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/"
MINILM_FILES = {"model_qint8_arm64.onnx": "onnx/model_qint8_arm64.onnx?download=true", "vocab.txt": "vocab.txt?download=true"}
MANIFESTS = ("https://agentpets.dev/api/manifest", "https://petdex.dev/api/manifest")
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36 HermesMobile"
VOICE_RE = re.compile(r"^[a-z]{2,3}_[A-Za-z]{2,4}-[A-Za-z0-9_]+-(?:x_low|low|medium|high)$")
ENV_BLOCK = """# FASTBRAIN_MINILM_BEGIN
FASTBRAIN_SEMANTIC_BACKEND=minilm
FASTBRAIN_MINILM_SOCKET="$HOME/.fastbrain/minilm/minilm.sock"
FASTBRAIN_MINILM_TIMEOUT=0.20
FASTBRAIN_MINILM_MIN_SIMILARITY=0.45
FASTBRAIN_MINILM_MIN_MARGIN=0.10
FASTBRAIN_SEMANTIC_HELPER=""
# FASTBRAIN_MINILM_END
"""

_children: dict[str, subprocess.Popen] = {}
_lock_fd = None
_task: dict = {"running": False, "step": "", "log": [], "done": False, "ok": None}
_task_lock = threading.Lock()
_fix_lock = threading.Lock()
_last_import = 0.0


# ------------------------------------------------------------------ small helpers
def key_file() -> Path:
    for c in (os.environ.get("HERMES_MOBILE_KEY_FILE", ""), "/data/data/com.termux/files/home/.hermes-mobile/key", str(HOME / ".hermes-mobile" / "key")):
        if c and Path(c).is_file():
            return Path(c)
    return Path("/data/data/com.termux/files/home/.hermes-mobile/key")


def read_key() -> str:
    try:
        return key_file().read_text().strip()
    except OSError:
        return ""


def port_open(port: int) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.4):
            return True
    except OSError:
        return False


def http_code(port: int, path: str = "/health") -> int:
    try:
        req = urllib.request.Request(f"http://127.0.0.1:{port}{path}", headers={"X-Hermes-Mobile-Key": read_key()})
        with urllib.request.urlopen(req, timeout=2.5) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return 0


def run(cmd: list[str], timeout: float = 120, env: dict | None = None) -> tuple[int, str]:
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env={**os.environ, **(env or {})})
        return p.returncode, ((p.stdout or "") + (p.stderr or "")).strip()[-1500:]
    except subprocess.TimeoutExpired:
        return 124, "timed out"
    except Exception as e:  # noqa: BLE001
        return 127, f"{type(e).__name__}: {e}"


def spawn(name: str, cmd: list[str], log: Path, env: dict | None = None) -> None:
    """Start a long-running child detached from Hermes' terminal (own session), once."""
    old = _children.get(name)
    if old is not None and old.poll() is None:
        return
    log.parent.mkdir(parents=True, exist_ok=True)
    f = open(log, "ab")
    _children[name] = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=f, stderr=subprocess.STDOUT, start_new_session=True,
                                       env={**os.environ, **(env or {})})


def py_ok(py: str, code: str) -> bool:
    return run([py, "-c", code], 25)[0] == 0


def pythons(*names: str) -> list[str]:
    out = []
    for n in names:
        p = shutil.which(n) if not os.path.isabs(n) else (n if os.access(n, os.X_OK) else None)
        if p and p not in out:
            out.append(p)
    return out


def py313() -> str | None:
    for p in pythons(os.environ.get("FASTBRAIN_PYTHON", ""), "python3.14", "python3.13", "python3"):
        if py_ok(p, "import sys; raise SystemExit(0 if sys.version_info>=(3,13) else 1)"):
            return p
    return None


def piper_py() -> str | None:
    for p in pythons(str(STATE_DIR / "venv" / "bin" / "python"), sys.executable, "python3"):
        if py_ok(p, "import piper"):
            return p
    return None


def hermes_py() -> str | None:
    for p in pythons(str(HOME / ".hermes" / "hermes-agent" / "venv" / "bin" / "python")):
        return p
    return None


def fastbrain_root() -> Path | None:
    cands = [os.environ.get("FASTBRAIN_ROOT", ""), str(HOME / "FastBrain")] + sorted(str(p) for p in HOME.glob("*/FastBrain"))
    for c in cands:
        if c and (Path(c) / "scripts" / "fastbrain-router-tcp.py").is_file():
            return Path(c)
    return None


def fastbrain_dirs() -> list[str]:
    return [str(p) for p in [HOME / "FastBrain", *HOME.glob("*/FastBrain")] if (p / "scripts").is_dir()]


def voice_dirs() -> list[Path]:
    return [VOICE_DIR, HOME / "piper-voices", HOME / ".hermes" / "cache" / "piper-voices", HOME / "FastBrain" / "piper-voices"]


def voices_found() -> dict[str, Path]:
    found: dict[str, Path] = {}
    for d in voice_dirs():
        for pat in ("*.onnx", "*/*.onnx"):
            for p in sorted(d.glob(pat)):
                if VOICE_RE.match(p.stem) and p.with_name(p.name + ".json").exists():
                    found.setdefault(p.stem, p)
    return found


def env_text() -> str:
    try:
        return (FB_HOME / "fastbrain.env").read_text()
    except OSError:
        return ""


def minilm_enabled() -> bool:
    return "FASTBRAIN_SEMANTIC_BACKEND=minilm" in env_text()


def minilm_files_ok() -> bool:
    return all((FB_HOME / "minilm" / n).is_file() and (FB_HOME / "minilm" / n).stat().st_size > 1000 for n in MINILM_FILES)


def minilm_up() -> bool:
    return (FB_HOME / "minilm" / "minilm.sock").exists() and (FB_HOME / "minilm" / "minilm.pid").is_file() and _pid_alive(FB_HOME / "minilm" / "minilm.pid")


def _pid_alive(pidfile: Path) -> bool:
    try:
        os.kill(int(pidfile.read_text().strip()), 0)
        return True
    except Exception:  # noqa: BLE001
        return False


def router_sock() -> Path:
    return Path(os.environ.get("FASTBRAIN_ROUTER_SOCKET", str(FB_HOME / "router" / "router.sock")))


def router_up() -> bool:
    s = router_sock()
    if not s.exists():
        return False
    try:
        c = socket.socket(socket.AF_UNIX)
        c.settimeout(1.5)
        c.connect(str(s))
        c.sendall(b"GET /health HTTP/1.0\r\n\r\n")
        ok = b" 200 " in c.recv(256)
        c.close()
        return ok
    except OSError:
        return False


def hindsight_config() -> dict:
    try:
        return json.loads((HOME / ".hermes" / "hindsight-lite" / "config.json").read_text())
    except Exception:  # noqa: BLE001
        return {}


# ------------------------------------------------------------------ keep things running
def ensure() -> list[str]:
    """Start whatever is down (never restarts something that is up). Returns what it did."""
    did: list[str] = []
    root = fastbrain_root()
    if root is not None and (FB_HOME / "app").is_dir():
        if not router_up():
            rc, out = run(["bash", str(root / "scripts" / "start-fastbrain-router.sh")], 60)
            did.append("router" + ("" if rc == 0 else f" (failed: {out[-200:]})"))
        if not port_open(FB_PORT):
            spawn("bridge", [sys.executable, str(root / "scripts" / "fastbrain-router-tcp.py"), "--socket", str(router_sock())], FB_HOME / "router" / "tcp.log")
            did.append("bridge")
        if minilm_enabled() and minilm_files_ok() and not minilm_up() and (FB_HOME / "bin" / "start-minilm").is_file():
            rc, _ = run(["bash", str(FB_HOME / "bin" / "start-minilm")], 40)
            did.append("minilm")
        if not port_open(TTS_PORT) and voices_found():
            pp = piper_py()
            if pp:
                spawn("piper", [pp, str(root / "scripts" / "piper-tts-server.py")], FB_HOME / "router" / "piper.log")
                did.append("piper")
    return did


def _supervise_loop() -> None:
    global _last_import
    while True:
        try:
            if _acquire_lock():
                if not port_open(CONTROL_PORT) and "control" not in _children_threads:
                    _start_control()
                ensure()
                if time.time() - _last_import > 1800:
                    _last_import = time.time()
                    threading.Thread(target=import_hindsight_aliases, daemon=True).start()
        except Exception as e:  # noqa: BLE001
            _log(f"supervisor: {type(e).__name__}: {e}")
        time.sleep(20)


_children_threads: dict[str, threading.Thread] = {}


def _acquire_lock() -> bool:
    global _lock_fd
    if _lock_fd is not None:
        return True
    try:
        import fcntl
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        fd = open(STATE_DIR / "supervisor.lock", "w")
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        fd.write(str(os.getpid()))
        fd.flush()
        _lock_fd = fd
        return True
    except Exception:  # noqa: BLE001
        return False


def _log(msg: str) -> None:
    try:
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        with open(STATE_DIR / "services.log", "a") as f:
            f.write(time.strftime("%F %T ") + msg + "\n")
        if (STATE_DIR / "services.log").stat().st_size > 200_000:
            (STATE_DIR / "services.log").write_text("")
    except OSError:
        pass


# ------------------------------------------------------------------ doctor
def diagnose() -> dict:
    checks: list[dict] = []

    def add(cid: str, title: str, ok: bool, detail: str = "", fix: bool = True) -> None:
        checks.append({"id": cid, "title": title, "ok": bool(ok), "detail": detail, "fix": fix and not ok})

    k = read_key()
    add("key", "App key shared", bool(k), "Open the Hermes app once; it writes the key.", fix=False)
    root = fastbrain_root()
    inst = (FB_HOME / "fastbrain").is_file() and (FB_HOME / "app").is_dir()
    add("fastbrain", "FastBrain installed", root is not None and inst, "" if inst and root else "Not installed (or the install step has not run).")
    dirs = fastbrain_dirs()
    if len(dirs) > 1:
        add("dup_fastbrain", "One FastBrain folder", False, "Several copies: " + ", ".join(dirs) + ". Keep one.", fix=False)
    add("router", "FastBrain router running", router_up(), "The resident router is down.")
    add("bridge", "App bridge (8765)", http_code(FB_PORT) == 200, "The key-protected bridge the app calls is down.")
    add("minilm_files", "MiniLM model files", minilm_files_ok(), "model_qint8_arm64.onnx and vocab.txt are missing (about 23 MB).")
    add("minilm_on", "MiniLM is the semantic brain", minilm_enabled(), "FastBrain's env does not use MiniLM.")
    add("minilm_up", "MiniLM service running", minilm_up(), "The MiniLM service is down.")
    pp = piper_py()
    add("piper_pkg", "Piper installed", pp is not None, "piper-tts is not installed for any Python here.")
    have = voices_found()
    need = [v for v in VOICES_WANTED if v not in have]
    add("voices", f"Voices ({len(VOICES_WANTED) - len(need)}/{len(VOICES_WANTED)})", not need, "Missing: " + ", ".join(need) if need else "")
    add("piper_up", "Piper voice server (8766)", http_code(TTS_PORT) == 200, "The voice server is down.")
    cfg = hindsight_config()
    hp = hermes_py()
    add("hindsight", "Hindsight Lite present", hp is not None and py_ok(hp, "import hindsight_lite"), "Hindsight Lite is not importable in Hermes' Python.", fix=False)
    add("memory_write", "Hermes can save new memories on its own", bool(cfg.get("llm_model")),
        "hindsight-lite/config.json has no llm_model, so automatic retention is OFF: only an explicit hmem_remember saves. Set llm_base_url + llm_model (any small chat model).", fix=False)
    n = len(read_aliases())
    return {"ok": all(c["ok"] for c in checks if c["id"] not in ("dup_fastbrain",)), "checks": checks, "voices": sorted(have), "aliases": n,
            "task": dict(_task), "time": int(time.time())}


def _step(msg: str) -> None:
    with _task_lock:
        _task["step"] = msg
        _task["log"] = (_task["log"] + [msg])[-40:]
    _log("fix: " + msg)


def _pip(pkgs: list[str], py: str | None = None) -> tuple[int, str]:
    py = py or shutil.which("python3") or sys.executable
    rc, out = run([py, "-m", "pip", "install", "--upgrade", *pkgs], 900)
    if rc != 0 and "externally-managed" in out.lower():
        rc, out = run([py, "-m", "pip", "install", "--break-system-packages", "--upgrade", *pkgs], 900)
    return rc, out


def _download(url: str, dest: Path, timeout: float = 120) -> bool:
    try:
        tmp = dest.with_suffix(dest.suffix + ".part")
        dest.parent.mkdir(parents=True, exist_ok=True)
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=timeout) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f)
        tmp.replace(dest)
        return True
    except Exception as e:  # noqa: BLE001
        _log(f"download {url}: {e}")
        return False


def fix_all() -> None:
    """Install what is missing, then start it. Safe to run again."""
    if not _fix_lock.acquire(blocking=False):
        return
    with _task_lock:
        _task.update(running=True, done=False, ok=None, log=[], step="Starting")
    try:
        # 1. FastBrain
        if fastbrain_root() is None:
            _step("Downloading FastBrain")
            if shutil.which("git") is None:
                run(["apt-get", "install", "-y", "git"], 300)
            rc, out = run(["git", "clone", "--depth", "1", FASTBRAIN_REPO, str(HOME / "FastBrain")], 300)
            if rc != 0:
                _step("FastBrain download failed: " + out[-160:])
        root = fastbrain_root()
        if root is not None and not (FB_HOME / "fastbrain").is_file():
            _step("Installing FastBrain")
            rc, out = run(["bash", str(root / "scripts" / "install-termux.sh")], 300)
            if rc != 0:
                _step("FastBrain install failed: " + out[-200:])
        # 2. MiniLM
        for name, rel in MINILM_FILES.items():
            dest = FB_HOME / "minilm" / name
            if not (dest.is_file() and dest.stat().st_size > 1000):
                _step(f"Downloading MiniLM ({name})")
                _download(MINILM_BASE + rel, dest, 300)
        fp = py313()
        if fp and not any(py_ok(p, "import numpy, onnxruntime") for p in pythons("python3.14", "python3.13", "python3")):
            _step("Installing numpy + onnxruntime")
            _pip(["numpy", "onnxruntime"], fp)
        if minilm_files_ok() and not minilm_enabled():
            _step("Switching FastBrain to MiniLM")
            envf = FB_HOME / "fastbrain.env"
            envf.parent.mkdir(parents=True, exist_ok=True)
            envf.write_text(env_text().rstrip("\n") + "\n" + ENV_BLOCK)
            envf.chmod(0o600)
        # direct Hindsight adapter, if the install didn't configure it
        hp = hermes_py()
        if hp and "FASTBRAIN_HINDSIGHT_CMD" not in env_text() and (FB_HOME / "bin" / "hindsight-direct.py").is_file() and py_ok(hp, "import hindsight_lite"):
            _step("Linking FastBrain to Hindsight Lite")
            envf = FB_HOME / "fastbrain.env"
            envf.write_text(env_text().rstrip("\n") + f'\nFASTBRAIN_HINDSIGHT_CMD="{hp} {FB_HOME / "bin" / "hindsight-direct.py"}"\n')
        # 3. Piper + voices
        if piper_py() is None:
            _step("Installing Piper")
            _pip(["piper-tts", "numpy"])
        pp = piper_py()
        if pp:
            have = voices_found()
            for v in VOICES_WANTED:
                if v not in have:
                    _step(f"Downloading voice {v}")
                    run([pp, "-m", "piper.download_voices", "--download-dir", str(VOICE_DIR), v], 900)
        # 4. start everything and check it
        _step("Starting services")
        for _ in range(3):
            ensure()
            time.sleep(2)
        import_hindsight_aliases()
        d = diagnose()
        with _task_lock:
            _task.update(ok=d["ok"], step="Done" if d["ok"] else "Some things still need attention")
    except Exception as e:  # noqa: BLE001
        _step(f"Error: {type(e).__name__}: {e}")
        with _task_lock:
            _task["ok"] = False
    finally:
        with _task_lock:
            _task.update(running=False, done=True)
        _fix_lock.release()


# ------------------------------------------------------------------ contact aliases (FastBrain hot cache, fed by Hindsight)
def _db_path() -> Path:
    return Path(os.environ.get("FASTBRAIN_DB", str(FB_HOME / "fastbrain.db")))


def read_aliases() -> dict[str, str]:
    out: dict[str, str] = {}
    p = _db_path()
    if not p.is_file():
        return out
    try:
        con = sqlite3.connect(f"file:{p}?mode=ro", uri=True, timeout=2)
        for subject, value in con.execute("SELECT subject,value FROM memory_hot WHERE type='alias' AND value!='null'"):
            try:
                v = json.loads(value)
            except ValueError:
                v = value
            if isinstance(v, str) and subject and v.strip():
                out[subject.strip()] = v.strip()
        con.close()
    except Exception as e:  # noqa: BLE001
        _log(f"read_aliases: {e}")
    return out


_NAME = r"[A-Za-z\u00C0-\u024F\u0900-\u097F][\w .'\-]{0,38}"
_ALIAS_RES = [
    re.compile(rf"(?P<canon>{_NAME}?)\s+(?:is\s+)?(?:also\s+known\s+as|a\.?k\.?a\.?|nicknamed|called)\s+[\"'\u201c]?(?P<alias>{_NAME}?)[\"'\u201d]?(?=[.,;:!?]|\s+(?:in|on|for|when|and)\b|$)", re.I),
    re.compile(rf"[\"'\u201c](?P<alias>{_NAME}?)[\"'\u201d]\s+(?:means|=|refers\s+to|is)\s+(?:my\s+)?(?:contact\s+)?(?P<canon>{_NAME}?)(?=[.,;:!?(]|$)", re.I),
]


def learn_alias(alias: str, canonical: str) -> bool:
    alias, canonical = " ".join(alias.split()), " ".join(canonical.split())
    if not alias or not canonical or alias.lower() == canonical.lower() or len(alias) > 60 or len(canonical) > 80:
        return False
    ok = False
    fb = FB_HOME / "fastbrain"
    if fb.is_file():
        ok = run([str(fb), "alias-set", canonical, alias], 25)[0] == 0
    hp = hermes_py()
    if hp:  # durable copy in Hindsight Lite, so Hermes itself knows it too
        code = ("import sys,json;from hindsight_lite.hermes import HermesMemoryBridge as B;b=B();"
                "b.initialize(session_id='hm-alias',hermes_home=sys.argv[1]);"
                "b.tool('hmem_remember',{'content':sys.argv[2]});b.shutdown()")
        text = f'Contact alias: "{alias}" means my contact {canonical}. {canonical} is also known as {alias}.'
        threading.Thread(target=run, args=([hp, "-c", code, str(HOME / ".hermes"), text], 90), daemon=True).start()
    return ok


def import_hindsight_aliases() -> int:
    """Pull aliases out of Hindsight Lite's memories into FastBrain's hot cache. Best effort; the app only trusts an alias
    whose target is a real contact on the phone, so a wrong guess here can't call a stranger."""
    hp = hermes_py()
    if not hp or not (FB_HOME / "fastbrain").is_file():
        return 0
    code = ("import sys,json;from hindsight_lite.hermes import HermesMemoryBridge as B;b=B();"
            "b.initialize(session_id='hm-alias-import',hermes_home=sys.argv[1]);"
            "r=[b.tool('hmem_recall',{'query':q,'limit':50}) for q in ('contact nickname alias also known as','what I call people in my contacts')];"
            "print(json.dumps(r));b.shutdown()")
    rc, out = run([hp, "-c", code, str(HOME / ".hermes")], 90)
    if rc != 0:
        return 0
    texts: list[str] = []

    def walk(x) -> None:
        if isinstance(x, str):
            try:
                y = json.loads(x)
                if not isinstance(y, str):
                    return walk(y)
            except ValueError:
                texts.append(x)
        elif isinstance(x, dict):
            for k in ("content", "text", "fact", "memory", "value"):
                if isinstance(x.get(k), str):
                    texts.append(x[k])
            for v in x.values():
                if isinstance(v, (dict, list)):
                    walk(v)
        elif isinstance(x, list):
            for v in x:
                walk(v)

    try:
        walk(json.loads(out.splitlines()[-1]))
    except Exception:  # noqa: BLE001
        return 0
    known = {k.lower() for k in read_aliases()}
    n = 0
    for t in texts:
        for rx in _ALIAS_RES:
            for m in rx.finditer(t):
                a, c = m.group("alias").strip(" .'\""), m.group("canon").strip(" .'\"")
                if a and c and a.lower() != c.lower() and a.lower() not in known and len(a.split()) <= 3:
                    if run([str(FB_HOME / "fastbrain"), "alias-set", c, a], 25)[0] == 0:
                        known.add(a.lower())
                        n += 1
        if n >= 100:
            break
    return n


# ------------------------------------------------------------------ pets (fetched here, so the phone needs no CDN access)
_manifest: dict = {"at": 0.0, "pets": []}


def _fetch(url: str, max_bytes: int, modes=(0, 1, 2)) -> bytes:
    last: Exception = RuntimeError("no attempt")
    for mode in modes:
        try:
            h = {"User-Agent": UA, "Accept": "*/*", "Referer": "https://agentpets.dev/"} if mode == 0 else ({"Referer": "https://agentpets.dev/"} if mode == 2 else {})
            with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=25) as r:
                data = r.read(max_bytes + 1)
            if len(data) > max_bytes:
                raise RuntimeError("too large")
            return data
        except Exception as e:  # noqa: BLE001
            last = e
    raise last


def pets() -> list[dict]:
    if time.time() - _manifest["at"] < 6 * 3600 and _manifest["pets"]:
        return _manifest["pets"]
    for url in MANIFESTS:
        try:
            m = json.loads(_fetch(url, 20_000_000))
            out = []
            for p in m.get("pets", []):
                slug, u = str(p.get("slug", "")), str(p.get("spritesheetUrl", ""))
                if re.fullmatch(r"[a-z0-9][a-z0-9-]{0,59}", slug) and u.startswith("https://"):
                    out.append({"slug": slug, "name": str(p.get("displayName", slug)), "url": u})
            if out:
                _manifest.update(at=time.time(), pets=out)
                return out
        except Exception as e:  # noqa: BLE001
            _log(f"manifest {url}: {e}")
    return _manifest["pets"]


def _is_image(b: bytes) -> bool:
    return len(b) > 2000 and (b[:8] == b"\x89PNG\r\n\x1a\n" or (b[:4] == b"RIFF" and b[8:12] == b"WEBP"))


def pet_bytes(slug: str) -> tuple[bytes, str]:
    cache = STATE_DIR / "pets" / f"{slug}.img"
    entry = next((p for p in pets() if p["slug"] == slug), None)
    if cache.is_file() and _is_image(cache.read_bytes()):
        return cache.read_bytes(), entry["name"] if entry else slug
    if not entry:
        raise KeyError("unknown pet")
    data = _fetch(entry["url"], 8_000_000)
    if not _is_image(data):
        raise RuntimeError("not an image")
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_bytes(data)
    return data, entry["name"]


def pet_auto() -> tuple[bytes, str, str]:
    """The first pet that downloads: nobody has to choose or set one up."""
    tried = 0
    for p in pets():
        if tried >= 12:
            break
        tried += 1
        try:
            data, name = pet_bytes(p["slug"])
            return data, p["slug"], name
        except Exception as e:  # noqa: BLE001
            _log(f"pet {p['slug']}: {e}")
    raise RuntimeError("no pet could be downloaded")


# ------------------------------------------------------------------ control server
class Handler(BaseHTTPRequestHandler):
    server_version = "HermesMobileServices/1"
    timeout = 30

    def log_message(self, *a) -> None:  # noqa: D401
        return

    def _send(self, code: int, body: bytes, ctype: str = "application/json; charset=utf-8", headers: dict | None = None) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _json(self, obj, code: int = 200) -> None:
        self._send(code, json.dumps(obj, ensure_ascii=False).encode())

    def _authed(self) -> bool:
        k = read_key()
        return bool(k) and hmac.compare_digest(self.headers.get("X-Hermes-Mobile-Key", ""), k)

    def _form(self) -> dict:
        n = min(int(self.headers.get("Content-Length") or 0), 20_000)
        return {k: v[0] for k, v in urllib.parse.parse_qs(self.rfile.read(n).decode("utf-8", "replace")).items()}

    def do_GET(self) -> None:  # noqa: N802
        if not self._authed():
            return self._json({"error": "key"}, 403)
        u = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        try:
            if u.path == "/health":
                return self._json({"ok": True, "v": 1})
            if u.path == "/status":
                return self._json(diagnose())
            if u.path == "/aliases":
                return self._json({"aliases": read_aliases()})
            if u.path == "/pet/catalog":
                qq, off, lim = q.get("q", "").lower(), int(q.get("offset", 0)), min(int(q.get("limit", 30)), 60)
                rows = [p for p in pets() if not qq or qq in p["slug"] or qq in p["name"].lower()]
                return self._json(rows[off:off + lim])
            if u.path == "/pet/file":
                data, name = pet_bytes(q.get("slug", ""))
                return self._send(200, data, "application/octet-stream", {"X-Pet-Name": urllib.parse.quote(name)})
            if u.path == "/pet/auto":
                data, slug, name = pet_auto()
                return self._send(200, data, "application/octet-stream", {"X-Pet-Slug": slug, "X-Pet-Name": urllib.parse.quote(name)})
        except KeyError:
            return self._json({"error": "unknown pet"}, 404)
        except Exception as e:  # noqa: BLE001
            return self._json({"error": f"{type(e).__name__}: {e}"}, 502)
        self._json({"error": "not found"}, 404)

    def do_POST(self) -> None:  # noqa: N802
        if not self._authed():
            return self._json({"error": "key"}, 403)
        path = urllib.parse.urlparse(self.path).path
        if path == "/fix":
            if not _task["running"]:
                threading.Thread(target=fix_all, daemon=True, name="hm-fix").start()
            return self._json({"started": True})
        if path == "/ensure":
            return self._json({"started": ensure()})
        if path == "/alias":
            f = self._form()
            return self._json({"ok": learn_alias(f.get("alias", ""), f.get("canonical", ""))})
        self._json({"error": "not found"}, 404)


def _start_control() -> None:
    try:
        srv = ThreadingHTTPServer(("127.0.0.1", CONTROL_PORT), Handler)
    except OSError as e:
        _log(f"control port: {e}")
        return
    t = threading.Thread(target=srv.serve_forever, daemon=True, name="hm-control")
    t.start()
    _children_threads["control"] = t


_started = False


def start() -> None:
    """Called by the Hermes plugin: supervise in the background of Hermes' own process."""
    global _started
    if _started:
        return
    _started = True
    threading.Thread(target=_supervise_loop, daemon=True, name="hm-supervisor").start()


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "serve"
    if cmd == "status":
        print(json.dumps(diagnose(), indent=2, ensure_ascii=False))
    elif cmd == "fix":
        fix_all()
        print(json.dumps(diagnose(), indent=2, ensure_ascii=False))
    else:  # serve: the long-lived foreground process (used when Hermes itself isn't running)
        start()
        idle = 0
        while True:
            time.sleep(10)
            idle = idle + 1 if _lock_fd is None else 0
            if idle >= 4:  # another Hermes process already supervises: nothing to do here, don't hold a session open
                sys.exit(0)
