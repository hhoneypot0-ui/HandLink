# -*- coding: utf-8 -*-
"""PC 端 WebRTC 观看端：经 VPS relay 信令连 A93s 被控端，验证推流全链路
用法: python -X utf8 pc_viewer.py <配对码> [输出文件名]
"""
import sys, asyncio, json, time, hmac, hashlib, base64
import websockets
from aiortc import (RTCPeerConnection, RTCSessionDescription, RTCIceCandidate,
                    RTCConfiguration, RTCIceServer)
from aiortc.contrib.media import MediaRecorder

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

RELAY = "ws://YOUR_RELAY_HOST:8724"
ROOM = sys.argv[1] if len(sys.argv) > 1 else ""
OUT = sys.argv[2] if len(sys.argv) > 2 else "pc_viewer_received.webm"
TURN_HOST = "YOUR_RELAY_HOST"
TURN_SECRET = "REPLACE_WITH_YOUR_TURN_SECRET"

def turn_creds(ttl=3600):
    ts = str(int(time.time()) + ttl)
    user = f"{ts}:pcviewer"
    cred = base64.b64encode(hmac.new(TURN_SECRET.encode(), user.encode(), hashlib.sha1).digest()).decode()
    return user, cred

frames = {"video": 0, "audio": 0}
first = {"video": None, "audio": None}

async def main():
    if not ROOM:
        print("用法: python pc_viewer.py <配对码>"); return
    user, cred = turn_creds()
    pc = RTCPeerConnection(
        configuration=RTCConfiguration(
            iceServers=[
                RTCIceServer(urls="stun:YOUR_RELAY_HOST:3478"),
                RTCIceServer(urls=[f"turn:{TURN_HOST}:3478?transport=udp", f"turn:{TURN_HOST}:3478?transport=tcp"], username=user, credential=cred),
            ]
        )
    )
    recorder = MediaRecorder(OUT)

    @pc.on("track")
    def on_track(track):
        kind = track.kind
        async def pump():
            try:
                while True:
                    frame = await track.recv()
                    frames[kind] += 1
                    if first[kind] is None:
                        first[kind] = time.time()
                        print(f"*** 首帧 {kind}: {getattr(frame,'width','')}x{getattr(frame,'height','')}")
                    if frames[kind] % 60 == 0:
                        print(f"{kind} frames={frames[kind]}")
            except Exception as e:
                print(f"pump end {kind}: {e}")
        asyncio.ensure_future(pump())
        try:
            recorder.addTrack(track)
        except Exception:
            pass

    pending_ice = []
    remote_set = asyncio.Event()

    @pc.on("icecandidate")
    def on_ice(cand):
        if cand:
            asyncio.ensure_future(ws.send(json.dumps({
                "t": "rtc-ice", "mid": cand.sdpMid or "", "mline": cand.sdpMLineIndex or 0,
                "cand": cand.candidate})))

    async with websockets.connect(RELAY, open_timeout=10) as ws:
        await ws.send(json.dumps({"t": "join", "room": ROOM}))
        print(f"join {ROOM} …")
        offer_ready = asyncio.Event()
        offer = {}

        async def recv_loop():
            async for raw in ws:
                try:
                    m = json.loads(raw)
                except Exception:
                    continue
                t = m.get("t")
                if t == "joined":
                    await ws.send(json.dumps({"t": "pair", "code": ROOM, "name": "PCVIEWER"}))
                    print("pair sent，等待被控端点允许…")
                elif t == "note":
                    print("note:", m.get("msg"))
                elif t == "deny":
                    print("!! deny:", m.get("reason"))
                elif t == "accept":
                    print("accepted! 等待 offer…")
                elif t == "rtc-offer":
                    offer["sdp"] = m["sdp"]
                    offer_ready.set()
                elif t == "rtc-ice":
                    cand = RTCIceCandidate(sdp=m["cand"], sdpMLineIndex=m.get("mline", 0), sdpMid=m.get("mid", ""))
                    if remote_set.is_set():
                        await pc.addIceCandidate(cand)
                    else:
                        pending_ice.append(cand)
                elif t == "rtc-answer":
                    pass
        rt = asyncio.ensure_future(recv_loop())

        await asyncio.wait_for(offer_ready.wait(), timeout=120)
        print("offer 收到，创建 answer")
        await pc.setRemoteDescription(RTCSessionDescription(sdp=offer["sdp"], type="offer"))
        remote_set.set()
        for c in pending_ice:
            await pc.addIceCandidate(c)
        ans = await pc.createAnswer()
        await pc.setLocalDescription(ans)
        await ws.send(json.dumps({"t": "rtc-answer", "sdp": pc.localDescription.sdp}))
        print("answer sent")
        try:
            await recorder.start()
        except Exception as e:
            print("recorder start fail:", e)

        t0 = time.time()
        while time.time() - t0 < 90:
            await asyncio.sleep(5)
            st = pc.connectionState
            print(f"[{int(time.time()-t0)}s] ICE={st} video={frames['video']} audio={frames['audio']}")
            if frames["video"] > 300:
                break
        try:
            await recorder.stop()
        except Exception:
            pass
        rt.cancel()
    print(f"\n=== 结果: video={frames['video']} audio={frames['audio']} 保存={OUT} ===")

asyncio.run(main())
