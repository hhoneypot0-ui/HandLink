# -*- coding: utf-8 -*-
"""中继协议端到端模拟（纯标准库 WS 客户端）：host + viewer 对打 VPS relay"""
import socket, base64, os, hashlib, json, struct, time, random

URL_HOST, PORT = 'YOUR_RELAY_HOST', 8724
ROOM = 'TEST%05d' % random.randrange(100000)

class WS:
    def __init__(self, host, port):
        self.s = socket.create_connection((host, port), 8)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (f"GET / HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\n"
               f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n")
        self.s.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            buf += self.s.recv(4096)
        assert b"101" in buf.split(b"\r\n")[0], buf.split(b"\r\n")[0]
        self._buf = buf.split(b"\r\n\r\n", 1)[1]
    def _frame(self, opcode, payload):
        mask = os.urandom(4)
        out = bytearray([0x80 | opcode])
        n = len(payload)
        if n < 126: out.append(0x80 | n)
        elif n < 65536: out.append(0x80 | 126); out += struct.pack(">H", n)
        else: out.append(0x80 | 127); out += struct.pack(">Q", n)
        out += mask
        out += bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.s.sendall(bytes(out))
    def send_text(self, s): self._frame(0x1, s.encode())
    def send_bin(self, b): self._frame(0x2, b)
    def recv(self, timeout=2.0):
        """返回 ('text', str) / ('bin', bytes) / None"""
        self.s.settimeout(timeout)
        def need(n):
            while len(self._buf) < n:
                chunk = self.s.recv(4096)
                if not chunk: raise ConnectionError
                self._buf += chunk
        while True:
            try:
                need(2)
            except (socket.timeout, ConnectionError):
                return None
            b0, b1 = self._buf[0], self._buf[1]
            op = b0 & 0x0F; ln = b1 & 0x7F
            hdr = 2
            if ln == 126: need(4); ln = struct.unpack(">H", self._buf[2:4])[0]; hdr = 4
            elif ln == 127: need(10); ln = struct.unpack(">Q", self._buf[2:10])[0]; hdr = 10
            need(hdr + ln)
            data = bytes(self._buf[hdr:hdr + ln]); self._buf = self._buf[hdr + ln:]
            if op == 0x8: return None
            if op == 0x9: self._frame(0xA, data); continue
            if op == 0x1: return ('text', data.decode())
            if op == 0x2: return ('bin', data)
    def close(self):
        try: self._frame(0x8, b""); self.s.close()
        except Exception: pass

def frame(flags, payload):
    return bytes([flags]) + struct.pack(">Q", 12345) + payload

passed = failed = 0
def check(name, ok):
    global passed, failed
    if ok: passed += 1; print(f"  PASS  {name}")
    else:  failed += 1; print(f"  FAIL  {name}")

print(f"房间 {ROOM}")
host = WS(URL_HOST, PORT); host.send_text(json.dumps({"t": "host", "room": ROOM}))
t0 = time.time()
m = host.recv()
check("host 上线收到 hosted", m and m[0] == 'text' and json.loads(m[1]).get("t") == "hosted")

viewer = WS(URL_HOST, PORT); viewer.send_text(json.dumps({"t": "join", "room": ROOM}))
m = viewer.recv()
check("viewer 入房收到 joined", m and json.loads(m[1]).get("t") == "joined")
viewer.send_text(json.dumps({"t": "pair", "code": ROOM, "name": "V1"}))
m = host.recv()
check("host 收到 pair", m and json.loads(m[1]).get("t") == "pair" and json.loads(m[1]).get("name") == "V1")

# host 按 CaptureService 的 accept 时序回话
host.send_text(json.dumps({"t": "accept", "peer": "本机"}))
host.send_text(json.dumps({"t": "size", "w": 880, "h": 1920}))
host.send_bin(frame(0x02 | 0x04, b"AUDCFG"))
host.send_bin(frame(0x01, b"VIDEOKEY"))
got = [viewer.recv() for _ in range(4)]
texts = [json.loads(x[1]) for x in got if x and x[0] == 'text']
bins = [x[1] for x in got if x and x[0] == 'bin']
check("viewer 收到 accept+size", any(t.get("t") == "accept" for t in texts) and any(t.get("t") == "size" for t in texts))
check("viewer 收到音频config(bit2|bit1)", any(len(b) > 9 and b[0] == 0x06 and b[9:] == b"AUDCFG" for b in bins))
check("viewer 收到视频关键帧(bit0)", any(b[0] == 0x01 and b[9:] == b"VIDEOKEY" for b in bins))

# viewer→host 反向（输入文本 + 二进制）
viewer.send_text(json.dumps({"t": "tap", "x": 0.5, "y": 0.5}))
viewer.send_bin(frame(0, b"PING"))
m = host.recv(); m2 = host.recv()
check("host 收到 viewer 文本输入", m and json.loads(m[1]).get("t") == "tap")
check("host 收到 viewer 二进制", m2 and m2[0] == 'bin' and m2[1][9:] == b"PING")

# 错误房间号 → deny
v2 = WS(URL_HOST, PORT); v2.send_text(json.dumps({"t": "join", "room": "NO-SUCH"}))
m = v2.recv()
check("不存在房间返回 deny", m and json.loads(m[1]).get("t") == "deny")
v2.close()

# viewer 离开 → host 收 note
viewer.close()
m = host.recv(3.0)
check("host 收到观众离开 note", m and json.loads(m[1]).get("t") == "note")
host.close()

print(f"\n结果: {passed} PASS / {failed} FAIL  用时 {time.time()-t0:.1f}s")
raise SystemExit(1 if failed else 0)
