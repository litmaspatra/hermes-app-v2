#!/usr/bin/env python3
"""P3 memory benchmark: Hindsight Lite vs Mnemosyne. One provider per run (each needs its own Python).

  python p3_memory_bench.py run --provider hindsight --hermes-home /tmp/bench-hl --out hl.json --install-mb 1
  python p3_memory_bench.py run --provider mnemosyne --data-dir /tmp/bench-mn --out mn.json --install-mb 40
  python p3_memory_bench.py compare hl.json mn.json

Uses scratch dirs only. Never touches ~/.hermes or the live memory.provider.
"""
import argparse
import json
import os
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

COLORS = "red blue green amber violet teal coral ivory".split()
NOUNS = "bicycle lantern backpack camera guitar kettle drone helmet".split()
CITIES = "Jaipur Tonk Ajmer Kota Alwar Sikar".split()
NAMES = "Asha Bilal Chitra Dev Esha Farid Gita Hari Isha Jai Kiran Lata".split()
TURNS = [
    "I moved my desk to the back room last week.",
    "My sister Gita prefers tea over coffee.",
    "I switched to a prepaid phone plan this month.",
    "I like lo-fi music while I code.",
    "My brother Dev works at a bank in Kota.",
    "I stopped using the old laptop and now use the tablet.",
]
LOWER_IS_BETTER = ("recall_p50_ms", "rss_delta_mb", "install_mb", "llm_calls_per_turn")


def corpus(n):
    if n > 4608:
        sys.exit("--facts max is 4608")
    rows = []
    for i in range(n):
        c, o = COLORS[i % 8], NOUNS[(i // 8) % 8]
        city, name = CITIES[(i // 64) % 6], NAMES[(i // 384) % 12]
        key = f"{c} {o} in {city} belongs to {name}"
        rows.append({"text": f"The {key}.", "query": f"who owns the {c} {o} in {city}", "key": key})
    return rows


def rss_mb():
    with open("/proc/self/status") as f:
        for line in f:
            if line.startswith("VmRSS:"):
                return round(int(line.split()[1]) / 1024, 1)
    return 0.0


def texts(res):
    if isinstance(res, dict):
        res = res.get("results") or res.get("memories") or [res]
    if not isinstance(res, (list, tuple)):
        res = [res]
    return [x if isinstance(x, str) else json.dumps(x, default=str) for x in res]


class Counter(BaseHTTPRequestHandler):
    """Forwards POSTs to the real LLM endpoint and counts them."""
    upstream = ""
    calls = 0

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        Counter.calls += 1
        hdrs = {k: v for k, v in self.headers.items() if k.lower() not in ("host", "content-length")}
        req = urllib.request.Request(Counter.upstream + self.path, data=body, headers=hdrs, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=90) as r:
                code, data = r.status, r.read()
        except urllib.error.HTTPError as e:
            code, data = e.code, e.read()
        except Exception:
            code, data = 502, b"{}"
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


def llm_calls_per_turn(a):
    """Hindsight Lite only: real sync_turn() calls through a counting proxy. Spends real LLM requests."""
    from hindsight_lite.hermes import HermesMemoryBridge

    root = a.llm_upstream.rstrip("/")
    Counter.upstream = root[:-3] if root.endswith("/v1") else root
    srv = HTTPServer(("127.0.0.1", 0), Counter)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    home = a.hermes_home.rstrip("/") + "-llm"
    cfg_dir = os.path.join(home, "hindsight-lite")
    os.makedirs(cfg_dir, exist_ok=True)
    cfg = {"llm_base_url": f"http://127.0.0.1:{srv.server_port}/v1", "llm_model": a.llm_model, "llm_api_key_env": a.llm_key_env}
    with open(os.path.join(cfg_dir, "config.json"), "w") as f:
        json.dump(cfg, f)
    b = HermesMemoryBridge()
    b.initialize(hermes_home=home)
    Counter.calls = 0
    turns = TURNS[: a.llm_turns]
    for t in turns:
        b.sync_turn(t, "Noted.")
    end = time.time() + 180
    while b._queue.unfinished_tasks and time.time() < end:
        time.sleep(0.5)
    b.shutdown()
    srv.shutdown()
    return round(Counter.calls / len(turns), 2)


def setup_hindsight(a):
    from hindsight_lite.hermes import HermesMemoryBridge

    b = HermesMemoryBridge()
    b.initialize(hermes_home=a.hermes_home)
    return (lambda t: b.tool("hmem_remember", {"content": t}),
            lambda q: [h.content for h in b.retrieval.recall(q, limit=5)],
            b.shutdown)


def setup_mnemosyne(a):
    os.environ["MNEMOSYNE_DATA_DIR"] = a.data_dir
    from mnemosyne import recall, remember

    return (lambda t: remember(t), lambda q: texts(recall(q))[:5], lambda: None)


def run(a):
    base = rss_mb()
    store, recall, close = (setup_hindsight if a.provider == "hindsight" else setup_mnemosyne)(a)
    facts = corpus(a.facts)
    t0 = time.perf_counter()
    for f in facts:
        store(f["text"])
    seed_ms = (time.perf_counter() - t0) * 1000 / len(facts)
    time.sleep(a.idle)
    ram = rss_mb()
    step = max(1, len(facts) // a.queries)
    qs = facts[::step][: a.queries]
    for f in qs[:3]:
        recall(f["query"])  # warm-up, not timed
    lat, hits = [], 0
    for f in qs:
        t = time.perf_counter()
        res = recall(f["query"])
        lat.append((time.perf_counter() - t) * 1000)
        hits += any(f["key"] in r for r in res[:5])
    lat.sort()
    out = {
        "provider": a.provider, "facts": len(facts), "queries": len(qs),
        "install_mb": a.install_mb, "rss_mb": ram, "rss_delta_mb": round(ram - base, 1),
        "seed_ms_per_fact": round(seed_ms, 1),
        "recall_p50_ms": round(statistics.median(lat), 1),
        "recall_p95_ms": round(lat[int(0.95 * (len(lat) - 1))], 1),
        "hit_at_5": round(hits / len(qs), 3),
        "llm_calls_per_turn": None,
    }
    close()
    if a.provider == "hindsight" and a.llm_turns and a.llm_model:
        out["llm_calls_per_turn"] = llm_calls_per_turn(a)
    with open(a.out, "w") as f:
        json.dump(out, f, indent=1)
    print(json.dumps(out, indent=1))


def verdict(hl, mn):
    if mn["hit_at_5"] < hl["hit_at_5"]:
        return "KEEP hindsight-lite: Mnemosyne recall quality is lower"
    better = worse = 0
    for k in LOWER_IS_BETTER:
        h, m = hl.get(k), mn.get(k)
        if not h or m is None:
            continue
        if m <= h * 0.7:
            better += 1
        elif m > h * 1.25:
            worse += 1
    if better >= 2 and worse == 0:
        return "SWITCH default to mnemosyne: >=30% better on 2+ metrics, none >25% worse, recall not lower"
    return f"KEEP hindsight-lite: no clear win (better={better}, worse={worse})"


def compare(a):
    rows = {r["provider"]: r for r in (json.load(open(p)) for p in a.files)}
    hl, mn = rows["hindsight"], rows["mnemosyne"]
    keys = ["facts", "install_mb", "rss_delta_mb", "seed_ms_per_fact", "recall_p50_ms", "recall_p95_ms", "hit_at_5", "llm_calls_per_turn"]
    print(f"{'metric':<20}{'hindsight':>12}{'mnemosyne':>12}")
    for k in keys:
        print(f"{k:<20}{str(hl.get(k)):>12}{str(mn.get(k)):>12}")
    print(verdict(hl, mn))


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--provider", choices=["hindsight", "mnemosyne"], required=True)
    r.add_argument("--hermes-home", default="/tmp/bench-hl")
    r.add_argument("--data-dir", default="/tmp/bench-mn")
    r.add_argument("--out", required=True)
    r.add_argument("--facts", type=int, default=500)
    r.add_argument("--queries", type=int, default=50)
    r.add_argument("--idle", type=float, default=10)
    r.add_argument("--install-mb", type=float, default=None)
    r.add_argument("--llm-turns", type=int, default=0)
    r.add_argument("--llm-upstream", default="http://127.0.0.1:20128/v1")
    r.add_argument("--llm-model", default="")
    r.add_argument("--llm-key-env", default="")
    c = sub.add_parser("compare")
    c.add_argument("files", nargs=2)
    a = p.parse_args()
    run(a) if a.cmd == "run" else compare(a)


if __name__ == "__main__":
    main()
