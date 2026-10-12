"""Always-on bots: keeps Hermes's messaging gateway running on the phone for the bots switched on.

Hermes runs ONE host gateway per machine (started from the default profile) that serves every
profile's channels; a profile is taken out of it by "parking" it (a `gateway.parked` marker, what
`hermes -p <name> gateway stop` writes). The phone has no service manager (proot Debian, no
systemd), so `hermes gateway start` refuses there. Instead the app records which bots should be
on in ~/.hermes/mobile/bots.json and this keeper, started by the Termux supervisor
(`hermes-services`), reconciles every few seconds:

- bots switched off are parked, bots switched on are unparked (the host rescans within 30 s);
- the host gateway (`hermes gateway run`) runs while any bot is on, is restarted if it dies
  (with backoff), and is stopped when every bot is off. The main profile is always served while
  the host runs — Hermes can't park it.

    python3 bots.py keep      # the long-running keeper loop

The dashboard plugin API imports this file for the same state helpers.
"""

import fcntl
import json
import os
import shutil
import signal
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path

ROOT = Path(os.environ.get("HERMES_MOBILE_ROOT", "/root/.hermes"))
STATE = ROOT / "mobile" / "bots.json"
HEARTBEAT = ROOT / "mobile" / "bots.heartbeat"
LOG = ROOT / "logs" / "mobile-gateway.log"
TICK = 4
BACKOFF_STARTS, BACKOFF_WINDOW, BACKOFF_WAIT = 3, 300, 300


def home(name: str) -> Path:
    return ROOT if name == "default" else ROOT / "profiles" / name


def valid(name: str) -> bool:
    return bool(name) and "/" not in name and not name.startswith(".") and home(name).is_dir()


def named_profiles() -> list:
    try:
        return sorted(p.name for p in (ROOT / "profiles").iterdir() if (p / "config.yaml").exists())
    except OSError:
        return []


@contextmanager
def _locked():
    STATE.parent.mkdir(parents=True, exist_ok=True)
    with open(STATE.with_suffix(".lock"), "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def _read() -> dict:
    try:
        data = json.loads(STATE.read_text())
    except (OSError, ValueError):
        data = {}
    return {"keep": list(data.get("keep") or []), "restart": bool(data.get("restart"))}


def _write(data: dict) -> None:
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=1))
    tmp.replace(STATE)


def load() -> dict:
    with _locked():
        return _read()


def set_keep(name: str, keep: bool, restart: bool = False) -> dict:
    """Switch one bot on/off. Any bot on needs the host, so "default" joins the list; switching
    the main profile off switches everything off. restart=True asks the keeper to bounce the host
    (needed after the main profile's channels change; named profiles are hot-reloaded by Hermes)."""
    with _locked():
        data = _read()
        keepers = [n for n in data["keep"] if n != name]
        if keep:
            keepers.append(name)
            if "default" not in keepers:
                keepers.insert(0, "default")
        elif name == "default":
            keepers = []
        data["keep"] = keepers
        data["restart"] = data["restart"] or restart
        _write(data)
        return data


def pid_of(name: str):
    """PID of the live gateway whose record lives in this profile's home (the host: "default")."""
    try:
        rec = json.loads((home(name) / "gateway.pid").read_text())
        pid = int(rec["pid"] if isinstance(rec, dict) else rec)
    except (OSError, ValueError, KeyError, TypeError):
        return None
    try:
        cmd = Path(f"/proc/{pid}/cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
    except OSError:
        return None
    return pid if "gateway" in cmd else None


def parked(name: str) -> bool:
    return name != "default" and (home(name) / "gateway.parked").exists()


def keeper_alive() -> bool:
    try:
        return time.time() - HEARTBEAT.stat().st_mtime < TICK * 5
    except OSError:
        return False


def log_tail(lines: int = 80) -> str:
    try:
        with open(LOG, "rb") as f:
            f.seek(0, 2)
            f.seek(max(0, f.tell() - 16384))
            return b"\n".join(f.read().splitlines()[-lines:]).decode(errors="replace")
    except OSError:
        return ""


def _platform_states() -> dict:
    """{profile: {platform: state}} from the host's runtime status file, when it has one."""
    try:
        st = json.loads((ROOT / "gateway_state.json").read_text())
    except (OSError, ValueError):
        return {}
    out = {"default": {k: (v or {}).get("state") for k, v in (st.get("platforms") or {}).items()}}
    for p in st.get("served_profiles") or []:
        if isinstance(p, dict) and p.get("name"):
            out[p["name"]] = {k: (v or {}).get("state") for k, v in (p.get("platforms") or {}).items()}
    return out


def status() -> dict:
    data = load()
    host = pid_of("default") is not None
    names = ["default", *named_profiles()]
    return {
        "keep": data["keep"],
        "host_running": host,
        "running": {n: host and not parked(n) for n in names},
        "parked": {n: parked(n) for n in names},
        "platforms": _platform_states() if host else {},
        "keeper_alive": keeper_alive(),
    }


# ── keeper ──────────────────────────────────────────────────


def _say(msg: str) -> None:
    print(time.strftime("%Y-%m-%d %H:%M:%S"), msg, flush=True)


def _stop_host() -> None:
    pid = pid_of("default")
    if not pid:
        return
    _say(f"stopping host gateway (pid {pid})")
    try:
        os.kill(pid, signal.SIGTERM)
        for _ in range(80):
            time.sleep(0.25)
            if pid_of("default") != pid:
                return
        os.kill(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def _start_host(hermes: str):
    LOG.parent.mkdir(parents=True, exist_ok=True)
    out = open(LOG, "ab")
    out.write(f"\n=== {time.strftime('%Y-%m-%d %H:%M:%S')} starting host gateway ===\n".encode())
    out.flush()
    proc = subprocess.Popen([hermes, "gateway", "run", "--external-supervisor"], stdout=out,
                            stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                            start_new_session=True, cwd=str(ROOT))
    out.close()
    _say(f"started host gateway (pid {proc.pid})")
    return proc


def _sync_parking(keep: list) -> None:
    for name in named_profiles():
        marker = home(name) / "gateway.parked"
        want = name not in keep
        if want and not marker.exists():
            marker.touch()
            _say(f"parked {name}")
        elif not want and marker.exists():
            marker.unlink()
            _say(f"unparked {name}")


def keep() -> None:
    hermes = shutil.which("hermes") or "/usr/local/bin/hermes"
    child = None
    starts: list = []
    was_on = False  # only stop a host this keeper was asked to run, never one started by hand
    _say(f"keeper up ({hermes})")
    while True:
        HEARTBEAT.parent.mkdir(parents=True, exist_ok=True)
        HEARTBEAT.touch()
        if child is not None and child.poll() is not None:
            child = None  # reaped
        with _locked():
            data = _read()
            gone = [n for n in data["keep"] if not valid(n)]
            restart = data["restart"]
            if gone or restart:
                data["keep"] = [n for n in data["keep"] if n not in gone]
                data["restart"] = False
                _write(data)
        try:
            if data["keep"]:
                _sync_parking(data["keep"])
                if restart:
                    _stop_host()
                if not pid_of("default") and (child is None or child.poll() is not None):
                    now = time.time()
                    starts = [t for t in starts if now - t < BACKOFF_WINDOW]
                    if len(starts) < BACKOFF_STARTS or now - starts[-1] >= BACKOFF_WAIT:
                        starts.append(now)
                        child = _start_host(hermes)
            elif was_on and pid_of("default"):
                _stop_host()
                child = None
            was_on = bool(data["keep"])
        except Exception as exc:  # keep the loop alive whatever happens
            _say(f"tick failed: {exc}")
        time.sleep(TICK)


def _keeper_pids() -> list:
    """PIDs of running `bots.py keep` loops (exact argv match), never this process."""
    out = []
    for d in Path("/proc").iterdir():
        if not d.name.isdigit() or int(d.name) == os.getpid():
            continue
        try:
            argv = [a for a in (d / "cmdline").read_bytes().split(b"\0") if a]
        except OSError:
            continue
        if len(argv) >= 2 and argv[-2].endswith(b"bots.py") and argv[-1] == b"keep":
            out.append(int(d.name))
    return out


def off() -> None:
    """Battery (the app is the only front end): switch every bot off, stop the keeper loop and the host
    gateway it started. The supervisor no longer runs the keeper; this cleans up one left from before."""
    with _locked():
        data = _read()
        data["keep"], data["restart"] = [], False
        _write(data)
    for pid in _keeper_pids():
        _say(f"stopping bot keeper (pid {pid})")
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    _stop_host()


if __name__ == "__main__":
    if sys.argv[1:] == ["keep"]:
        keep()
    elif sys.argv[1:] == ["off"]:
        off()
    else:
        print(json.dumps(status(), indent=1))
