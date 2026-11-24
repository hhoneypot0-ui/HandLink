// 在 VPS 本机跑：模拟被控端(host) + 控制端(viewer) 经中继的双向转发
const WebSocket = require('ws');
const URL = 'ws://127.0.0.1:8724';
const host = new WebSocket(URL);
let viewer = null;
const log = (m) => console.log(m);

host.on('open', () => host.send(JSON.stringify({ t: 'host', room: '246810' })));
host.on('message', (d, isBin) => {
  const s = JSON.parse(d.toString());
  log('[host<-] ' + s.t);
  if (s.t === 'pair') {
    host.send(JSON.stringify({ t: 'accept', peer: '本机' }));
    host.send(JSON.stringify({ t: 'size', w: 486, h: 1080 }));
    host.send(Buffer.from([0x03, 0,0,0,0,0,0,0,1, 0xDE, 0xAD, 0xBE, 0xEF])); // config帧
  }
});
setTimeout(() => {
  viewer = new WebSocket(URL);
  viewer.on('open', () => viewer.send(JSON.stringify({ t: 'join', room: '246810' })));
  let got = { joined: false, accept: false, size: false, bin: false };
  viewer.on('message', (d, isBin) => {
    if (isBin) {
      got.bin = true; log('[viewer<-] BINARY ' + d.length + 'B');
      if (got.accept && got.size && got.bin) { log('SELFTEST_PASS'); process.exit(0); }
      return;
    }
    const s = JSON.parse(d.toString());
    got[s.t] = true;
    log('[viewer<-] ' + JSON.stringify(s).slice(0, 60));
    if (s.t === 'joined') viewer.send(JSON.stringify({ t: 'pair', code: '246810', name: '测试机' }));
    if (got.accept && got.size && got.bin) {
      log('SELFTEST_PASS');
      process.exit(0);
    }
  });
  viewer.on('error', e => { log('viewer ERR ' + e.message); process.exit(1); });
}, 800);
setTimeout(() => { log('SELFTEST_TIMEOUT'); process.exit(1); }, 8000);
