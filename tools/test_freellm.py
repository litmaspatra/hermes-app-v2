#!/usr/bin/env python3
"""Tests for the doctor's FreeLLMAPI check and start (plugin services.py). Stdlib only:
    python3 tools/test_freellm.py
"""
import importlib.util
import json
import os
import socket
import stat
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("services", os.path.join(HERE, "..", "hermes-plugin", "hermes-mobile", "services.py"))
svc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(svc)

fails = 0


def check(name, cond, detail=""):
    global fails
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}" + (f"  ({detail})" if detail and not cond else ""))
    fails += 0 if cond else 1


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


REPLY = {"code": 200, "body": b'{"status":"ok","version":"0.4.1","uptime_s":12}'}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path != "/livez":
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(REPLY["code"])
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(REPLY["body"])


tmp = Path(tempfile.mkdtemp())
svc.HOME = tmp
svc.FB_HOME = tmp / ".fastbrain"
svc.FB_HOME.mkdir()
svc.FREELLM_PORT = free_port()
svc.FREELLM_WAIT = 3

print("not installed")
check("no project, no script: not installed", svc.freellm_installed() is False)
check("no row in the doctor", svc.freellm_check() is None)
(tmp / "freellmapi").mkdir()
check("project without start script: not installed", svc.freellm_installed() is False)
script = tmp / "start-freellmapi.sh"
script.write_text("#!/bin/bash\nexit 0\n")
script.chmod(script.stat().st_mode | stat.S_IEXEC)
check("project + script: installed", svc.freellm_installed() is True)

print("nothing listening")
check("freellm_live is {} when nothing answers", svc.freellm_live() == {})
c = svc.freellm_check()
check("row is red", c is not None and c["ok"] is False and c["id"] == "freellmapi")
check("row says what to do", "Fix" in c["detail"] and str(svc.FREELLM_PORT) in c["detail"], c["detail"])
(svc.FB_HOME / "freellmapi.log").write_text("assertion failed: ... uv__io_poll ...\n")
check("a libuv crash in the log is named", "uv__io_poll" in svc.freellm_check()["detail"])
(svc.FB_HOME / "freellmapi.pid").write_text(str(os.getpid()))
check("a live pid that does not answer: stuck/starting", "does not answer" in svc.freellm_check()["detail"])
(svc.FB_HOME / "freellmapi.pid").unlink()

print("answering")
srv = ThreadingHTTPServer(("127.0.0.1", svc.FREELLM_PORT), H)
threading.Thread(target=srv.serve_forever, daemon=True).start()
fl = svc.freellm_live()
check("livez ok", fl.get("status") == "ok" and fl.get("version") == "0.4.1")
c = svc.freellm_check()
check("row is green and shows the version", c["ok"] is True and "v0.4.1" in c["title"] and c["detail"] == "", c)
REPLY["body"] = b'{"status":"degraded"}'
check("status other than ok is red", svc.freellm_live() == {} and svc.freellm_check()["ok"] is False)
REPLY["body"] = b"not json"
check("garbage is red, not a crash", svc.freellm_live() == {})
REPLY.update(code=401, body=b'{"error":"auth"}')
check("401 is red", svc.freellm_live() == {})
REPLY.update(code=200, body=b'{"status":"ok","version":"0.4.1","uptime_s":12}')
check("already up: start does nothing and says True", svc.freellm_start() is True)
check("tend does nothing while up", svc._freellm_tend() is False)

print("start and supervise")
srv.shutdown()
srv.server_close()
time.sleep(0.2)
marker = tmp / "ran"
# a start script that brings a server up (like the real one: starts node in the background and waits for /livez)
server_py = tmp / "fake_server.py"
server_py.write_text(
    "import sys\nfrom http.server import BaseHTTPRequestHandler, ThreadingHTTPServer\n"
    "class H(BaseHTTPRequestHandler):\n"
    "    def log_message(self, *a): pass\n"
    "    def do_GET(self):\n"
    "        self.send_response(200); self.end_headers(); self.wfile.write(b'{\"status\":\"ok\",\"version\":\"0.4.1\",\"uptime_s\":1}')\n"
    f"ThreadingHTTPServer(('127.0.0.1', {svc.FREELLM_PORT}), H).serve_forever()\n"
)
script.write_text(f"#!/bin/bash\necho x >> {marker}\nnohup {sys.executable} {server_py} >/dev/null 2>&1 &\necho $! > {svc.FB_HOME / 'freellmapi.pid'}\n")
svc._freellm_state.update(fails=0, next=0.0)
check("tend starts it when down", svc._freellm_tend() is True)
check("it answers afterwards", bool(svc.freellm_live()))
check("start was recorded for service_states", svc._svc.get("freellmapi", {}).get("starts") == 1)
svc._freellm_state.update(fails=0, next=0.0)
check("tend leaves a healthy one alone", svc._freellm_tend() is False and marker.read_text().count("x") == 1)
try:
    os.kill(int((svc.FB_HOME / "freellmapi.pid").read_text()), 9)
except Exception:
    pass
time.sleep(0.5)
check("killed: down again", svc.freellm_live() == {})

print("failing start script and backoff")
script.write_text(f"#!/bin/bash\necho x >> {marker}\nexit 1\n")
(svc.FB_HOME / "freellmapi.pid").unlink(missing_ok=True)
svc._children.pop("freellmapi", None)
marker.unlink()
svc._freellm_state.update(fails=0, next=0.0)
svc.FREELLM_WAIT = 1
for _ in range(5):
    svc._freellm_state["next"] = 0.0
    svc._children.pop("freellmapi", None)
    check("a failed start returns False", svc._freellm_tend() is False)
check("5 failures counted", svc._freellm_state["fails"] == 5, svc._freellm_state)
svc._freellm_state["next"] = 0.0
before = marker.read_text().count("x")
svc._freellm_tend()
check("after 5 failures it stops trying", marker.read_text().count("x") == before)
svc._freellm_state.update(fails=0, next=time.time() + 60)
svc._children.pop("freellmapi", None)
svc._freellm_tend()
check("inside the minute it does not try again", marker.read_text().count("x") == before)
(svc.FB_HOME / "freellmapi.pid").write_text(str(os.getpid()))
svc._freellm_state.update(fails=0, next=0.0)
svc._children.pop("freellmapi", None)
svc._freellm_tend()
check("a live recorded pid blocks the automatic start", marker.read_text().count("x") == before and svc._freellm_state["fails"] == 0)
svc._children.pop("freellmapi", None)
svc.freellm_start(force=True)
check("Fix (force) runs the script even then", marker.read_text().count("x") == before + 1)

print("through the doctor")
(svc.FB_HOME / "freellmapi.pid").unlink(missing_ok=True)
svc._children.pop("freellmapi", None)
script.write_text(f"#!/bin/bash\necho x >> {marker}\nnohup {sys.executable} {server_py} >/dev/null 2>&1 &\necho $! > {svc.FB_HOME / 'freellmapi.pid'}\n")
svc.FREELLM_WAIT = 5
svc._freellm_state.update(fails=0, next=0.0)
svc._svc.clear()
d = svc.diagnose()
rows = [c for c in d["checks"] if c["id"] == "freellmapi"]
check("diagnose has the FreeLLMAPI row, red, with a Fix button", len(rows) == 1 and rows[0]["ok"] is False and rows[0]["fix"] is True, rows)
check("a red FreeLLMAPI row makes the doctor red", d["ok"] is False)
check("service_states lists it as down", d["services"].get("freellmapi", {}).get("state") == "down", d["services"])
did = svc.ensure()
check("ensure() starts it", "freellmapi" in did and bool(svc.freellm_live()), did)
d = svc.diagnose()
rows = [c for c in d["checks"] if c["id"] == "freellmapi"]
check("then the row is green", len(rows) == 1 and rows[0]["ok"] is True and rows[0]["fix"] is False, rows)
check("service_states lists it as up", d["services"]["freellmapi"]["state"] == "up")
try:
    os.kill(int((svc.FB_HOME / "freellmapi.pid").read_text()), 9)
except Exception:
    pass

print(f"\n{'ALL PASS' if not fails else str(fails) + ' FAILED'}")
sys.exit(1 if fails else 0)
