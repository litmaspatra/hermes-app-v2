# Runs INSIDE Debian on the phone (fed over stdin by tools/gw). One tui_gateway JSON-RPC call.
import asyncio, base64, json, re, sys, urllib.request

import websockets

method = sys.argv[1]
params = json.loads(base64.b64decode(sys.argv[2]).decode()) if len(sys.argv) > 2 else {}
page = urllib.request.urlopen("http://127.0.0.1:9119/").read().decode()
token = re.search(r'__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"', page).group(1)


async def main():
    async with websockets.connect(f"ws://127.0.0.1:9119/api/ws?token={token}", max_size=None) as ws:
        await ws.send(json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}))
        while True:
            msg = json.loads(await ws.recv())
            if msg.get("id") == 1 and "method" not in msg:
                print(json.dumps(msg.get("result", msg.get("error")), indent=2, ensure_ascii=False))
                return


asyncio.run(main())
