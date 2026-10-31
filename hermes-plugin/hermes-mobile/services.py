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
import signal
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
KOKORO_DIR = STATE_DIR / "kokoro"
KOKORO_BASE = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/"
KOKORO_FILES = {"kokoro-v1.0.int8.onnx": 80_000_000, "voices-v1.0.bin": 20_000_000}  # name -> minimum plausible size

# --- P2a: pinned, resumable, verified Kokoro download ---
KOKORO_PIN = {
    "kokoro-v1.0.int8.onnx": (92_361_271, "6e742170d309016e5891a994e1ce1559c702a2ccd0075e67ef7157974f6406cb"),
    "voices-v1.0.bin":       (28_214_398, "bca610b8308e8d99f32e6fe4197e7ec01679264efed0cac9140fe9c29f1fbf7d"),
}


def _fetch_verified(url, dest, size, sha256, tries=4):
    """Resumable, checksummed download. Network failure keeps .part so the
    next call resumes; a size/hash mismatch deletes it. Raises on final failure."""
    import hashlib, http.client, shutil, time, urllib.request
    from pathlib import Path
    dest = Path(dest)
    part = dest.with_name(dest.name + ".part")
    dest.parent.mkdir(parents=True, exist_ok=True)
    have = part.stat().st_size if part.exists() else 0
    free = shutil.disk_usage(dest.parent).free
    if free < (size - have) + 300_000_000:
        raise RuntimeError("Not enough free space: need about %d MB, have %d MB"
                           % ((size - have + 300_000_000) // 1_000_000, free // 1_000_000))
    last = None
    for attempt in range(tries):
        try:
            have = part.stat().st_size if part.exists() else 0
            if have > size:
                part.unlink()
                have = 0
            if have < size:
                hdr = {"Range": "bytes=%d-" % have} if have else {}
                req = urllib.request.Request(url, headers=hdr)
                with urllib.request.urlopen(req, timeout=60) as r:
                    resumed = bool(have) and getattr(r, "status", 200) == 206
                    with open(part, "ab" if resumed else "wb") as f:
                        shutil.copyfileobj(r, f, 1 << 20)
            h = hashlib.sha256()
            with open(part, "rb") as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            if part.stat().st_size != size or h.hexdigest() != sha256:
                part.unlink()
                raise ValueError("checksum/size mismatch for " + dest.name)
            part.replace(dest)
            return
        except (OSError, http.client.HTTPException, ValueError) as e:
            last = e
            time.sleep(2 ** (attempt + 1))
    raise RuntimeError("Download of %s failed after %d tries: %s" % (dest.name, tries, last))
# --- end P2a ---
_KOKORO_ERR = {"msg": ""}

TTS_SERVER_SRC = r'''#!/usr/bin/env python3
"""Local voice server on 127.0.0.1:8766 (same API as the old Piper server): Kokoro first, Piper voices as a fallback."""
import argparse, hmac, io, os, sys, threading, time, urllib.parse, wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HOME = Path.home()
STATE = Path(os.environ.get("HM_STATE_DIR", str(HOME / ".hermes-mobile")))
KDIR = STATE / "kokoro"
MODEL, VOICES = KDIR / "kokoro-v1.0.int8.onnx", KDIR / "voices-v1.0.bin"
# English first, then Hindi; every one of these is inside voices-v1.0.bin
WANTED = ["af_heart", "af_bella", "af_nicole", "af_sarah", "af_sky", "am_adam", "am_michael", "bf_emma", "bf_isabella", "bm_george", "bm_lewis",
          "hf_alpha", "hf_beta", "hm_omega", "hm_psi"]
DEFAULT = "kokoro-af_heart"
_lock = threading.Lock()
_k = None
_piper = {}


def lang(v: str) -> str:
    return {"a": "en-us", "b": "en-gb", "h": "hi"}.get(v[:1], "en-us")


_kload = threading.Lock()


def kokoro():
    global _k
    if _k is None:
        with _kload:  # one load, even when a request and the background warm-up arrive together
            if _k is None:
                from kokoro_onnx import Kokoro
                try:  # an explicit thread count: measured ~12% faster than the default on this phone (6 and 8 threads are slower)
                    import onnxruntime as ort
                    so = ort.SessionOptions()
                    so.intra_op_num_threads = int(os.environ.get("HM_TTS_THREADS", "4"))
                    sess = ort.InferenceSession(str(MODEL), sess_options=so, providers=["CPUExecutionProvider"])
                    k = Kokoro.from_session(sess, str(VOICES))
                except Exception as e:  # older kokoro-onnx without from_session, or anything unexpected: the default way
                    print("kokoro: default session:", type(e).__name__, e, flush=True)
                    k = Kokoro(str(MODEL), str(VOICES))
                _k = k
    return _k


def kokoro_ready() -> bool:
    return MODEL.is_file() and VOICES.is_file()


def piper_catalog() -> dict:
    out = {}
    for d in (STATE.parent / ".piper-voices", HOME / "piper-voices", HOME / ".hermes" / "cache" / "piper-voices"):
        for p in sorted(d.glob("*.onnx")) if d.is_dir() else []:
            if p.with_name(p.name + ".json").exists():
                out.setdefault(p.stem, p)
    return out


def names() -> list:
    out = []
    if kokoro_ready():
        try:
            have = set(_k.get_voices()) if _k is not None else set(WANTED)  # listing voices must not load the model
            out += ["kokoro-" + v for v in WANTED if v in have]
        except Exception:
            pass
    return out + sorted(piper_catalog())


def to_wav(a, rate: int) -> bytes:
    import numpy as np
    a = np.asarray(a, dtype="float32").reshape(-1)
    idx = np.flatnonzero(np.abs(a) > 0.01)
    if idx.size:  # silence trimmed at both ends: the app adds its own pauses
        a = a[max(0, idx[0] - int(rate * 0.01)): idx[-1] + int(rate * 0.03)]
    pcm = (np.clip(a, -1, 1) * 32767).astype("<i2").tobytes()
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)
    return out.getvalue()


def synth(voice: str, text: str, speed: float) -> bytes:
    speed = max(0.5, min(1.8, speed))
    if voice.startswith("kokoro-") and kokoro_ready():
        v = voice[7:]
        t0 = time.time()
        a, rate = kokoro().create(text, voice=v, speed=speed, lang=lang(v))
        print(f"tts {len(text)} chars: made in {time.time() - t0:.2f}s, plays {len(a) / rate:.2f}s", flush=True)
        return to_wav(a, rate)
    cat = piper_catalog()
    name = voice if voice in cat else (sorted(cat)[0] if cat else "")
    if not name:
        if kokoro_ready():  # unknown or old voice name: speak in the default Kokoro voice instead of failing
            a, rate = kokoro().create(text, voice=DEFAULT[7:], speed=speed, lang="en-us")
            return to_wav(a, rate)
        raise LookupError("no voice")
    import numpy as np
    from piper import PiperVoice, SynthesisConfig
    if name not in _piper:
        _piper[name] = PiperVoice.load(cat[name])
    cfg = SynthesisConfig(length_scale=1.0 / speed, noise_w_scale=0.9)
    parts, rate = [], 22050
    for c in _piper[name].synthesize(text, cfg):
        rate = c.sample_rate
        parts.append(np.asarray(c.audio_float_array, dtype="float32").reshape(-1))
    return to_wav(np.concatenate(parts) if parts else np.zeros(0, dtype="float32"), rate)


def make_handler(key_path: Path):
    class H(BaseHTTPRequestHandler):
        timeout = 20

        def log_message(self, *a):
            return

        def _send(self, code, body, ctype="application/json"):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _ok(self):
            try:
                key = key_path.read_text().strip()
            except OSError:
                key = ""
            return bool(key) and hmac.compare_digest(key.encode(), self.headers.get("X-Hermes-Mobile-Key", "").encode())

        def do_GET(self):
            if self.path not in ("/health", "/voices"):
                return self._send(404, b"{}")
            if not self._ok():
                return self._send(403, b"{}")
            n = names()  # no synthesis lock: status must answer while speech is being made
            if self.path == "/voices":
                return self._send(200, "\n".join(n).encode(), "text/plain")
            self._send(200 if n else 503, b'{"ok":true}' if n else b'{"ok":false,"error":"no_voice_installed"}')

        def do_POST(self):
            if self.path != "/tts":
                return self._send(404, b"{}")
            if not self._ok():
                return self._send(403, b"{}")
            try:
                n = int(self.headers.get("Content-Length", "0") or 0)
                if n <= 0 or n > 20000:
                    return self._send(413, b"{}")
                f = urllib.parse.parse_qs(self.rfile.read(n).decode("utf-8", "replace"))
                text = (f.get("text") or [""])[0].strip()[:600]
                speed = float((f.get("speed") or ["1.0"])[0])
                if not text:
                    return self._send(400, b"{}")
                with _lock:
                    wav = synth((f.get("voice") or [DEFAULT])[0], text, speed)
                self._send(200, wav, "audio/wav")
            except LookupError:
                self._send(503, b'{"error":"no_voice_installed"}')
            except Exception as exc:
                self._send(500, ('{"error":"%s"}' % type(exc).__name__).encode())
    return H


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8766)
    p.add_argument("--key-file", default=os.environ.get("HERMES_MOBILE_KEY_FILE", "/data/data/com.termux/files/home/.hermes-mobile/key"))
    a = p.parse_args()
    try:
        srv = ThreadingHTTPServer(("127.0.0.1", a.port), make_handler(Path(a.key_file)))
    except OSError as e:  # another voice server already holds the port: leave it alone, do not load a second model
        print(f"port {a.port} is taken ({e}); a voice server is already running", flush=True)
        return 0
    srv.daemon_threads = True
    if kokoro_ready():
        def warm() -> None:
            # the first calls on a fresh process run at half speed; do them now, in the background (a request that
            # arrives meanwhile waits for the lock), so the first real sentence isn't the slow one
            for w in ("Ready.", "Hello. This is a short warm up sentence.",
                      "I looked at your calendar for tomorrow. You are free in the morning, and busy after lunch."):
                try:
                    with _lock:
                        synth(DEFAULT, w, 1.0)
                except Exception as e:
                    print("kokoro warm-up failed:", type(e).__name__, e, flush=True)
                    return
        threading.Thread(target=warm, daemon=True).start()
    print(f"voice server on 127.0.0.1:{a.port}, voices: {names()}", flush=True)
    srv.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
'''

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


_spawn_state: dict[str, dict] = {}


def _alive_as(pid: int, marker: str) -> bool:
    try:
        return marker.encode() in Path(f"/proc/{pid}/cmdline").read_bytes()
    except OSError:
        return False


def spawn(name: str, cmd: list[str], log: Path, env: dict | None = None) -> None:
    """Start a long-running child detached from Hermes' terminal (own session), once.
    - Another supervisor process (the plugin, the app's helper, a Doctor fix) may already run it: its pid file says so.
    - A child that dies within a minute is restarted only after 30 s, 60 s … (at most 2 min), and why it died is logged."""
    marker = next((os.path.basename(a) for a in cmd[1:] if not a.startswith("-")), os.path.basename(cmd[0]))
    pf = STATE_DIR / f"{name}.pid"
    try:
        if _alive_as(int(pf.read_text().strip()), marker):
            return
    except (OSError, ValueError):
        pass
    st = _spawn_state.setdefault(name, {"t": 0.0, "fails": 0, "next": 0.0})
    old = _children.get(name)
    if old is not None:
        if old.poll() is None:
            return
        if st["t"]:
            lived = time.time() - st["t"]
            _log(f"{name} exited rc={old.returncode} after {lived:.0f}s")
            st["fails"] = st["fails"] + 1 if lived < 60 else 0
            st["next"] = time.time() + min(120, 30 * 2 ** (st["fails"] - 1)) if st["fails"] else 0.0
            st["t"] = 0.0
    if time.time() < st["next"]:
        return
    log.parent.mkdir(parents=True, exist_ok=True)
    f = open(log, "ab")
    _children[name] = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=f, stderr=subprocess.STDOUT, start_new_session=True,
                                       env={**os.environ, **(env or {})})
    st["t"] = time.time()
    try:
        pf.write_text(str(_children[name].pid))
    except OSError:
        pass


def py_ok(py: str, code: str) -> bool:
    """True if `code` runs in `py`. A probe that only times out (a swapping, overloaded phone) counts as ok: a package
    that is really missing fails at once, and calling a slow import "missing" made the helper reinstall working things."""
    return run([py, "-c", code], 90)[0] in (0, 124)


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


def _piper_py() -> str | None:
    for p in pythons(str(STATE_DIR / "venv" / "bin" / "python"), sys.executable, "python3"):
        if py_ok(p, "import piper"):
            return p
    return None


def hermes_py() -> str | None:
    """Hermes' own Python: the old venv if present, else the standalone interpreter newer installs run from (~/.hermes/tools)."""
    tools = sorted((HOME / ".hermes" / "tools").glob("python-*/bin/python3"), reverse=True)
    for p in pythons(str(HOME / ".hermes" / "hermes-agent" / "venv" / "bin" / "python"), *map(str, tools)):
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


def _kokoro_py() -> str | None:
    for p in pythons(str(STATE_DIR / "venv" / "bin" / "python"), "python3", sys.executable):
        if py_ok(p, "import kokoro_onnx"):
            return p
    return None


def kokoro_py() -> str | None:
    return cached("kokoro_py", _kokoro_py)


def piper_py() -> str | None:
    return cached("piper_py", _piper_py)


def kokoro_files_ok() -> bool:
    return all((KOKORO_DIR / n).is_file() and (KOKORO_DIR / n).stat().st_size >= m for n, m in KOKORO_FILES.items())


def write_tts_server() -> Path:
    p = STATE_DIR / "tts_server.py"
    if not p.is_file() or p.read_text() != TTS_SERVER_SRC:
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        p.write_text(TTS_SERVER_SRC)
    return p


def tts_voices() -> list[str]:
    try:
        req = urllib.request.Request(f"http://127.0.0.1:{TTS_PORT}/voices", headers={"X-Hermes-Mobile-Key": read_key()})
        with urllib.request.urlopen(req, timeout=8) as r:
            return r.read().decode().split()
    except Exception:
        return []


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


def _minilm_answers() -> bool:
    """The MiniLM socket accepts a connection."""
    try:
        c = socket.socket(socket.AF_UNIX)
        c.settimeout(1.0)
        c.connect(str(FB_HOME / "minilm" / "minilm.sock"))
        c.close()
        return True
    except OSError:
        return False


def _minilm_pid() -> int | None:
    """The pid in minilm.pid if that process is alive and is the MiniLM service (never another process that reused the number)."""
    try:
        pid = int((FB_HOME / "minilm" / "minilm.pid").read_text().strip())
    except (OSError, ValueError):
        return None
    try:
        return pid if b"minilm-semantic-service" in Path(f"/proc/{pid}/cmdline").read_bytes() else None
    except OSError:
        try:
            os.kill(pid, 0)  # cmdline not readable here: fall back to "is it alive"
            return pid
        except OSError:
            return None


def minilm_up() -> bool:
    """Up = the service process is alive (the same test start-minilm uses), or its socket answers.
    The socket FILE is deliberately not required: on some phones it is not visible by path although the service
    is listening, and judging by it made the supervisor restart a healthy MiniLM every 20 s."""
    return _minilm_pid() is not None or _minilm_answers()


_minilm_state: dict = {"fails": 0, "next": 0.0, "noted": 0.0}


def _minilm_tend() -> bool:
    """Keep MiniLM running and never touch a healthy one. True if it started it.
    A start waits up to 15 s and counts only if the process is still alive; failures back off (30 s, 60 s … 10 min)."""
    st, now = _minilm_state, time.time()
    if minilm_up():
        st["fails"] = 0
        pid = _minilm_pid()
        if pid is not None and not _minilm_answers() and now - st["noted"] > 3600:
            st["noted"] = now  # evidence for later, once an hour: alive, but the socket cannot be reached by path
            _log(f"minilm: process {pid} is alive; its socket does not answer from here ({(FB_HOME / 'minilm' / 'minilm.sock').exists()=})")
        return False
    if now < st["next"]:
        return False
    rc, out = run(["bash", str(FB_HOME / "bin" / "start-minilm")], 40)
    for _ in range(15):
        time.sleep(1)
        if _minilm_pid() is None and rc != 0:
            break
    if minilm_up():
        st["fails"] = 0
        _note_start("minilm")
    else:
        st["fails"] += 1
        st["next"] = time.time() + min(600, 30 * 2 ** (st["fails"] - 1))
        _note_start("minilm", f"did not stay up (rc={rc}): {out[-200:]}")
    return True


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


def memory_provider() -> str:
    """memory.provider from ~/.hermes/config.yaml, '' if unset. Plain scan, no YAML dependency."""
    import re
    try:
        inside = False
        for line in (HOME / ".hermes" / "config.yaml").read_text().splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            if not line[0].isspace():
                inside = line.split("#")[0].strip() == "memory:"
                continue
            m = re.match(r"\s+provider:\s*['\"]?([\w.-]+)", line) if inside else None
            if m:
                return m.group(1)
    except Exception:
        pass
    return ""


def hindsight_config() -> dict:
    try:
        return json.loads((HOME / ".hermes" / "hindsight-lite" / "config.json").read_text())
    except Exception:  # noqa: BLE001
        return {}


_cache: dict[str, tuple[float, object]] = {}


def cached(key: str, fn, ok_ttl: float = 600.0, bad_ttl: float = 20.0):
    """Remember a slow probe (it spawns a Python process) so the 20 s loop and the doctor don't repeat it."""
    hit = _cache.get(key)
    if hit and time.time() - hit[0] < (ok_ttl if hit[1] else bad_ttl):
        return hit[1]
    v = fn()
    _cache[key] = (time.time(), v)
    return v


_svc: dict[str, dict] = {}

# ── port/PID change tracking ────────────────────────────────────────────────
# Tracks the last-known listener PID for each monitored TCP port.
# On every supervisor tick we compare the current listener to the recorded one;
# any change is written to services.log so unusual port hand-offs (a second
# FastBrain process grabbing 8765, dashboard restart on 9119, etc.) are visible.
# We do NOT kill processes or treat a new listener as evidence of a duplicate —
# we only log the change so the operator can investigate.
_port_pid_last: dict[int, int | None] = {}

MONITORED_PORTS = (FB_PORT, TTS_PORT, CONTROL_PORT, 9119)


def _listener_pid(port: int) -> int | None:
    """Return the PID of the process listening on TCP 127.0.0.1:<port>, or None.
    Uses /proc/net/tcp (IPv4 only; sufficient for loopback). Does not shell out."""
    try:
        # /proc/net/tcp columns: sl local_address rem_address st tx:rx uid inode …
        # local_address is hex "0100007F:hex_port" for 127.0.0.1 (little-endian).
        hex_port = f"{port:04X}"
        target = f"0100007F:{hex_port}"
        listening_inodes: set[int] = set()
        with open("/proc/net/tcp") as f:
            for line in f:
                parts = line.split()
                if len(parts) < 10:
                    continue
                if parts[1] == target and parts[3] == "0A":  # 0A = LISTEN
                    try:
                        listening_inodes.add(int(parts[9]))
                    except ValueError:
                        pass
        if not listening_inodes:
            return None
        # Walk /proc/*/fd to find which process owns an inode in the listen set.
        for pid_str in os.listdir("/proc"):
            if not pid_str.isdigit():
                continue
            try:
                fd_dir = f"/proc/{pid_str}/fd"
                for fd in os.listdir(fd_dir):
                    try:
                        target_link = os.readlink(f"{fd_dir}/{fd}")
                        # Links look like "socket:[1234567]"
                        if target_link.startswith("socket:["):
                            inode = int(target_link[8:-1])
                            if inode in listening_inodes:
                                return int(pid_str)
                    except (OSError, ValueError):
                        pass
            except OSError:
                pass
        return None
    except Exception:  # noqa: BLE001
        return None


def _log_port_changes() -> None:
    """Check monitored ports for listener PID changes and log any differences.
    Called once per supervisor tick — O(n·procs) but fast enough at 20 s intervals."""
    for port in MONITORED_PORTS:
        try:
            pid = _listener_pid(port)
            prev = _port_pid_last.get(port, -1)  # -1 = never checked
            if prev == -1:
                # First check: record baseline without logging (normal startup noise).
                _port_pid_last[port] = pid
                continue
            if pid != prev:
                if pid is None:
                    _log(f"port {port}: listener gone (was pid {prev})")
                elif prev is None:
                    _log(f"port {port}: new listener pid {pid}")
                else:
                    _log(f"port {port}: listener changed pid {prev} → {pid}")
                _port_pid_last[port] = pid
        except Exception:  # noqa: BLE001
            pass


def _note_start(name: str, err: str = "") -> None:
    s = _svc.setdefault(name, {"starts": 0, "last_start": 0.0, "error": ""})
    s["starts"] += 1
    s["last_start"] = time.time()
    s["error"] = err
    _log(f"start {name}" + (f": {err}" if err else ""))


def tts_up() -> bool:
    """The TTS server is genuinely ready: the port accepts connections AND /health returns 200.
    The FastBrain bridge (port 8765) speaks a proprietary binary protocol with no confirmed
    HTTP health path, so port_open() remains correct for that service. The TTS server is
    written by this module (TTS_SERVER_SRC) and is known to expose /health, so we check it.
    Falls back to port_open() if the HTTP check times out, to avoid a false "down" during
    the ~1 s startup window before the HTTP listener is ready."""
    if not port_open(TTS_PORT):
        return False
    code = http_code(TTS_PORT, "/health")
    # code 0 = connection refused or timeout (still starting); treat as port_open truth
    return code == 200 or code == 0


# ------------------------------------------------------------------ FreeLLMAPI (the one LLM endpoint; optional)
# Checked and started only when it is installed on this phone: ~/freellmapi plus ~/start-freellmapi.sh (which clears a stale
# pid file and waits for /livez). Node sometimes dies under load (a libuv assertion) and nothing else restarts it.
FREELLM_PORT = int(os.environ.get("FREELLM_PORT", "3001"))
FREELLM_WAIT = 30  # seconds to wait for /livez after running the start script
_freellm_state: dict = {"fails": 0, "next": 0.0}


def freellm_script() -> Path:
    return HOME / "start-freellmapi.sh"


def freellm_installed() -> bool:
    return (HOME / "freellmapi").is_dir() and freellm_script().is_file()


def freellm_live() -> dict:
    """GET /livez gives {"status": "ok", "version": ..., "uptime_s": ...}; {} when nothing healthy answers."""
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{FREELLM_PORT}/livez", timeout=1.5) as r:
            d = json.loads(r.read(4096).decode("utf-8", "replace"))
        return d if isinstance(d, dict) and d.get("status") == "ok" else {}
    except Exception:  # noqa: BLE001
        return {}


def _freellm_hint() -> str:
    """Why it is down, from its pid file and the end of its log."""
    if _pid_alive(FB_HOME / "freellmapi.pid"):
        return "The process is running but does not answer on /livez (still starting, or stuck)."
    try:
        with open(FB_HOME / "freellmapi.log", "rb") as f:
            f.seek(0, 2)
            f.seek(max(0, f.tell() - 4000))
            tail = f.read().decode("utf-8", "replace")
    except OSError:
        tail = ""
    if "uv__io_poll" in tail:
        return "Node crashed (libuv uv__io_poll assertion, seen under load). Fix restarts it."
    return f"Nothing answers on port {FREELLM_PORT}. Fix runs {freellm_script()}; log: {FB_HOME / 'freellmapi.log'}."


def freellm_check() -> dict | None:
    """The doctor row, or None when FreeLLMAPI is not installed here (then it is not part of the doctor)."""
    if not freellm_installed():
        return None
    fl = freellm_live()
    title = "FreeLLMAPI running" + (f" (v{str(fl['version'])[:20]})" if fl.get("version") else "")
    return {"id": "freellmapi", "title": title, "ok": bool(fl), "detail": "" if fl else _freellm_hint()}


def freellm_start(force: bool = False) -> bool:
    """Runs the start script; True once /livez answers. Not while the recorded process is alive (it may just be slow to
    start), unless forced. When forced, spawn()'s internal backoff is cleared so it actually relaunches."""
    if freellm_live():
        return True
    if not force and _pid_alive(FB_HOME / "freellmapi.pid"):
        return False
    script = freellm_script()
    if not script.is_file():
        return False
    if force:
        # Clear spawn()'s internal restart-backoff so the forced restart actually runs
        _spawn_state.pop("freellmapi", None)
    spawn("freellmapi", ["bash", str(script)], FB_HOME / "freellmapi.start.log")
    for _ in range(FREELLM_WAIT):
        time.sleep(1)
        if freellm_live():
            _note_start("freellmapi")
            return True
    _note_start("freellmapi", f"did not answer /livez within {FREELLM_WAIT} s")
    return False


def _freellm_tend() -> bool:
    """Supervisor step: start it when it is down.
    Uses exponential backoff (60 s → 120 s → 240 s → 300 s max).  After the process has been gone
    for 10 minutes the fail counter resets so it tries again with a clean slate — this handles
    reboots, Termux restarts, and OOM kills without needing the Fix button."""
    st, now = _freellm_state, time.time()
    if freellm_live():
        st["fails"] = 0
        st["down_since"] = 0.0
        return False
    # Record when it first went down so we can reset after a long gap
    if not st.get("down_since"):
        st["down_since"] = now
    elif now - st["down_since"] > 600:  # 10 min — reset and try fresh
        st.update(fails=0, next=0.0, down_since=now)
    if now < st.get("next", 0.0):
        return False
    # Don't start while the old process is still alive (it may be slow) unless it has been stuck for >90 s
    pid_file = FB_HOME / "freellmapi.pid"
    if _pid_alive(pid_file) and now - st.get("down_since", now) < 90:
        return False
    # Exponential backoff: 60, 120, 240, 300 s
    delay = min(60 * (2 ** st.get("fails", 0)), 300)
    st["next"] = now + delay
    if freellm_start():
        st.update(fails=0, down_since=0.0)
        return True
    st["fails"] = st.get("fails", 0) + 1
    return False


def service_states() -> dict:
    """Per-service health: up, starting (started in the last 60 s) or down.
    TTS uses a real HTTP /health check (the server exposes one); bridge uses TCP connect-close
    (proprietary binary protocol, no confirmed health endpoint); router uses its Unix-socket
    HTTP check; MiniLM uses its two-tier socket+pid check."""
    probes = {"router": router_up, "bridge": lambda: port_open(FB_PORT), "tts": tts_up}
    if minilm_enabled():
        probes["minilm"] = minilm_up
    if freellm_installed():
        probes["freellmapi"] = lambda: bool(freellm_live())
    out = {}
    for name, probe in probes.items():
        s = _svc.get(name, {})
        up = bool(probe())
        fresh = time.time() - s.get("last_start", 0.0) < 60
        out[name] = {"state": "up" if up else ("starting" if fresh else "down"), "starts": s.get("starts", 0), "error": "" if up else s.get("error", "")}
    return out


def router_health() -> dict:
    """The router's own /health JSON (version, capabilities); {} when it is down or answers in the old format."""
    s = router_sock()
    if not s.exists():
        return {}
    try:
        c = socket.socket(socket.AF_UNIX)
        c.settimeout(1.5)
        c.connect(str(s))
        c.sendall(b"GET /health HTTP/1.0\r\n\r\n")
        raw = b""
        while len(raw) < 16384:
            part = c.recv(4096)
            if not part:
                break
            raw += part
        c.close()
        return json.loads(raw.partition(b"\r\n\r\n")[2].decode("utf-8", "replace") or "{}")
    except Exception:  # noqa: BLE001
        return {}


def _norm_url(u: str) -> str:
    return u.strip().strip("'\"").rstrip("/").lower().replace("//localhost", "//127.0.0.1")


def key_present(name: str) -> bool:
    """True if `name` has a non-empty value in this process or in ~/.hermes/.env (Hermes loads that file itself). Never returns the value."""
    if os.environ.get(name):
        return True
    try:
        for line in (HOME / ".hermes" / ".env").read_text().splitlines():
            k, sep, v = line.strip().removeprefix("export ").partition("=")
            if sep and k.strip() == name and v.strip().strip("'\""):
                return True
    except OSError:
        pass
    return False


def hermes_llm_url() -> str:
    """model.base_url from Hermes' config.yaml (the one LLM endpoint setting); no YAML library needed."""
    try:
        txt = (HOME / ".hermes" / "config.yaml").read_text()
    except OSError:
        return ""
    blk = re.search(r"^model:[ \t]*\n((?:[ \t]+.*\n?)+)", txt, re.M)
    u = re.search(r"^[ \t]+base_url:[ \t]*(\S+)", blk.group(1), re.M) if blk else None
    return u.group(1) if u else ""


# ------------------------------------------------------------------ keep things running
def ensure() -> list[str]:
    """Start whatever is down (never restarts something that is up). Returns what it did."""
    did: list[str] = []
    if freellm_installed() and _freellm_tend():
        did.append("freellmapi")
    root = fastbrain_root()
    if root is not None and (FB_HOME / "app").is_dir():
        if not router_up():
            rc, out = run(["bash", str(root / "scripts" / "start-fastbrain-router.sh")], 60, env={"FASTBRAIN_SKIP_APP_BRIDGES": "1"})
            did.append("router" + ("" if rc == 0 else f" (failed: {out[-200:]})"))
            _note_start("router", "" if rc == 0 else out[-200:])
        if not port_open(FB_PORT):
            spawn("bridge", [sys.executable, str(root / "scripts" / "fastbrain-router-tcp.py"), "--socket", str(router_sock())], FB_HOME / "router" / "tcp.log")
            did.append("bridge")
            _note_start("bridge")
        if minilm_enabled() and minilm_files_ok() and (FB_HOME / "bin" / "start-minilm").is_file():
            if _minilm_tend():
                did.append("minilm")
    ready = kokoro_files_ok() and kokoro_py() is not None
    if ready and port_open(TTS_PORT) and not any(v.startswith("kokoro-") for v in tts_voices()):
        run(["pkill", "-f", "piper-tts-server.py"], 10)  # the old Piper-only server holds the port: Kokoro replaces it
        time.sleep(1)
    if not port_open(TTS_PORT) and (ready or voices_found()):
        py = kokoro_py() if ready else piper_py()
        if py:
            spawn("tts", [py, str(write_tts_server()), "--port", str(TTS_PORT)], STATE_DIR / "tts.log")
            did.append("voice server")
            _note_start("tts")
    return did


def _supervise_loop() -> None:
    global _last_import
    while True:
        try:
            if _acquire_lock():
                if not port_open(CONTROL_PORT) and "control" not in _children_threads:
                    _start_control()
                ensure()
                _log_port_changes()  # log any listener PID changes for 8765/8766/8767/9119
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
    add("kokoro_pkg", "Kokoro voice engine installed", kokoro_py() is not None, "kokoro-onnx is not installed for any Python here.")
    add("kokoro_files", "Kokoro voice files (about 120 MB)", kokoro_files_ok(), (("Download failed: " + _KOKORO_ERR["msg"] + ". ") if _KOKORO_ERR["msg"] else "The model and voice pack are missing. ") + "Until they are downloaded, speech uses the phone's own voice.")
    kv = [v for v in tts_voices() if v.startswith("kokoro-")]
    up = http_code(TTS_PORT) == 200
    add("piper_up", "Natural voice server (8766)", up and bool(kv) if kokoro_files_ok() else up,
        "The voice server is down." if not up else "An old Piper-only server holds the port; the doctor replaces it.")
    have = {v: None for v in kv}
    need = []
    cfg = hindsight_config()
    hp = hermes_py()
    prov = memory_provider()
    add("memory_provider", f"Memory provider: {prov or 'not set'}", bool(prov),
        "Hermes config.yaml has no memory.provider, so only the built-in file memory is used.", fix=False)
    if prov == "mnemosyne":
        add("mnemosyne", "Mnemosyne present", hp is not None and cached("mnemosyne", lambda: py_ok(hp, "import mnemosyne")), "Mnemosyne is not importable in Hermes' Python.", fix=False)
    elif prov == "hindsight-lite":
        add("hindsight", "Hindsight Lite present", hp is not None and cached("hindsight", lambda: py_ok(hp, "import hindsight_lite")), "Hindsight Lite is not importable in Hermes' Python. Tap Fix to install it.", fix=True)
        add("memory_write", "Hermes can save new memories on its own", bool(cfg.get("llm_model")),
            "hindsight-lite/config.json has no llm_model, so automatic retention is OFF: only an explicit hmem_remember saves. Set llm_base_url + llm_model (any small chat model).", fix=False)
        hl, hu = cfg.get("llm_base_url", ""), hermes_llm_url()
        env_name = cfg.get("llm_api_key_env", "")
        add("llm_endpoint", "One LLM endpoint for Hermes and memory", bool(hu) and _norm_url(hl) == _norm_url(hu),
            f"Hermes uses {hu or '(not set)'} but hindsight-lite/config.json llm_base_url is {hl or '(not set)'}. Make them the same.", fix=False)
        add("llm_key", "LLM key is set for Hermes", bool(env_name) and key_present(env_name),
            "llm_api_key_env is not set in hindsight-lite/config.json." if not env_name else f"{env_name} has no value in ~/.hermes/.env.", fix=False)
    fb = router_health()
    add("fastbrain_api", "FastBrain reports its version", bool(fb.get("version")), "FastBrain is down, or too old to report a version. Update it if the router check above is green.", fix=False)
    fc = freellm_check()
    if fc:
        add(fc["id"], fc["title"], fc["ok"], fc["detail"])
    n = len(read_aliases())
    return {"ok": all(c["ok"] for c in checks if c["id"] not in ("dup_fastbrain",)), "checks": checks, "voices": sorted(have), "aliases": n,
            "services": service_states(), "fastbrain": fb, "task": dict(_task), "time": int(time.time())}


def _step(msg: str) -> None:
    with _task_lock:
        _task["step"] = msg
        _task["log"] = (_task["log"] + [msg])[-40:]
    _log("fix: " + msg)


def _pip(pkgs: list[str], py: str | None = None) -> tuple[int, str]:
    py = py or shutil.which("python3") or sys.executable
    rc, out = run([py, "-m", "pip", "install", *pkgs], 900)  # no --upgrade: never replace an install that works
    if rc != 0 and "externally-managed" in out.lower():
        rc, out = run([py, "-m", "pip", "install", "--break-system-packages", *pkgs], 900)
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
        # Hindsight Lite: install it into Hermes' own Python if missing
        hp = hermes_py()
        if hp and memory_provider() == "hindsight-lite" and not py_ok(hp, "import hindsight_lite"):
            _step("Installing hindsight-lite into Hermes' Python")
            rc, out = _pip(["hindsight-lite"], hp)
            if rc != 0:
                _step("hindsight-lite install failed: " + out[-200:])
            else:
                _cache.pop("hindsight", None)  # bust the cached import probe
        # direct Hindsight adapter, if the install didn't configure it
        hp = hermes_py()
        if hp and "FASTBRAIN_HINDSIGHT_CMD" not in env_text() and (FB_HOME / "bin" / "hindsight-direct.py").is_file() and py_ok(hp, "import hindsight_lite"):
            _step("Linking FastBrain to Hindsight Lite")
            envf = FB_HOME / "fastbrain.env"
            envf.write_text(env_text().rstrip("\n") + f'\nFASTBRAIN_HINDSIGHT_CMD="{hp} {FB_HOME / "bin" / "hindsight-direct.py"}"\n')
        # 3. Kokoro (natural voice) + its voice pack
        if kokoro_py() is None:
            _step("Installing Kokoro (voice engine)")
            _pip(["kokoro-onnx"])
        for _p in KOKORO_DIR.glob("*.part"):
            if time.time() - _p.stat().st_mtime > 86400:
                _p.unlink(missing_ok=True)
        for name, (ksize, ksha) in KOKORO_PIN.items():
            dest = KOKORO_DIR / name
            if dest.is_file() and dest.stat().st_size == ksize:
                continue
            _step(f"Downloading {name} ({ksize // 1_000_000} MB)")
            try:
                _fetch_verified(KOKORO_BASE + name, dest, ksize, ksha)
                _KOKORO_ERR["msg"] = ""
            except Exception as e:
                _step(f"Kokoro download failed: {e}")
                _KOKORO_ERR["msg"] = str(e)[:160]
                break
        write_tts_server()
        # FreeLLMAPI (only when it is installed on this phone): start it, and let the supervisor try afresh
        if freellm_installed():
            _freellm_state.update(fails=0, next=0.0, down_since=0.0)
            if not freellm_live():
                _step("Starting FreeLLMAPI")
                freellm_start(force=True)
        # 4. start everything and check it
        _cache.clear()
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
                return self._json({"ok": True, "v": 2, "services": service_states()})
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
