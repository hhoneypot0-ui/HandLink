// 牵手远程 · VPS 中继服务（房间制，纯转发，不存内容）
// 用法: node relay.js [port]   默认 8724
// 协议:
//   被控端连上后发 {"t":"host","room":"<配对码>"}
//   控制端连上后发 {"t":"join","room":"<配对码>"}
//   中继把双方的文本/二进制帧互相转发；join 时若 host 不在则回 deny。
//
// ---- 关于"卡顿"的关键修复 ----
// 原实现收到二进制帧就 peer.send()，不做任何背压判断。
// 视频是持续 9~12Mbps 的流，而 VPS 出口带宽往往只有 1~5Mbps，
// 于是 ws 的内部发送队列无限堆积：
//   · 延迟从几十毫秒一路涨到几秒
//   · 内存持续增长，严重时进程被 OOM 杀掉
//   · 用户感受就是"卡得没法用"
//
// 现在的策略：
//   1) 转发前检查对端缓冲水位（bufferedAmount），超过阈值就丢帧而不是排队
//   2) 丢帧遵循 GOP 边界：丢就丢到下一个关键帧，避免解码器拿到残帧花屏
//   3) 定期打印带宽统计，方便判断 VPS 出口是否成为瓶颈
const { WebSocketServer } = require('ws');

const PORT = parseInt(process.argv[2] || '8724', 10);
const wss = new WebSocketServer({ port: PORT });
const rooms = new Map(); // room -> { host, viewer }

// 缓冲水位阈值（字节）。超过就认为带宽跟不上，开始丢帧。
// 8MB 大约相当于 6~8Mbps 流 1 秒的数据量，足够覆盖正常抖动。
const HIGH_WATER = 8 * 1024 * 1024;
// 回落到这个水位以下才恢复转发，形成滞回，避免在阈值附近反复抖
const LOW_WATER = 3 * 1024 * 1024;

function send(ws, obj) {
  try { ws.send(JSON.stringify(obj)); } catch (e) { }
}

/** 每个连接一个状态，记录是否处于"丢弃模式"和累计统计 */
const stateOf = new WeakMap();
function state(ws) {
  let s = stateOf.get(ws);
  if (!s) {
    s = { dropping: false, sentBytes: 0, droppedFrames: 0, lastStat: Date.now(), bytesSince: 0 };
    stateOf.set(ws, s);
  }
  return s;
}

/** 判断一个视频帧是否以关键帧开头（协议里 flags 的第 0/1 位） */
function isGopStart(raw) {
  if (!raw || raw.length < 1) return false;
  const flags = raw[0];
  return (flags & 0x01) !== 0 || (flags & 0x02) !== 0;
}

function forwardBinary(target, raw) {
  if (!target || target.readyState !== 1) return;

  const st = state(target);
  const buffered = target.bufferedAmount || 0;

  // 滞回：高水位进入丢弃，低水位退出
  if (!st.dropping && buffered > HIGH_WATER) {
    st.dropping = true;
    console.log(`[backpressure] buffered=${(buffered / 1048576).toFixed(1)}MB -> drop until keyframe`);
  } else if (st.dropping && buffered < LOW_WATER) {
    st.dropping = false;
    console.log(`[backpressure] buffered=${(buffered / 1048576).toFixed(1)}MB -> resume`);
  }

  if (st.dropping) {
    // 只放行关键帧（GOP 起点），其余全丢：这样解码器永远不会拿到断链的残帧
    if (!isGopStart(raw)) {
      st.droppedFrames++;
      return;
    }
    st.dropping = false;
  }

  try {
    target.send(raw);
    st.sentBytes += raw.length;
    st.bytesSince += raw.length;
  } catch (e) { }
}

/** 每 10 秒打印一次实际转发带宽，用来判断 VPS 出口是否够用 */
setInterval(() => {
  for (const [, r] of rooms) {
    for (const [role, ws] of [['host', r.host], ['viewer', r.viewer]]) {
      if (!ws) continue;
      const st = state(ws);
      const now = Date.now();
      const dt = (now - st.lastStat) / 1000;
      if (dt >= 9 && st.bytesSince > 0) {
        const mbps = (st.bytesSince * 8) / dt / 1e6;
        console.log(
          `[stat] ${role} room=${[...rooms.entries()].find(([, v]) => v.host === ws || v.viewer === ws)?.[0] || '?'} ` +
          `out=${mbps.toFixed(2)}Mbps buffered=${((ws.bufferedAmount || 0) / 1048576).toFixed(1)}MB ` +
          `dropped=${st.droppedFrames}`
        );
        st.lastStat = now;
        st.bytesSince = 0;
      }
    }
  }
}, 10000).unref();

function relay(a, b) {
  a.on('message', (data, isBinary) => {
    if (b && b.readyState === 1) b.send(data, { binary: isBinary });
  });
}

wss.on('connection', (ws) => {
  let joined = null;
  ws.on('message', (raw, isBinary) => {
    if (isBinary) {
      // 视频帧：走高水位判断后再转发
      if (joined && joined.room) {
        const r = rooms.get(joined.room);
        const peer = joined.role === 'host' ? r && r.viewer : r && r.host;
        forwardBinary(peer, raw);
      }
      return;
    }
    let m; try { m = JSON.parse(raw.toString()); } catch (e) { return; }
    if (m.t === 'host') {
      const r = rooms.get(m.room) || {};
      if (r.host && r.host !== ws) r.host.close();
      r.host = ws; rooms.set(m.room, r); joined = { room: m.room, role: 'host' };
      send(ws, { t: 'hosted', room: m.room });
      console.log(`[host] room=${m.room}`);
    } else if (m.t === 'join') {
      const r = rooms.get(m.room);
      if (!r || !r.host) { send(ws, { t: 'deny', reason: '房间不存在或被控端不在线' }); ws.close(); return; }
      if (r.viewer && r.viewer !== ws) r.viewer.close();
      r.viewer = ws; joined = { room: m.room, role: 'viewer' };
      send(ws, { t: 'joined' });
      console.log(`[join] room=${m.room}`);
      // 转发统一走下面的 else 分支，这里不再挂 listener（否则双重转发）
    } else if (joined && joined.room) {
      // 已入房的普通消息（pair/input/accept/size 等）以文本透传给对端
      const r = rooms.get(joined.room);
      const peer = joined.role === 'host' ? r && r.viewer : r && r.host;
      if (peer && peer.readyState === 1) peer.send(raw.toString());
    }
  });
  ws.on('close', () => {
    if (joined) {
      const r = rooms.get(joined.room);
      if (r) {
        if (joined.role === 'host') { r.host = null; if (r.viewer) r.viewer.close(); }
        else { r.viewer = null; if (r.host) send(r.host, { t: 'note', msg: '观众离开' }); }
        if (!r.host && !r.viewer) rooms.delete(joined.room);
      }
      console.log(`[left] ${joined.role} room=${joined.room}`);
    }
  });
});
console.log(`HandLink relay listening on :${PORT}`);
console.log(`backpressure: high=${HIGH_WATER / 1048576}MB low=${LOW_WATER / 1048576}MB`);